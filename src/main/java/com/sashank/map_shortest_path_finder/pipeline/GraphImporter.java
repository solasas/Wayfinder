package com.sashank.map_shortest_path_finder.pipeline;

import com.sashank.map_shortest_path_finder.config.RegionConfig;
import com.sashank.map_shortest_path_finder.model.Edge;
import com.sashank.map_shortest_path_finder.model.Node;
import com.sashank.map_shortest_path_finder.repository.EdgeRepository;
import com.sashank.map_shortest_path_finder.repository.NodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Orchestrates the full OSM → PostgreSQL data pipeline.
 *
 * Only active when the "import" Spring profile is set, so it never runs
 * automatically during normal application startup.
 *
 * Usage:
 *   ./mvnw spring-boot:run -Dspring-boot.run.profiles=import
 *
 * To force a fresh re-import (clears existing data first):
 *   ./mvnw spring-boot:run -Dspring-boot.run.profiles=import \
 *          -Dspring-boot.run.arguments=--force-reimport
 *
 * Pipeline steps:
 *   1. Fetch raw OSM data via Overpass API
 *   2. Parse into Node entities + ParsedEdge descriptors
 *   3. Batch-save Nodes (Hibernate assigns DB primary keys)
 *   4. Translate ParsedEdges (OSM IDs) → Edge entities (DB IDs) and save
 *   5. Log counts as a sanity check
 */
@Component
@Profile("import")
public class GraphImporter implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(GraphImporter.class);

    @Autowired private RegionConfig regionConfig;
    @Autowired private OsmDataFetcher osmDataFetcher;
    @Autowired private OsmParser osmParser;
    @Autowired private NodeRepository nodeRepository;
    @Autowired private EdgeRepository edgeRepository;

    @Override
    public void run(String... args) {
        boolean forceReimport = Arrays.asList(args).contains("--force-reimport");

        long existingNodes = nodeRepository.count();
        if (!forceReimport && existingNodes > 0) {
            log.info("Graph already imported ({} nodes, {} edges). "
                   + "Pass --force-reimport to clear and re-import.",
                     existingNodes, edgeRepository.count());
            return;
        }

        if (forceReimport && existingNodes > 0) {
            log.info("Force re-import requested — deleting existing graph data...");
            // Edges must be deleted before nodes (foreign key constraint)
            edgeRepository.deleteAll();
            nodeRepository.deleteAll();
            log.info("Existing data cleared.");
        }

        // ── Step 1: Fetch ─────────────────────────────────────────────────────
        log.info("Fetching road network for '{}' ...", regionConfig.getName());
        OsmResponse osmData = osmDataFetcher.fetchRoadNetwork(regionConfig.getBbox());

        // ── Step 2: Parse ─────────────────────────────────────────────────────
        log.info("Parsing OSM elements into graph...");
        OsmParser.ParsedGraph parsed = osmParser.parse(osmData);

        // ── Step 3: Save nodes ────────────────────────────────────────────────
        // saveAll() runs a batched INSERT (batch_size=500 from application.properties).
        // After this call, each Node object in the list has its DB-assigned id populated.
        log.info("Saving {} nodes...", parsed.nodes().size());
        List<Node> savedNodes = nodeRepository.saveAll(parsed.nodes());

        // Build osmId → DB primary key map so we can wire up edge FKs.
        // This is necessary because edges in ParsedGraph use OSM IDs (the only
        // IDs we have before saving), but Edge entities need DB primary keys.
        Map<Long, Long> osmIdToDbId = savedNodes.stream()
            .collect(Collectors.toMap(Node::getOsmId, Node::getId));

        // ── Step 4: Build and save edges ──────────────────────────────────────
        log.info("Building {} edge entities...", parsed.edges().size());
        List<Edge> edges = parsed.edges().stream()
            .filter(pe -> osmIdToDbId.containsKey(pe.fromOsmId())
                       && osmIdToDbId.containsKey(pe.toOsmId()))
            .map(pe -> Edge.builder()
                .fromNodeId(osmIdToDbId.get(pe.fromOsmId()))
                .toNodeId(osmIdToDbId.get(pe.toOsmId()))
                .weight(pe.weight())
                .osmWayId(pe.osmWayId())
                .build())
            .toList();

        edgeRepository.saveAll(edges);

        // ── Step 5: Sanity check ──────────────────────────────────────────────
        long finalNodes = nodeRepository.count();
        long finalEdges = edgeRepository.count();
        log.info("=== Import complete ===");
        log.info("  Nodes : {}", finalNodes);
        log.info("  Edges : {}", finalEdges);

        // Edges should outnumber nodes (most roads are bidirectional → 2 edges per segment).
        // If edges < nodes, something may be wrong with the OSM filter or parsing.
        if (finalEdges < finalNodes) {
            log.warn("Edge count ({}) is less than node count ({}) — "
                   + "this is unusual. Check the Overpass response and parsing logic.",
                     finalEdges, finalNodes);
        } else {
            log.info("Sanity check passed (edges > nodes, as expected for a road network).");
        }
    }
}
