package com.sashank.map_shortest_path_finder.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DijkstraService.
 *
 * These tests use a hand-crafted in-memory adjacency map and never touch
 * Spring, the database, or any other infrastructure. Because DijkstraService
 * accepts the adjacency map as a parameter, we can verify the algorithm
 * independently of how the graph is stored or loaded.
 *
 * Test graph:
 *
 *   (1) --4-- (2) --3-- (3)
 *    \                  /
 *     --------10-------
 *
 * All edges directed; graph is NOT symmetric (bidirectional edges would be
 * added as two separate entries in a real import).
 */
class DijkstraServiceTest {

    private DijkstraService dijkstra;

    // Shorthand to build a Neighbor
    private static GraphService.Neighbor nb(long to, double w) {
        return new GraphService.Neighbor(to, w);
    }

    @BeforeEach
    void setUp() {
        dijkstra = new DijkstraService();
    }

    // ─── Basic shortest-path cases ────────────────────────────────────────────

    @Test
    void prefersLowerCostIndirectPath() {
        // 1→3 directly costs 10; via 2 it costs 4+3 = 7 → expect 1→2→3
        Map<Long, List<GraphService.Neighbor>> adj = Map.of(
            1L, List.of(nb(2L, 4.0), nb(3L, 10.0)),
            2L, List.of(nb(3L, 3.0)),
            3L, List.of()
        );

        Optional<DijkstraService.PathResult> result =
            dijkstra.findShortestPath(adj, 1L, 3L);

        assertTrue(result.isPresent());
        assertEquals(List.of(1L, 2L, 3L), result.get().nodeIds());
        assertEquals(7.0, result.get().totalDistanceMeters(), 1e-9);
    }

    @Test
    void singleEdgePath() {
        Map<Long, List<GraphService.Neighbor>> adj = Map.of(
            1L, List.of(nb(2L, 5.0)),
            2L, List.of()
        );

        Optional<DijkstraService.PathResult> result =
            dijkstra.findShortestPath(adj, 1L, 2L);

        assertTrue(result.isPresent());
        assertEquals(List.of(1L, 2L), result.get().nodeIds());
        assertEquals(5.0, result.get().totalDistanceMeters(), 1e-9);
    }

    // ─── Edge cases ───────────────────────────────────────────────────────────

    @Test
    void sourceEqualsTargetReturnsZeroDistanceSingleNode() {
        Map<Long, List<GraphService.Neighbor>> adj = Map.of(
            1L, List.of(nb(2L, 5.0)),
            2L, List.of()
        );

        Optional<DijkstraService.PathResult> result =
            dijkstra.findShortestPath(adj, 1L, 1L);

        assertTrue(result.isPresent());
        assertEquals(List.of(1L), result.get().nodeIds());
        assertEquals(0.0, result.get().totalDistanceMeters(), 1e-9);
    }

    @Test
    void returnsEmptyWhenNoPathExists() {
        // 1 and 2 exist but there is no edge connecting them
        Map<Long, List<GraphService.Neighbor>> adj = Map.of(
            1L, List.of(),
            2L, List.of()
        );

        Optional<DijkstraService.PathResult> result =
            dijkstra.findShortestPath(adj, 1L, 2L);

        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyWhenSourceNodeMissing() {
        Map<Long, List<GraphService.Neighbor>> adj = Map.of(
            2L, List.of()
        );

        Optional<DijkstraService.PathResult> result =
            dijkstra.findShortestPath(adj, 99L, 2L);

        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyWhenTargetNodeMissing() {
        Map<Long, List<GraphService.Neighbor>> adj = Map.of(
            1L, List.of()
        );

        Optional<DijkstraService.PathResult> result =
            dijkstra.findShortestPath(adj, 1L, 99L);

        assertTrue(result.isEmpty());
    }

    // ─── Correctness on a larger graph ───────────────────────────────────────

    @Test
    void findsShortestAmongMultiplePaths() {
        // Diamond graph: 1 → {2,3} → 4
        //   1→2: 1,  2→4: 10   total via 2: 11
        //   1→3: 5,  3→4:  2   total via 3: 7   ← shortest
        Map<Long, List<GraphService.Neighbor>> adj = Map.of(
            1L, List.of(nb(2L, 1.0), nb(3L, 5.0)),
            2L, List.of(nb(4L, 10.0)),
            3L, List.of(nb(4L, 2.0)),
            4L, List.of()
        );

        Optional<DijkstraService.PathResult> result =
            dijkstra.findShortestPath(adj, 1L, 4L);

        assertTrue(result.isPresent());
        assertEquals(List.of(1L, 3L, 4L), result.get().nodeIds());
        assertEquals(7.0, result.get().totalDistanceMeters(), 1e-9);
    }
}
