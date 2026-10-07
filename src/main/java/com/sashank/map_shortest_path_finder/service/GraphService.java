package com.sashank.map_shortest_path_finder.service;

import com.sashank.map_shortest_path_finder.model.EdgeAttrs;
import com.sashank.map_shortest_path_finder.model.Node;
import com.sashank.map_shortest_path_finder.model.RoadClass;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * Loads the entire road graph from PostgreSQL into memory once at startup.
 *
 * Why in-memory?
 *   Running Dijkstra with live DB queries (one SELECT per edge relaxation) would produce hundreds of thousands
 *   of round-trips per route request. Held in memory, each route is a pure in-memory operation.
 *
 * Sizing: a multi-city corridor is ~1M nodes / ~2.5M directed edges, which needs roughly 1–1.5 GB of heap with
 * these structures. The load streams rows from the database (a read-only transaction with a fetch size, so the JDBC
 * driver uses a cursor rather than buffering whole tables) and shares identical EdgeAttrs instances between edges.
 * Give the JVM headroom, e.g. JAVA_TOOL_OPTIONS=-Xmx3g, and watch the "Graph loaded" log line for the real figure.
 *
 * If the import hasn't run yet the graph will be empty. The isEmpty() check in PathController lets the API
 * return a clear 503 instead of a confusing 404.
 */
@Service
// Hibernate (ddl-auto=update) may add columns this query selects; make sure it has run first.
@DependsOn("entityManagerFactory")
public class GraphService {

    private static final Logger log = LoggerFactory.getLogger(GraphService.class);
    private static final int FETCH_SIZE = 20_000;

    /**
     * One entry in the adjacency list: the ID of a reachable neighbour, plus both
     * weights DijkstraService can route on. timeSeconds is derived from
     * distanceMeters / speedKmh here rather than stored, so retuning speed
     * assumptions never requires a re-import. attrs carries road class / toll / lit / paved
     * for intent-based routing; Dijkstra and Yen's ignore it.
     */
    public record Neighbor(long toNodeId, double distanceMeters, double timeSeconds, EdgeAttrs attrs) {
        /** Attribute-less edge (hand-built graphs, unit tests). */
        public Neighbor(long toNodeId, double distanceMeters, double timeSeconds) {
            this(toNodeId, distanceMeters, timeSeconds, EdgeAttrs.UNKNOWN);
        }
    }

    /**
     * Fraction (0..1) of directed edges whose attribute is known (non-null / non-UNKNOWN).
     * Lets the API explain why a hard constraint can't be verified, and flags a graph that
     * was imported before road attributes were captured (all zeros → re-import needed).
     */
    public record AttributeCoverage(long edges, double roadClass, double toll, double surface, double lighting) {}

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private Environment environment;

    private AttributeCoverage coverage = new AttributeCoverage(0, 0, 0, 0, 0);

    // nodeId → ordered list of outgoing neighbours
    private Map<Long, List<Neighbor>> adjacency = Collections.emptyMap();

    // nodeId → Node entity (for lat/lng lookups when building the path response)
    private Map<Long, Node> nodeIndex = Collections.emptyMap();

    @PostConstruct
    public void loadGraph() {
        if (environment.acceptsProfiles(Profiles.of("import"))) {
            log.info("'import' profile active: not loading the graph into memory.");
            return;
        }
        long startedNanos = System.nanoTime();

        Long nodeCountOrNull = jdbc.queryForObject("SELECT count(*) FROM nodes", Long.class);
        long nodeCount = nodeCountOrNull == null ? 0 : nodeCountOrNull;
        if (nodeCount == 0) {
            log.warn("Graph is empty — run the import first: "
                   + "./mvnw spring-boot:run -Dspring-boot.run.profiles=import");
            return;
        }

        int capacity = (int) Math.min(Integer.MAX_VALUE / 2, nodeCount * 4 / 3 + 16);
        Map<Long, Node> index = new HashMap<>(capacity);
        Map<Long, List<Neighbor>> adj = new HashMap<>(capacity);
        Map<EdgeAttrs, EdgeAttrs> interned = new HashMap<>();
        long[] counts = new long[5]; // edges, class, toll, surface, lit

        // A read-only transaction turns autocommit off, which is what lets the PostgreSQL driver stream with a cursor.
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        JdbcTemplate streaming = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        streaming.setFetchSize(FETCH_SIZE);

        readOnly.executeWithoutResult(status -> {
            streaming.query("SELECT id, osm_id, lat, lng FROM nodes", rs -> {
                long id = rs.getLong(1);
                index.put(id, Node.builder().id(id).osmId(rs.getLong(2)).lat(rs.getDouble(3)).lng(rs.getDouble(4)).build());
                adj.put(id, new ArrayList<>(3));
            });

            streaming.query("""
                SELECT from_node_id, to_node_id, distance_meters, speed_kmh, highway, toll, lit, paved
                FROM edges""", rs -> {
                String highway = rs.getString(5);
                Boolean toll = nullableBoolean(rs, 6), lit = nullableBoolean(rs, 7), paved = nullableBoolean(rs, 8);
                EdgeAttrs attrs = new EdgeAttrs(RoadClass.fromOsm(highway), toll, lit, paved);
                attrs = interned.computeIfAbsent(attrs, a -> a);

                double distance = rs.getDouble(3);
                double speedMs = rs.getDouble(4) * 1000.0 / 3600.0;
                adj.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>(3))
                   .add(new Neighbor(rs.getLong(2), distance, distance / speedMs, attrs));

                counts[0]++;
                if (highway != null) counts[1]++;
                if (toll != null) counts[2]++;
                if (paved != null) counts[3]++;
                if (lit != null) counts[4]++;
            });
        });

        double t = Math.max(counts[0], 1);
        this.coverage = new AttributeCoverage(counts[0], counts[1] / t, counts[2] / t, counts[3] / t, counts[4] / t);
        if (counts[0] > 0 && counts[1] == 0) {
            log.warn("Edges have no road attributes (highway/toll/surface/lit) — the graph was imported before they "
                   + "were captured. Re-import with --force-reimport; hard constraints can't be verified until then.");
        } else {
            log.info("Road attribute coverage (share of edges tagged): highway {}%, toll {}%, surface {}%, lit {}%.",
                     Math.round(100.0 * counts[1] / t), Math.round(100.0 * counts[2] / t),
                     Math.round(100.0 * counts[3] / t), Math.round(100.0 * counts[4] / t));
        }

        // Swap in the new graph atomically so reads during reload see a consistent view
        this.nodeIndex = Collections.unmodifiableMap(index);
        this.adjacency = Collections.unmodifiableMap(adj);

        Runtime rt = Runtime.getRuntime();
        log.info("Graph loaded into memory: {} nodes, {} directed edges in {} ms (heap in use ~{} MB of max {} MB; "
               + "{} distinct edge-attribute combinations).",
                 index.size(), counts[0], (System.nanoTime() - startedNanos) / 1_000_000,
                 (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024), rt.maxMemory() / (1024 * 1024), interned.size());
    }

    private static Boolean nullableBoolean(java.sql.ResultSet rs, int column) throws java.sql.SQLException {
        boolean v = rs.getBoolean(column);
        return rs.wasNull() ? null : v;
    }

    public Map<Long, List<Neighbor>> getAdjacency() { return adjacency; }
    public Map<Long, Node>           getNodeIndex()  { return nodeIndex; }
    public boolean                   isEmpty()        { return nodeIndex.isEmpty(); }
    public AttributeCoverage         getCoverage()    { return coverage; }
}
