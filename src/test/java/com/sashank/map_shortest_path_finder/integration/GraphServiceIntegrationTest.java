package com.sashank.map_shortest_path_finder.integration;

import com.sashank.map_shortest_path_finder.model.Edge;
import com.sashank.map_shortest_path_finder.model.Node;
import com.sashank.map_shortest_path_finder.repository.EdgeRepository;
import com.sashank.map_shortest_path_finder.repository.NodeRepository;
import com.sashank.map_shortest_path_finder.service.GraphService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for GraphService against a real PostgreSQL database (via
 * Testcontainers, see {@link AbstractIntegrationTest}) — verifies the in-memory
 * adjacency map and nodeIndex built at load time match exactly what's in the DB.
 *
 * Timing note: GraphService.loadGraph() runs at @PostConstruct, i.e. once when
 * the Spring context is first created — before any test method has had a chance
 * to insert data. So instead of relying on that first (empty-graph) load, each
 * test seeds its known graph via the repositories and then calls
 * graphService.loadGraph() again directly. That's the exact same method
 * @PostConstruct invokes, just triggered at a time we control.
 */
class GraphServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired private NodeRepository nodeRepository;
    @Autowired private EdgeRepository edgeRepository;
    @Autowired private GraphService graphService;

    @AfterEach
    void cleanUp() {
        edgeRepository.deleteAll(); // edges reference nodes via FK, must go first
        nodeRepository.deleteAll();
    }

    private Node saveNode(long osmId, double lat, double lng) {
        return nodeRepository.save(Node.builder().osmId(osmId).lat(lat).lng(lng).build());
    }

    private void saveEdge(long fromId, long toId, double distanceMeters, double speedKmh) {
        edgeRepository.save(Edge.builder()
            .fromNodeId(fromId)
            .toNodeId(toId)
            .distanceMeters(distanceMeters)
            .speedKmh(speedKmh)
            .build());
    }

    private GraphService.Neighbor findNeighbor(List<GraphService.Neighbor> neighbors, long toNodeId) {
        return neighbors.stream()
            .filter(n -> n.toNodeId() == toNodeId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("No neighbor to node " + toNodeId + " in " + neighbors));
    }

    @Test
    void loadsNodeIndexMatchingTheDatabase() {
        Node a = saveNode(1, 16.975, 81.778);
        Node b = saveNode(2, 16.980, 81.780);
        Node c = saveNode(3, 16.985, 81.785);

        graphService.loadGraph();

        Map<Long, Node> nodeIndex = graphService.getNodeIndex();
        assertEquals(3, nodeIndex.size());

        for (Node expected : List.of(a, b, c)) {
            Node dbRow = nodeRepository.findById(expected.getId()).orElseThrow();
            Node inMemory = nodeIndex.get(expected.getId());
            assertNotNull(inMemory, "node " + expected.getId() + " missing from nodeIndex");
            assertEquals(dbRow.getOsmId(), inMemory.getOsmId());
            assertEquals(dbRow.getLat(), inMemory.getLat(), 1e-9);
            assertEquals(dbRow.getLng(), inMemory.getLng(), 1e-9);
        }
    }

    @Test
    void loadsAdjacencyMatchingTheDatabase() {
        // A --100m/30km/h--> B --200m/50km/h--> C, plus B --100m/30km/h--> A
        // (the two-edges-per-bidirectional-road pattern GraphImporter produces).
        Node a = saveNode(1, 16.975, 81.778);
        Node b = saveNode(2, 16.980, 81.780);
        Node c = saveNode(3, 16.985, 81.785);

        saveEdge(a.getId(), b.getId(), 100.0, 30.0);
        saveEdge(b.getId(), a.getId(), 100.0, 30.0);
        saveEdge(b.getId(), c.getId(), 200.0, 50.0);

        graphService.loadGraph();

        Map<Long, List<GraphService.Neighbor>> adjacency = graphService.getAdjacency();

        // Every node gets an entry, even ones with no outgoing edges (C here).
        assertEquals(Set.of(a.getId(), b.getId(), c.getId()), adjacency.keySet());
        assertEquals(1, adjacency.get(a.getId()).size());
        assertEquals(2, adjacency.get(b.getId()).size());
        assertEquals(0, adjacency.get(c.getId()).size());

        GraphService.Neighbor aToB = findNeighbor(adjacency.get(a.getId()), b.getId());
        assertEquals(100.0, aToB.distanceMeters(), 1e-9);
        assertEquals(100.0 / (30.0 * 1000.0 / 3600.0), aToB.timeSeconds(), 1e-9);

        GraphService.Neighbor bToA = findNeighbor(adjacency.get(b.getId()), a.getId());
        assertEquals(100.0, bToA.distanceMeters(), 1e-9);

        GraphService.Neighbor bToC = findNeighbor(adjacency.get(b.getId()), c.getId());
        assertEquals(200.0, bToC.distanceMeters(), 1e-9);
        assertEquals(200.0 / (50.0 * 1000.0 / 3600.0), bToC.timeSeconds(), 1e-9);
    }

    @Test
    void isEmptyGoesFalseOnceDataIsLoaded() {
        saveNode(1, 16.975, 81.778);
        graphService.loadGraph();

        assertFalse(graphService.isEmpty());
    }

    @Test
    void loadGraphKeepsServingTheLastGoodGraphIfTheDatabaseGoesEmpty() {
        // GraphService.loadGraph() deliberately does NOT clear nodeIndex/adjacency
        // when it finds zero nodes — it logs a warning and returns, so a transient
        // empty read (or premature call before import) can't wipe out a graph
        // that was already successfully serving routes. This asserts that on
        // purpose, since GraphService is a singleton whose state would otherwise
        // silently depend on test execution order.
        saveNode(1, 16.975, 81.778);
        graphService.loadGraph();
        assertFalse(graphService.isEmpty());

        nodeRepository.deleteAll(); // DB is now empty, but the loaded graph shouldn't be
        graphService.loadGraph();

        assertFalse(graphService.isEmpty());
        assertEquals(1, graphService.getNodeIndex().size());
    }
}
