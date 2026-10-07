package com.sashank.map_shortest_path_finder.pipeline;

import com.sashank.map_shortest_path_finder.support.EmbeddedPg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The importer's SQL, exercised against a real PostgreSQL. */
class TileImporterIntegrationTest {

    private JdbcTemplate jdbc;
    private TileImporter importer;
    private final OsmParser parser = new OsmParser();

    @BeforeEach
    void setUp() {
        EmbeddedPg.freshSchema();
        jdbc = EmbeddedPg.jdbc();
        importer = new TileImporter(jdbc, EmbeddedPg.txManager());
        importer.ensureProgressTable();
    }

    // ── helpers: build Overpass-shaped responses ──────────────────────────────

    private static OsmResponse.OsmElement node(long id, double lat, double lon) {
        var e = new OsmResponse.OsmElement();
        e.setType("node"); e.setId(id); e.setLat(lat); e.setLon(lon);
        return e;
    }

    private static OsmResponse.OsmElement way(long id, List<Long> nodes, Map<String, String> tags) {
        var e = new OsmResponse.OsmElement();
        e.setType("way"); e.setId(id); e.setNodes(nodes); e.setTags(tags);
        return e;
    }

    private static OsmResponse response(OsmResponse.OsmElement... elements) {
        var r = new OsmResponse();
        r.setElements(List.of(elements));
        return r;
    }

    private TileImporter.Result importTile(String key, OsmResponse r) {
        return importer.importTile(key, parser.parse(r, importer.seenWayIds()), 0);
    }

    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    void storesNodesAndEdges_withTheirAttributes_andTriStateBooleansIntact() {
        var r = response(
            node(1, 16.50, 80.60), node(2, 16.51, 80.61), node(3, 16.52, 80.62),
            way(100, List.of(1L, 2L), Map.of("highway", "trunk", "toll", "yes", "surface", "asphalt", "lit", "no", "oneway", "yes")),
            way(101, List.of(2L, 3L), Map.of("highway", "residential")));

        var result = importTile("t1", r);

        assertEquals(3, result.newNodes());
        assertEquals(3, result.newEdges());                          // way 100 one-way = 1 edge, way 101 two-way = 2
        assertEquals(3, count("nodes"));
        assertEquals(3, count("edges"));

        Map<String, Object> tolled = jdbc.queryForMap("SELECT highway, toll, lit, paved, speed_kmh, osm_way_id FROM edges WHERE osm_way_id = 100");
        assertEquals("trunk", tolled.get("highway"));
        assertEquals(Boolean.TRUE, tolled.get("toll"));
        assertEquals(Boolean.FALSE, tolled.get("lit"));
        assertEquals(Boolean.TRUE, tolled.get("paved"));
        assertEquals(70.0, (Double) tolled.get("speed_kmh"), 1e-9);   // trunk gets its own speed, not the 40 km/h default

        Map<String, Object> untagged = jdbc.queryForMap("SELECT toll, lit, paved FROM edges WHERE osm_way_id = 101 LIMIT 1");
        assertNull(untagged.get("toll"));                             // unknown stays NULL, not false
        assertNull(untagged.get("lit"));
        assertNull(untagged.get("paved"));
    }

    @Test
    void edgesPointAtTheRightNodes_andDistancesAreReal() {
        importTile("t1", response(node(10, 16.50, 80.60), node(20, 16.51, 80.60),
            way(1, List.of(10L, 20L), Map.of("highway", "primary", "oneway", "yes"))));

        Map<String, Object> e = jdbc.queryForMap("""
            SELECT a.osm_id AS from_osm, b.osm_id AS to_osm, e.distance_meters
            FROM edges e JOIN nodes a ON a.id = e.from_node_id JOIN nodes b ON b.id = e.to_node_id""");
        assertEquals(10L, e.get("from_osm"));
        assertEquals(20L, e.get("to_osm"));
        assertEquals(1111.9, (Double) e.get("distance_meters"), 2.0);   // 0.01° of latitude
    }

    @Test
    void aWayReturnedByTwoTiles_andSharedNodes_areStoredOnce() {
        var tileA = response(node(1, 16.50, 80.60), node(2, 16.51, 80.61), node(3, 16.52, 80.62),
            way(100, List.of(1L, 2L, 3L), Map.of("highway", "primary")));
        // the neighbouring tile also returns way 100 (it crosses the border) plus a new way sharing node 3
        var tileB = response(node(2, 16.51, 80.61), node(3, 16.52, 80.62), node(4, 16.53, 80.63),
            way(100, List.of(1L, 2L, 3L), Map.of("highway", "primary")),
            way(200, List.of(3L, 4L), Map.of("highway", "secondary")));

        importTile("a", tileA);
        long edgesAfterA = count("edges");
        var resultB = importTile("b", tileB);

        assertEquals(4, count("nodes"), "node 3 must not be inserted twice");
        assertEquals(1, resultB.newNodes());
        assertEquals(edgesAfterA + 2, count("edges"), "only the new way's two directed edges are added");
        assertEquals(Set.of(100L, 200L), importer.seenWayIds());
        assertEquals(2, count("import_tiles"));

        // the new way is connected to the node that came from the first tile
        Long linked = jdbc.queryForObject("""
            SELECT count(*) FROM edges e JOIN nodes n ON n.id = e.from_node_id WHERE e.osm_way_id = 200 AND n.osm_id = 3""", Long.class);
        assertEquals(1L, linked);
    }

