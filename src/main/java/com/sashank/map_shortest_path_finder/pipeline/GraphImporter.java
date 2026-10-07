package com.sashank.map_shortest_path_finder.pipeline;

import com.sashank.map_shortest_path_finder.config.RegionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Orchestrates the OSM → PostgreSQL data pipeline, tile by tile.
 *
 * Only active when the "import" Spring profile is set, so it never runs automatically during normal startup.
 *
 *   ./mvnw spring-boot:run -Dspring-boot.run.profiles=import
 *   ./mvnw spring-boot:run -Dspring-boot.run.profiles=import -Dspring-boot.run.arguments=--force-reimport
 *
 * For a multi-city region this takes a long time (many Overpass requests, politely spaced). It is RESTARTABLE:
 * each finished tile is recorded in the import_tiles table, so after a failure — or Ctrl-C — running the same
 * command again continues with the tiles that are left. --force-reimport wipes everything and starts over.
 * If the region configuration changes (new area, different filter), only the new tiles are fetched.
 *
 * Steps per tile: fetch (Overpass) → parse (skipping ways already stored) → store (one transaction) → pause.
 */
@Component
@Profile("import")
public class GraphImporter implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(GraphImporter.class);

    @Autowired private RegionConfig regionConfig;
    @Autowired private TilePlanner tilePlanner;
    @Autowired private OsmDataFetcher osmDataFetcher;
    @Autowired private OsmParser osmParser;
    @Autowired private TileImporter tileImporter;
    @Autowired private JdbcTemplate jdbc;

    @Override
    public void run(String... args) throws InterruptedException {
        boolean forceReimport = Arrays.asList(args).contains("--force-reimport");

        tileImporter.ensureProgressTable();
        List<TilePlanner.Tile> tiles = tilePlanner.plan(regionConfig);
        long existingNodes = tileImporter.nodeCount();

        if (forceReimport && existingNodes > 0) {
            log.info("Force re-import requested — clearing existing graph data...");
            tileImporter.truncateAll();
            existingNodes = 0;
        }

        Set<String> done = tileImporter.completedTileKeys();
        if (!forceReimport && existingNodes > 0 && done.isEmpty()) {
            log.info("A graph is already stored ({} nodes) but it was imported without progress tracking, so it cannot be "
                   + "extended safely. Pass --force-reimport to clear it and import the configured region.", existingNodes);
            return;
        }

        List<TilePlanner.Tile> pending = tiles.stream().filter(t -> !done.contains(t.key())).toList();
        if (pending.isEmpty()) {
            log.info("Import already complete ({} tiles, {} nodes, {} edges). Pass --force-reimport to start over.",
                     tiles.size(), existingNodes, tileImporter.edgeCount());
            return;
        }
        if (existingNodes > 0) {
            tileImporter.loadStateFromDatabase();
        }
        log.info("Importing '{}': {} tile(s) to fetch ({} already done). Each is one Overpass request; this can take a while.",
                 regionConfig.getName(), pending.size(), tiles.size() - pending.size());

        long started = System.nanoTime();
        int index = 0;
        for (TilePlanner.Tile tile : pending) {
            index++;
            log.info("[{}/{}] {} ...", index, pending.size(), tile.label());
            OsmResponse osm = osmDataFetcher.fetchRoadNetwork(tile.bbox(), tile.highwayFilter());

            int before = osm.getElements() == null ? 0 : (int) osm.getElements().stream().filter(e -> "way".equals(e.getType())).count();
            OsmParser.ParsedGraph parsed = osmParser.parse(osm, tileImporter.seenWayIds());
            int skipped = before - (int) parsed.edges().stream().mapToLong(OsmParser.ParsedEdge::osmWayId).distinct().count();
            TileImporter.Result r = tileImporter.importTile(tile.key(), parsed, Math.max(skipped, 0));

            log.info("[{}/{}] +{} nodes, +{} edges ({} elapsed)", index, pending.size(), r.newNodes(), r.newEdges(),
                     human(Duration.ofNanos(System.nanoTime() - started)));
            if (index < pending.size()) {
                Thread.sleep(regionConfig.getImporter().getRequestDelayMillis()); // be polite to the public Overpass server
            }
        }

        // Fresh statistics so the planner uses the GiST index for nearest-node (KNN) lookups.
        jdbc.execute("ANALYZE nodes");
        jdbc.execute("ANALYZE edges");

        long finalNodes = tileImporter.nodeCount();
        long finalEdges = tileImporter.edgeCount();
        log.info("=== Import complete ===");
        log.info("  Nodes : {}", finalNodes);
        log.info("  Edges : {}", finalEdges);
        if (finalEdges < finalNodes) {
            log.warn("Edge count ({}) is less than node count ({}) — this is unusual. Check the Overpass responses and filters.",
                     finalEdges, finalNodes);
        } else {
            log.info("Sanity check passed (edges > nodes, as expected for a road network).");
        }
        log.info("Start the API with enough heap for the graph, e.g. JAVA_TOOL_OPTIONS=-Xmx3g (see README).");
    }

    private static String human(Duration d) {
        return "%d:%02d:%02d".formatted(d.toHours(), d.toMinutesPart(), d.toSecondsPart());
    }
}
