package com.sashank.map_shortest_path_finder.controller;

import com.sashank.map_shortest_path_finder.dto.IsochroneResponse;
import com.sashank.map_shortest_path_finder.dto.LatLng;
import com.sashank.map_shortest_path_finder.dto.ReachableNodeDto;
import com.sashank.map_shortest_path_finder.model.Node;
import com.sashank.map_shortest_path_finder.service.DijkstraService;
import com.sashank.map_shortest_path_finder.service.GraphService;
import com.sashank.map_shortest_path_finder.service.SnapService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * GET /api/isochrone
 *
 * "Everywhere reachable from this point within N minutes" — used to draw a
 * reachability blob on the map, rather than a single route between two points.
 *
 * Full flow:
 *   1. Validate that the graph is loaded (503 if not)
 *   2. Snap lat/lng to the nearest graph node (PostGIS KNN, same as /api/shortest-path)
 *   3. Convert the minutes budget into a cutoff and run DijkstraService's
 *      single-source traversal on the in-memory adjacency map — no further DB queries
 *   4. Convert reached node IDs into lat/lng coordinates
 */
@RestController
@RequestMapping("/api")
public class IsochroneController {

    // The endpoint only accepts a minutes budget, even for optimize=distance, since
    // that's the natural unit for "how far can I get". This nominal speed converts
    // that budget into an equivalent distance cutoff for distance-based isochrones.
    private static final double NOMINAL_SPEED_KMH = 40.0;
    private static final double NOMINAL_SPEED_MS = NOMINAL_SPEED_KMH * 1000.0 / 3600.0;

    @Autowired private GraphService graphService;
    @Autowired private DijkstraService dijkstraService;
    @Autowired private SnapService snapService;

    @GetMapping("/isochrone")
    public IsochroneResponse isochrone(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam double minutes,
            @RequestParam(defaultValue = "time") String optimize) {

        if (graphService.isEmpty()) {
            throw new IllegalStateException(
                "Graph not loaded. Run the import first: "
                + "./mvnw spring-boot:run -Dspring-boot.run.profiles=import");
        }
        if (minutes <= 0) {
            throw new IllegalArgumentException("minutes must be positive");
        }

        DijkstraService.WeightType weightType = DijkstraService.WeightType.fromQueryParam(optimize);
        double cutoff = switch (weightType) {
            case TIME -> minutes * 60.0;
            case DISTANCE -> minutes * 60.0 * NOMINAL_SPEED_MS;
        };

        // SnapService throws IllegalArgumentException for out-of-region points;
        // GlobalExceptionHandler maps that to 400.
        Node center = snapService.snapToNearest(lat, lng);

        Set<DijkstraService.Reachable> reached = dijkstraService.findReachableNodes(
            graphService.getAdjacency(), center.getId(), cutoff, weightType);

        List<ReachableNodeDto> reachableNodes = reached.stream()
            .map(r -> {
                Node n = graphService.getNodeIndex().get(r.nodeId());
                return new ReachableNodeDto(n.getLat(), n.getLng(), r.cost());
            })
            .toList();

        return new IsochroneResponse(new LatLng(center.getLat(), center.getLng()), minutes, reachableNodes);
    }
}