    @Test
    void aFailingTile_leavesNothingBehind_andTheImporterStillWorksAfterwards() {
        // highway longer than VARCHAR(32) makes the edge insert fail AFTER the nodes were already inserted
        var bad = response(node(1, 16.50, 80.60), node(2, 16.51, 80.61),
            way(100, List.of(1L, 2L), Map.of("highway", "x".repeat(40))));

        assertThrows(RuntimeException.class, () -> importTile("bad", bad));

        assertEquals(0, count("nodes"), "node inserts must have been rolled back with the tile");
        assertEquals(0, count("edges"));
        assertEquals(0, count("import_tiles"), "a failed tile must not be recorded as done");
        assertTrue(importer.seenWayIds().isEmpty(), "in-memory state must not learn about a rolled-back tile");

        importTile("good", response(node(1, 16.50, 80.60), node(2, 16.51, 80.61),
            way(100, List.of(1L, 2L), Map.of("highway", "primary"))));
        assertEquals(2, count("nodes"));
        assertEquals(Set.of("good"), importer.completedTileKeys());
    }

    @Test
    void resuming_rebuildsTheInMemoryStateFromTheDatabase() {
        importTile("a", response(node(1, 16.50, 80.60), node(2, 16.51, 80.61), node(3, 16.52, 80.62),
            way(100, List.of(1L, 2L, 3L), Map.of("highway", "primary"))));

        TileImporter restarted = new TileImporter(jdbc, EmbeddedPg.txManager());   // a new process: empty memory
        assertTrue(restarted.seenWayIds().isEmpty());
        restarted.loadStateFromDatabase();
        assertEquals(Set.of(100L), restarted.seenWayIds());
        assertEquals(Set.of("a"), restarted.completedTileKeys());

        var tileB = response(node(3, 16.52, 80.62), node(4, 16.53, 80.63),
            way(100, List.of(1L, 2L, 3L), Map.of("highway", "primary")),     // repeated way → skipped
            way(200, List.of(3L, 4L), Map.of("highway", "secondary")));      // new way joining an OLD node
        restarted.importTile("b", parser.parse(tileB, restarted.seenWayIds()), 0);

        assertEquals(4, count("nodes"), "node 3 already existed and must be reused after a restart");
        assertEquals(6, count("edges"));        // way 100: 2 segments x 2 directions + way 200: 1 x 2 → 4 + 2
    }

    @Test
    void truncateAll_emptiesEverything_andRestartsIds() {
        importTile("a", response(node(1, 16.50, 80.60), node(2, 16.51, 80.61), way(100, List.of(1L, 2L), Map.of("highway", "primary"))));
        assertTrue(count("nodes") > 0);

        importer.truncateAll();

        assertEquals(0, count("nodes"));
        assertEquals(0, count("edges"));
        assertEquals(0, count("import_tiles"));
        assertTrue(importer.seenWayIds().isEmpty());
        importTile("again", response(node(7, 16.50, 80.60), node(8, 16.51, 80.61), way(1, List.of(7L, 8L), Map.of("highway", "primary"))));
        assertEquals(1L, jdbc.queryForObject("SELECT min(id) FROM nodes", Long.class), "identity must restart");
    }

    @Test
    void ensureProgressTable_isIdempotent() {
        importer.ensureProgressTable();
        importer.ensureProgressTable();
        assertEquals(Set.of(), importer.completedTileKeys());
    }

    @Test
    void largeTile_isInsertedInChunks() {
        List<OsmResponse.OsmElement> els = new ArrayList<>();
        int n = 5_000;                                                     // > the 2 000-row insert chunk
        for (int i = 1; i <= n; i++) els.add(node(i, 16.0 + i * 1e-5, 80.0 + i * 1e-5));
        for (int i = 1; i < n; i += 2) els.add(way(1_000_000 + i, List.of((long) i, (long) i + 1), Map.of("highway", "residential")));
        var r = new OsmResponse();
        r.setElements(els);

        var result = importTile("big", r);

        assertEquals(n, result.newNodes());
        assertEquals(n, count("nodes"));
        assertEquals(2 * (n / 2), count("edges"));
    }

    @Test
    void theRealInitSql_declaresEveryColumnAndTableTheImporterWrites() throws Exception {
        // Guards against init.sql and the importer drifting apart (this test's own DDL mirrors init.sql by hand).
        String sql = Files.readString(Path.of(System.getProperty("user.dir"), "docker", "postgres", "init.sql"));
        for (String needed : List.of("osm_way_id", "highway", "toll", "lit", "paved", "speed_kmh", "distance_meters",
                                     "import_tiles", "tile_key", "nodes_added", "edges_added", "from_node_id", "to_node_id")) {
            assertTrue(sql.contains(needed), "init.sql no longer mentions '" + needed + "'");
        }
    }
}
