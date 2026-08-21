package com.sashank.map_shortest_path_finder.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AlternateRoutesService (Yen's k-shortest-paths).
 *
 * Like DijkstraServiceTest, these use hand-crafted adjacency maps and never
 * touch Spring, the database, or any other infrastructure — AlternateRoutesService
 * only depends on a plain DijkstraService instance, so it's constructed directly.
 *
 * Test graph:
 *
 *   (1) --1--> (2) --1--> (3) --1--> (6)     route A: 1-2-3-6, cost 3 (best)
 *               \
 *                --2--> (4) --1--> (6)        route B: 1-2-4-6, cost 4
 *                                              (shares the "1-2" prefix with A —
 *                                               exercises Yen's shared-root edge removal)
 *   (1) --10--> (5) --10--> (6)               route C: 1-5-6, cost 20 (fully disjoint,
 *                                               much worse — exists only to prove k=3
 *                                               finds it last and k>3 doesn't invent more)
 *
 * All edges directed; distanceMeters == timeSeconds throughout (uniform weight
 * shorthand), so WeightType.DISTANCE is used and the choice is otherwise inert —
 * distance-vs-time selection is already covered by DijkstraServiceTest.
 */
class AlternateRoutesServiceTest {

    private AlternateRoutesService alternateRoutes;

    private static GraphService.Neighbor nb(long to, double w) {
        return new GraphService.Neighbor(to, w, w);
    }

    private Map<Long, List<GraphService.Neighbor>> threeRouteGraph() {
        return Map.of(
            1L, List.of(nb(2L, 1.0), nb(5L, 10.0)),
            2L, List.of(nb(3L, 1.0), nb(4L, 2.0)),
            3L, List.of(nb(6L, 1.0)),
            4L, List.of(nb(6L, 1.0)),
            5L, List.of(nb(6L, 10.0)),
            6L, List.of()
        );
    }

    @BeforeEach
    void setUp() {
        alternateRoutes = new AlternateRoutesService(new DijkstraService());
    }

    @Test
    void kEqualsOneReturnsJustTheShortestPath() {
        List<AlternateRoutesService.RouteCandidate> routes =
            alternateRoutes.findKShortestPaths(threeRouteGraph(), 1L, 6L, 1, DijkstraService.WeightType.DISTANCE);

        assertEquals(1, routes.size());
        assertEquals(List.of(1L, 2L, 3L, 6L), routes.get(0).nodeIds());
        assertEquals(3.0, routes.get(0).totalDistanceMeters(), 1e-9);
    }

    @Test
    void findsTwoGenuinelyDistinctRoutesInIncreasingCostOrder() {
        List<AlternateRoutesService.RouteCandidate> routes =
            alternateRoutes.findKShortestPaths(threeRouteGraph(), 1L, 6L, 2, DijkstraService.WeightType.DISTANCE);

        assertEquals(2, routes.size());

        assertEquals(List.of(1L, 2L, 3L, 6L), routes.get(0).nodeIds());
        assertEquals(3.0, routes.get(0).totalDistanceMeters(), 1e-9);

        assertEquals(List.of(1L, 2L, 4L, 6L), routes.get(1).nodeIds());
        assertEquals(4.0, routes.get(1).totalDistanceMeters(), 1e-9);

        // Genuinely distinct: they only share the source, diverging at node 2.
        assertNotEquals(routes.get(0).nodeIds(), routes.get(1).nodeIds());
    }

    @Test
    void findsAllThreeRoutesIncludingTheFullyDisjointOneLast() {
        List<AlternateRoutesService.RouteCandidate> routes =
            alternateRoutes.findKShortestPaths(threeRouteGraph(), 1L, 6L, 3, DijkstraService.WeightType.DISTANCE);

        assertEquals(3, routes.size());
        assertEquals(List.of(1L, 2L, 3L, 6L), routes.get(0).nodeIds());
        assertEquals(List.of(1L, 2L, 4L, 6L), routes.get(1).nodeIds());
        assertEquals(List.of(1L, 5L, 6L), routes.get(2).nodeIds());

        assertEquals(3.0, routes.get(0).totalDistanceMeters(), 1e-9);
        assertEquals(4.0, routes.get(1).totalDistanceMeters(), 1e-9);
        assertEquals(20.0, routes.get(2).totalDistanceMeters(), 1e-9);

        // Strictly increasing cost, round by round.
        for (int i = 1; i < routes.size(); i++) {
            assertTrue(routes.get(i).totalDistanceMeters() > routes.get(i - 1).totalDistanceMeters());
        }
    }

    @Test
    void returnsAllPathsDistinctNoDuplicates() {
        List<AlternateRoutesService.RouteCandidate> routes =
            alternateRoutes.findKShortestPaths(threeRouteGraph(), 1L, 6L, 3, DijkstraService.WeightType.DISTANCE);

        Set<List<Long>> distinctPaths = new HashSet<>();
        for (AlternateRoutesService.RouteCandidate route : routes) {
            assertTrue(distinctPaths.add(route.nodeIds()), "duplicate path returned: " + route.nodeIds());
        }
    }

    @Test
    void stopsEarlyWhenFewerThanKDistinctPathsExist() {
        // Only 3 distinct 1->6 routes exist in this graph at all.
        List<AlternateRoutesService.RouteCandidate> routes =
            alternateRoutes.findKShortestPaths(threeRouteGraph(), 1L, 6L, 5, DijkstraService.WeightType.DISTANCE);

        assertEquals(3, routes.size());
    }

    @Test
    void returnsEmptyListWhenNoPathExists() {
        Map<Long, List<GraphService.Neighbor>> disconnected = Map.of(
            1L, List.of(),
            2L, List.of()
        );

        List<AlternateRoutesService.RouteCandidate> routes =
            alternateRoutes.findKShortestPaths(disconnected, 1L, 2L, 3, DijkstraService.WeightType.DISTANCE);

        assertTrue(routes.isEmpty());
    }

    @Test
    void returnsEmptyListForNonPositiveK() {
        List<AlternateRoutesService.RouteCandidate> routes =
            alternateRoutes.findKShortestPaths(threeRouteGraph(), 1L, 6L, 0, DijkstraService.WeightType.DISTANCE);

        assertTrue(routes.isEmpty());
    }
}