package com.sashank.map_shortest_path_finder.pipeline;

import com.sashank.map_shortest_path_finder.model.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.*;

/**
 * Writes one parsed Overpass tile into PostgreSQL, and remembers what has been written so the next tile (and a
 * restarted import) can skip it.
 *
 * Why plain JDBC rather than JPA: the entities use IDENTITY ids, which switches off Hibernate's insert batching —
 * every row would be its own round trip, hours for ~1M nodes / ~2.5M edges. Here nodes go in with multi-row
 * unnest() inserts and edges with batched statements.
 *
 * Each tile is ONE transaction (nodes, edges, and its progress row), so a crash leaves either the whole tile or
 * nothing, and "tile recorded as done" always means "all its data is present".
 *
 * State kept in memory for the duration of an import (rebuilt from the database when resuming):
 *   osmIdToDbId — every node already stored, so nodes shared between tiles are inserted once;
 *   seenWayIds  — every way already turned into edges, so a way that crosses a tile border (it is returned by both
 *                 tiles) — or appears in both the corridor layer and a city layer — yields its edges only once.
 */
@Component
public class TileImporter {

    private static final Logger log = LoggerFactory.getLogger(TileImporter.class);
    private static final int NODE_CHUNK = 2_000;
    private static final int EDGE_BATCH = 2_000;

    public record Result(int newNodes, int newEdges, int skippedWays) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    private final Map<Long, Long> osmIdToDbId = new HashMap<>();
    private final Set<Long> seenWayIds = new HashSet<>();

