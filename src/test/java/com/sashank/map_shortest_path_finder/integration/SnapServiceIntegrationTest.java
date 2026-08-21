package com.sashank.map_shortest_path_finder.integration;

import com.sashank.map_shortest_path_finder.model.Node;
import com.sashank.map_shortest_path_finder.repository.NodeRepository;
import com.sashank.map_shortest_path_finder.service.SnapService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration test for SnapService against a real PostgreSQL database (via
 * Testcontainers, see {@link AbstractIntegrationTest}) — exercises the real
 * NodeRepository.findNearestTo() query and RegionConfig bbox check, with no
 * mocking, unlike the pure-unit SnapServiceTest.
 *
 * NOTE: findNearestTo() currently ranks candidates by a plain Euclidean lat/lng
 * distance approximation, not PostGIS's `<->` KNN operator over the `geom` column
 * that init.sql's trigger populates (despite CLAUDE.md describing the latter —
 * worth reconciling separately). These tests validate the observable behavior
 * (correct nearest node), which holds regardless of which query technique is
 * used; only the O(log n) vs O(n) performance characteristic differs.
 *
 * Region under test: Rajahmundry, bbox south=16.96 north=17.01 west=81.76 east=81.82
 * (see application.properties region.bbox.*).
 */
class SnapServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired private NodeRepository nodeRepository;
    @Autowired private SnapService snapService;

    @AfterEach
    void cleanUp() {
        nodeRepository.deleteAll();
    }

    private Node saveNode(long osmId, double lat, double lng) {
        return nodeRepository.save(Node.builder().osmId(osmId).lat(lat).lng(lng).build());
    }

    @Test
    void findsNearestNodeAmongSeveral() {
        Node near = saveNode(1, 16.975, 81.778);
        saveNode(2, 16.990, 81.800);
        saveNode(3, 16.960, 81.760);

        Node result = snapService.snapToNearest(16.9751, 81.7781);

        assertEquals(near.getId(), result.getId());
    }

    @Test
    void picksTheCloserOfTwoNearbyNodes() {
        // Two nodes ~220m apart; the click sits much closer to `a`.
        Node a = saveNode(1, 16.980, 81.780);
        Node b = saveNode(2, 16.982, 81.780);

        Node result = snapService.snapToNearest(16.9805, 81.780);

        assertEquals(a.getId(), result.getId());
    }

    @Test
    void findsNearestNodeJustInsideSouthwestCorner() {
        // bbox corner: south=16.96, west=81.76
        Node corner = saveNode(1, 16.9605, 81.7605);
        saveNode(2, 16.99, 81.79); // far from the click, must lose

        Node result = snapService.snapToNearest(16.961, 81.761);

        assertEquals(corner.getId(), result.getId());
    }

    @Test
    void findsNearestNodeJustInsideNortheastCorner() {
        // bbox corner: north=17.01, east=81.82
        Node corner = saveNode(1, 17.0095, 81.8195);
        saveNode(2, 16.97, 81.77); // far from the click, must lose

        Node result = snapService.snapToNearest(17.009, 81.819);

        assertEquals(corner.getId(), result.getId());
    }

    @Test
    void acceptsAClickExactlyOnTheBoundaryEdge() {
        // south=16.96 is inclusive per RegionConfig.Bbox#contains (lat >= south).
        Node onBoundary = saveNode(1, 16.96, 81.79);

        Node result = snapService.snapToNearest(16.96, 81.79);

        assertEquals(onBoundary.getId(), result.getId());
    }

    @Test
    void throwsIllegalArgumentForOutOfRegionCoordinates() {
        saveNode(1, 16.975, 81.778);

        // Chennai — nowhere near Rajahmundry's bbox
        assertThrows(IllegalArgumentException.class,
            () -> snapService.snapToNearest(13.08, 80.27));
    }

    @Test
    void throwsIllegalStateWhenNoNodesExist() {
        assertThrows(IllegalStateException.class,
            () -> snapService.snapToNearest(16.98, 81.78));
    }
}
