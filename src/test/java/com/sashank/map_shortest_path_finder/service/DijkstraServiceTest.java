package com.sashank.map_shortest_path_finder.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    // Shorthand to build a Neighbor: distanceMeters == weight for most tests,
    // with a distinct timeSeconds so distance- vs time-optimised runs can diverge.
    private static GraphService.Neighbor nb(long to, double distanceMeters, double timeSeconds) {
        return new GraphService.Neighbor(to, distanceMeters, timeSeconds);
    }

    private static GraphService.Neighbor nb(long to, double w) {
        return nb(to, w, w);
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
            dijkstra.findShortestPath(adj, 1L, 3L, DijkstraService.WeightType.DISTANCE);

        assertTrue(result.isPresent());
        assertEquals(List.of(1L, 2L, 3L), result.get().nodeIds());
        assertEquals(7.0, result.get().totalDistanceMeters(), 1e-9);
        assertEquals(7.0, result.get().totalTimeSeconds(), 1e-9);
    }

    @Test
    void singleEdgePath() {
        Map<Long, List<GraphService.Neighbor>> adj = Map.of(
            1L, List.of(nb(2L, 5.0)),
            2L, List.of()
        );

        Optional<DijkstraService.PathResult> result =
            dijkstra.findShortestPath(adj, 1L, 2L, DijkstraService.WeightType.DISTANCE);

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
            dijkstra.findShortestPath(adj, 1L, 1L, DijkstraService.WeightType.DISTANCE);

        assertTrue(result.isPresent());
        assertEquals(List.of(1L), result.get().nodeIds());
        assertEquals(0.0, result.get().totalDistanceMeters(), 1e-9);
        assertEquals(0.0, result.get().totalTimeSeconds(), 1e-9);
    }

    @Test
    void returnsEmptyWhenNoPathExists() {
        // 1 and 2 exist but there is no edge connecting them
        Map<Long, List<GraphService.Neighbor>> adj = Map.of(
            1L, List.of(),
            2L, List.of()
        );

        Optional<DijkstraService.PathResult> result =
            dijkstra.findShortestPath(adj, 1L, 2L, DijkstraService.WeightType.DISTANCE);

        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyWhenSourceNodeMissing() {
        Map<Long, List<GraphService.Neighbor>> adj = Map.of(
            2L, List.of()
        );

        Optional<DijkstraService.PathResult> result =
            dijkstra.findShortestPath(adj, 99L, 2L, DijkstraService.WeightType.DISTANCE);

        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyWhenTargetNodeMissing() {
        Map<Long, List<GraphService.Neighbor>> adj = Map.of(
            1L, List.of()
        );

        Optional<DijkstraService.PathResult> result =
            dijkstra.findShortestPath(adj, 1L, 99L, DijkstraService.WeightType.DISTANCE);

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
            dijkstra.findShortestPath(adj, 1L, 4L, DijkstraService.WeightType.DISTANCE);

        assertTrue(result.isPresent());
        assertEquals(List.of(1L, 3L, 4L), result.get().nodeIds());
        assertEquals(7.0, result.get().totalDistanceMeters(), 1e-9);
    }

    // ─── Weight selection (distance vs time) ──────────────────────────────────

    @Test
    void distanceAndTimeOptimizationCanChooseDifferentPaths() {
        // Diamond graph where the shortest path by distance differs from the
        // fastest path by time:
        //   1→2: 1m / 100s,  2→4: 1m / 100s   distance via 2: 2m,   time via 2: 200s
        //   1→3: 5m / 1s,    3→4: 5m / 1s     distance via 3: 10m,  time via 3: 2s
        Map<Long, List<GraphService.Neighbor>> adj = Map.of(
            1L, List.of(nb(2L, 1.0, 100.0), nb(3L, 5.0, 1.0)),
            2L, List.of(nb(4L, 1.0, 100.0)),
            3L, List.of(nb(4L, 5.0, 1.0)),
            4L, List.of()
        );

        Optional<DijkstraService.PathResult> byDistance =
            dijkstra.findShortestPath(adj, 1L, 4L, DijkstraService.WeightType.DISTANCE);
        assertTrue(byDistance.isPresent());
        assertEquals(List.of(1L, 2L, 4L), byDistance.get().nodeIds());
        assertEquals(2.0, byDistance.get().totalDistanceMeters(), 1e-9);
        assertEquals(200.0, byDistance.get().totalTimeSeconds(), 1e-9);

        Optional<DijkstraService.PathResult> byTime =
            dijkstra.findShortestPath(adj, 1L, 4L, DijkstraService.WeightType.TIME);
        assertTrue(byTime.isPresent());
        assertEquals(List.of(1L, 3L, 4L), byTime.get().nodeIds());
        assertEquals(10.0, byTime.get().totalDistanceMeters(), 1e-9);
        assertEquals(2.0, byTime.get().totalTimeSeconds(), 1e-9);
    }

    // ─── Reachability with a cutoff (isochrone traversal) ────────────────────

    @Test
    void findsAllNodesWithinCutoff() {
        // 1→2: 4, 2→3: 3, 1→3: 10 (direct)
        Map<Long, List<GraphService.Neighbor>> adj = Map.of(
            1L, List.of(nb(2L, 4.0), nb(3L, 10.0)),
            2L, List.of(nb(3L, 3.0)),
            3L, List.of()
        );

        // Cutoff 5: node 2 is reachable (cost 4); node 3 would need 7 via 2 or 10 direct.
        Set<DijkstraService.Reachable> reached =
            dijkstra.findReachableNodes(adj, 1L, 5.0, DijkstraService.WeightType.DISTANCE);

        assertEquals(
            Set.of(new DijkstraService.Reachable(1L, 0.0),
                   new DijkstraService.Reachable(2L, 4.0)),
            reached);
    }

    @Test
    void raisingCutoffRevealsFartherNodes() {
        Map<Long, List<GraphService.Neighbor>> adj = Map.of(
            1L, List.of(nb(2L, 4.0), nb(3L, 10.0)),
            2L, List.of(nb(3L, 3.0)),
            3L, List.of()
        );

        // Cutoff 10: node 3 is now reachable via 2 (cost 7), cheaper than the direct edge (10).
        Set<DijkstraService.Reachable> reached =
            dijkstra.findReachableNodes(adj, 1L, 10.0, DijkstraService.WeightType.DISTANCE);

        assertEquals(
            Set.of(new DijkstraService.Reachable(1L, 0.0),
                   new DijkstraService.Reachable(2L, 4.0),
                   new DijkstraService.Reachable(3L, 7.0)),
            reached);
    }

    @Test
    void sourceAlwaysIncludedEvenWithZeroCutoff() {
        Map<Long, List<GraphService.Neighbor>> adj = Map.of(
            1L, List.of(nb(2L, 4.0)),
            2L, List.of()
        );

        Set<DijkstraService.Reachable> reached =
            dijkstra.findReachableNodes(adj, 1L, 0.0, DijkstraService.WeightType.DISTANCE);

        assertEquals(Set.of(new DijkstraService.Reachable(1L, 0.0)), reached);
    }

    @Test
    void returnsEmptySetWhenSourceNodeMissing() {
        Map<Long, List<GraphService.Neighbor>> adj = Map.of(
            2L, List.of()
        );

        Set<DijkstraService.Reachable> reached =
            dijkstra.findReachableNodes(adj, 99L, 100.0, DijkstraService.WeightType.DISTANCE);

        assertTrue(reached.isEmpty());
    }

    @Test
    void distanceAndTimeCutoffsReachDifferentSets() {
        // 1→2: 1m / 100s,  1→3: 5m / 1s
        Map<Long, List<GraphService.Neighbor>> adj = Map.of(
            1L, List.of(nb(2L, 1.0, 100.0), nb(3L, 5.0, 1.0)),
            2L, List.of(),
            3L, List.of()
        );

        // Distance cutoff 5: both 2 (1m) and 3 (5m) are within budget.
        Set<DijkstraService.Reachable> byDistance =
            dijkstra.findReachableNodes(adj, 1L, 5.0, DijkstraService.WeightType.DISTANCE);
        assertEquals(3, byDistance.size());

        // Time cutoff 5: only node 3 (1s) is within budget; node 2 costs 100s.
        Set<DijkstraService.Reachable> byTime =
            dijkstra.findReachableNodes(adj, 1L, 5.0, DijkstraService.WeightType.TIME);
        assertEquals(
            Set.of(new DijkstraService.Reachable(1L, 0.0),
                   new DijkstraService.Reachable(3L, 1.0)),
            byTime);
    }

    // ─── Bidirectional Dijkstra: reverse-adjacency helper ─────────────────────

    @Test
    void buildReverseAdjacencyFlipsEveryEdge() {
        // Plain one-way chain: 1→2 (5), 2→3 (2). No edges back.
        Map<Long, List<GraphService.Neighbor>> adj = Map.of(
            1L, List.of(nb(2L, 5.0)),
            2L, List.of(nb(3L, 2.0)),
            3L, List.of()
        );

        Map<Long, List<GraphService.Neighbor>> reversed = DijkstraService.buildReverseAdjacency(adj);

        assertEquals(Set.of(1L, 2L, 3L), reversed.keySet());
        assertEquals(List.of(), reversed.get(1L));
        assertEquals(List.of(nb(1L, 5.0)), reversed.get(2L));
        assertEquals(List.of(nb(2L, 2.0)), reversed.get(3L));
    }

    // ─── Bidirectional Dijkstra: matches findShortestPath exactly ─────────────

    @Test
    void bidirectionalMatchesSingleDirectionOnIndirectPathGraph() {
        // Same graph as prefersLowerCostIndirectPath: 1→3 direct costs 10;
        // via 2 it costs 4+3 = 7 → both algorithms must pick 1→2→3.
        Map<Long, List<GraphService.Neighbor>> adj = Map.of(
            1L, List.of(nb(2L, 4.0), nb(3L, 10.0)),
            2L, List.of(nb(3L, 3.0)),
            3L, List.of()
        );

        Optional<DijkstraService.PathResult> single =
            dijkstra.findShortestPath(adj, 1L, 3L, DijkstraService.WeightType.DISTANCE);
        Optional<DijkstraService.PathResult> bidirectional =
            dijkstra.findShortestPathBidirectional(adj, 1L, 3L, DijkstraService.WeightType.DISTANCE);

        assertTrue(single.isPresent());
        assertTrue(bidirectional.isPresent());
        assertEquals(single.get().nodeIds(), bidirectional.get().nodeIds());
        assertEquals(single.get().totalDistanceMeters(), bidirectional.get().totalDistanceMeters(), 1e-9);
        assertEquals(single.get().totalTimeSeconds(), bidirectional.get().totalTimeSeconds(), 1e-9);

        // Hand-traced for this specific graph: both settle exactly {1, 2, 3-or-meeting-node}.
        assertEquals(3, single.get().nodesExpanded());
        assertEquals(3, bidirectional.get().nodesExpanded());
    }

    @Test
    void bidirectionalMatchesSingleDirectionOnDiamondGraph() {
        // Same graph as findsShortestAmongMultiplePaths: 1 → {2,3} → 4,
        // shortest is 1→3→4 (cost 7) over 1→2→4 (cost 11).
        Map<Long, List<GraphService.Neighbor>> adj = Map.of(
            1L, List.of(nb(2L, 1.0), nb(3L, 5.0)),
            2L, List.of(nb(4L, 10.0)),
            3L, List.of(nb(4L, 2.0)),
            4L, List.of()
        );

        Optional<DijkstraService.PathResult> single =
            dijkstra.findShortestPath(adj, 1L, 4L, DijkstraService.WeightType.DISTANCE);
        Optional<DijkstraService.PathResult> bidirectional =
            dijkstra.findShortestPathBidirectional(adj, 1L, 4L, DijkstraService.WeightType.DISTANCE);

        assertTrue(single.isPresent());
        assertTrue(bidirectional.isPresent());
        assertEquals(List.of(1L, 3L, 4L), bidirectional.get().nodeIds());
        assertEquals(single.get().nodeIds(), bidirectional.get().nodeIds());
        assertEquals(single.get().totalDistanceMeters(), bidirectional.get().totalDistanceMeters(), 1e-9);
        assertEquals(single.get().totalTimeSeconds(), bidirectional.get().totalTimeSeconds(), 1e-9);
        assertTrue(bidirectional.get().nodesExpanded() > 0);
    }

    @Test
    void bidirectionalMatchesSingleDirectionWhenOptimizingForTime() {
        // Same diverging distance/time graph as distanceAndTimeOptimizationCanChooseDifferentPaths.
        Map<Long, List<GraphService.Neighbor>> adj = Map.of(
            1L, List.of(nb(2L, 1.0, 100.0), nb(3L, 5.0, 1.0)),
            2L, List.of(nb(4L, 1.0, 100.0)),
            3L, List.of(nb(4L, 5.0, 1.0)),
            4L, List.of()
        );

        Optional<DijkstraService.PathResult> single =
            dijkstra.findShortestPath(adj, 1L, 4L, DijkstraService.WeightType.TIME);
        Optional<DijkstraService.PathResult> bidirectional =
            dijkstra.findShortestPathBidirectional(adj, 1L, 4L, DijkstraService.WeightType.TIME);

        assertTrue(single.isPresent());
        assertTrue(bidirectional.isPresent());
        assertEquals(List.of(1L, 3L, 4L), bidirectional.get().nodeIds());
        assertEquals(single.get().nodeIds(), bidirectional.get().nodeIds());
        assertEquals(single.get().totalDistanceMeters(), bidirectional.get().totalDistanceMeters(), 1e-9);
        assertEquals(single.get().totalTimeSeconds(), bidirectional.get().totalTimeSeconds(), 1e-9);
    }

    @Test
    void bidirectionalSourceEqualsTargetReturnsZeroDistanceSingleNode() {
        Map<Long, List<GraphService.Neighbor>> adj = Map.of(
            1L, List.of(nb(2L, 5.0)),
            2L, List.of()
        );

        Optional<DijkstraService.PathResult> result =
            dijkstra.findShortestPathBidirectional(adj, 1L, 1L, DijkstraService.WeightType.DISTANCE);

        assertTrue(result.isPresent());
        assertEquals(List.of(1L), result.get().nodeIds());
        assertEquals(0.0, result.get().totalDistanceMeters(), 1e-9);
        assertEquals(0.0, result.get().totalTimeSeconds(), 1e-9);
        assertEquals(0, result.get().nodesExpanded());
    }

    @Test
    void bidirectionalReturnsEmptyWhenNoPathExists() {
        Map<Long, List<GraphService.Neighbor>> adj = Map.of(
            1L, List.of(),
            2L, List.of()
        );

        Optional<DijkstraService.PathResult> result =
            dijkstra.findShortestPathBidirectional(adj, 1L, 2L, DijkstraService.WeightType.DISTANCE);

        assertTrue(result.isEmpty());
    }

    @Test
    void bidirectionalReturnsEmptyWhenSourceOrTargetNodeMissing() {
        Map<Long, List<GraphService.Neighbor>> adj = Map.of(
            1L, List.of()
        );

        assertTrue(dijkstra.findShortestPathBidirectional(adj, 99L, 1L, DijkstraService.WeightType.DISTANCE).isEmpty());
        assertTrue(dijkstra.findShortestPathBidirectional(adj, 1L, 99L, DijkstraService.WeightType.DISTANCE).isEmpty());
    }
}