    public TileImporter(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    // ── schema / state ───────────────────────────────────────────────────────

    public void ensureProgressTable() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS import_tiles (
                tile_key     TEXT PRIMARY KEY,
                completed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                nodes_added  INTEGER NOT NULL DEFAULT 0,
                edges_added  INTEGER NOT NULL DEFAULT 0
            )""");
    }

    public Set<String> completedTileKeys() {
        return new HashSet<>(jdbc.queryForList("SELECT tile_key FROM import_tiles", String.class));
    }

    public long nodeCount() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM nodes", Long.class);
        return n == null ? 0 : n;
    }

    public long edgeCount() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM edges", Long.class);
        return n == null ? 0 : n;
    }

    /** Empties the graph. TRUNCATE (not deleteAll): it must not load millions of entities to delete them one by one. */
    public void truncateAll() {
        ensureProgressTable();
        jdbc.execute("TRUNCATE TABLE edges, nodes, import_tiles RESTART IDENTITY");
        osmIdToDbId.clear();
        seenWayIds.clear();
    }

    /** Rebuilds the in-memory state from what is already in the database (used when resuming). */
    public void loadStateFromDatabase() {
        osmIdToDbId.clear();
        seenWayIds.clear();
        jdbc.query("SELECT osm_id, id FROM nodes", rs -> { osmIdToDbId.put(rs.getLong(1), rs.getLong(2)); });
        jdbc.query("SELECT DISTINCT osm_way_id FROM edges WHERE osm_way_id IS NOT NULL", rs -> { seenWayIds.add(rs.getLong(1)); });
        log.info("Resuming: {} nodes and {} ways already stored.", osmIdToDbId.size(), seenWayIds.size());
    }

    /** OSM way ids already imported — the parser skips these so a way is never turned into edges twice. */
    public Set<Long> seenWayIds() { return Collections.unmodifiableSet(seenWayIds); }

    // ── the import itself ────────────────────────────────────────────────────

    /** Stores one tile and marks it complete, atomically. */
    public Result importTile(String tileKey, OsmParser.ParsedGraph parsed, int skippedWays) {
        Map<Long, Long> stagedIds = new HashMap<>();
        Set<Long> stagedWays = new HashSet<>();

        Result result = tx.execute(status -> {
            int newNodes = insertNewNodes(parsed.nodes(), stagedIds);
            int newEdges = insertEdges(parsed.edges(), stagedIds, stagedWays);
            jdbc.update("INSERT INTO import_tiles (tile_key, nodes_added, edges_added) VALUES (?, ?, ?) "
                + "ON CONFLICT (tile_key) DO UPDATE SET completed_at = now(), nodes_added = EXCLUDED.nodes_added, "
                + "edges_added = EXCLUDED.edges_added", tileKey, newNodes, newEdges);
            return new Result(newNodes, newEdges, skippedWays);
        });

        // Only after the transaction committed do the in-memory maps learn about the tile.
        osmIdToDbId.putAll(stagedIds);
        seenWayIds.addAll(stagedWays);
        return result;
    }

    private int insertNewNodes(List<Node> nodes, Map<Long, Long> stagedIds) {
        List<Node> fresh = nodes.stream().filter(n -> !osmIdToDbId.containsKey(n.getOsmId())).toList();
        for (int from = 0; from < fresh.size(); from += NODE_CHUNK) {
            List<Node> chunk = fresh.subList(from, Math.min(from + NODE_CHUNK, fresh.size()));
            Long[] osmIds = new Long[chunk.size()];
            Double[] lats = new Double[chunk.size()];
            Double[] lngs = new Double[chunk.size()];
            for (int i = 0; i < chunk.size(); i++) {
                osmIds[i] = chunk.get(i).getOsmId();
                lats[i] = chunk.get(i).getLat();
                lngs[i] = chunk.get(i).getLng();
            }
            jdbc.query((Connection con) -> {
                PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO nodes (osm_id, lat, lng)
                    SELECT * FROM unnest(?::bigint[], ?::double precision[], ?::double precision[])
                    ON CONFLICT (osm_id) DO NOTHING
                    RETURNING osm_id, id""");
                ps.setArray(1, con.createArrayOf("bigint", osmIds));
                ps.setArray(2, con.createArrayOf("float8", lats));
                ps.setArray(3, con.createArrayOf("float8", lngs));
                return ps;
            }, rs -> { stagedIds.put(rs.getLong(1), rs.getLong(2)); });
        }

        // Safety net: a node that already existed in the database but was not in our map (it should not happen — the
        // map is rebuilt from the table on resume) would be skipped by ON CONFLICT and lose its edges. Look it up.
        List<Long> unresolved = fresh.stream().map(Node::getOsmId).filter(id -> !stagedIds.containsKey(id)).toList();
        int inserted = stagedIds.size();
        for (int from = 0; from < unresolved.size(); from += NODE_CHUNK) {
            Long[] ids = unresolved.subList(from, Math.min(from + NODE_CHUNK, unresolved.size())).toArray(Long[]::new);
            jdbc.query((Connection con) -> {
                PreparedStatement ps = con.prepareStatement("SELECT osm_id, id FROM nodes WHERE osm_id = ANY(?)");
                ps.setArray(1, con.createArrayOf("bigint", ids));
                return ps;
            }, rs -> { stagedIds.put(rs.getLong(1), rs.getLong(2)); });
        }
        return inserted;
    }

    private int insertEdges(List<OsmParser.ParsedEdge> edges, Map<Long, Long> stagedIds, Set<Long> stagedWays) {
        List<Object[]> rows = new ArrayList<>(edges.size());
        for (OsmParser.ParsedEdge e : edges) {
            Long from = lookup(e.fromOsmId(), stagedIds), to = lookup(e.toOsmId(), stagedIds);
            if (from == null || to == null) continue; // endpoint outside what was fetched
            rows.add(new Object[]{from, to, e.distanceMeters(), e.speedKmh(), e.osmWayId(), e.highway(), e.toll(), e.lit(), e.paved()});
            stagedWays.add(e.osmWayId());
        }
        jdbc.batchUpdate("""
            INSERT INTO edges (from_node_id, to_node_id, distance_meters, speed_kmh, osm_way_id, highway, toll, lit, paved)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", rows, EDGE_BATCH, (ps, r) -> {
            ps.setLong(1, (Long) r[0]);
            ps.setLong(2, (Long) r[1]);
            ps.setDouble(3, (Double) r[2]);
            ps.setDouble(4, (Double) r[3]);
            ps.setLong(5, (Long) r[4]);
            if (r[5] == null) ps.setNull(6, Types.VARCHAR); else ps.setString(6, (String) r[5]);
            setNullableBoolean(ps, 7, (Boolean) r[6]);
            setNullableBoolean(ps, 8, (Boolean) r[7]);
            setNullableBoolean(ps, 9, (Boolean) r[8]);
        });
        return rows.size();
    }

    private Long lookup(long osmId, Map<Long, Long> staged) {
        Long id = staged.get(osmId);
        return id != null ? id : osmIdToDbId.get(osmId);
    }

    private static void setNullableBoolean(PreparedStatement ps, int index, Boolean value) throws java.sql.SQLException {
        if (value == null) ps.setNull(index, Types.BOOLEAN); else ps.setBoolean(index, value);
    }
}
