package com.sashank.map_shortest_path_finder.controller;

import com.sashank.map_shortest_path_finder.dto.LatLng;
import com.sashank.map_shortest_path_finder.dto.PathRequest;
import com.sashank.map_shortest_path_finder.dto.PathResponse;
import com.sashank.map_shortest_path_finder.exception.RouteNotFoundException;
import com.sashank.map_shortest_path_finder.model.Node;
import com.sashank.map_shortest_path_finder.service.DijkstraService;
import com.sashank.map_shortest_path_finder.service.GraphService;
import com.sashank.map_shortest_path_finder.service.SnapService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * POST /api/shortest-path
 *
 * Full flow:
 *   1. Validate that the graph is loaded (503 if not)
 *   2. Snap start and end lat/lng to the nearest graph nodes (PostGIS KNN)
 *   3. Run Dijkstra on the in-memory adjacency map
 *   4. Convert the resulting node-ID path into lat/lng coordinates
 *   5. Return the polyline + distance + estimated travel time
 */
@RestController
@RequestMapping("/api")
public class PathController {

    // 30 km/h average city speed → 8.33 m/s
    private static final double CITY_SPEED_MS = 30_000.0 / 3600.0;

    @Autowired private GraphService graphService;
    @Autowired private DijkstraService dijkstraService;
    @Autowired private SnapService snapService;

    @PostMapping("/shortest-path")
    public PathResponse shortestPath(@RequestBody PathRequest req) {
        if (graphService.isEmpty()) {
            throw new IllegalStateException(
                "Graph not loaded. Run the import first: "
                + "./mvnw spring-boot:run -Dspring-boot.run.profiles=import");
        }

        // ── Snap clicks to graph nodes ────────────────────────────────────────
        // SnapService throws IllegalArgumentException for out-of-region points;
        // GlobalExceptionHandler maps that to 400.
        Node startNode = snapService.snapToNearest(req.start().lat(), req.start().lng());
        Node endNode   = snapService.snapToNearest(req.end().lat(),   req.end().lng());

        // ── Run Dijkstra ──────────────────────────────────────────────────────
        DijkstraService.PathResult result =
            dijkstraService.findShortestPath(
                graphService.getAdjacency(),
                startNode.getId(),
                endNode.getId()
            ).orElseThrow(() -> new RouteNotFoundException(startNode.getId(), endNode.getId()));

        // ── Build response ────────────────────────────────────────────────────
        // Convert node IDs → lat/lng coordinates for the frontend polyline
        List<LatLng> polyline = result.nodeIds().stream()
            .map(id -> {
                Node n = graphService.getNodeIndex().get(id);
                return new LatLng(n.getLat(), n.getLng());
            })
            .toList();

        long estimatedTimeSecs = Math.round(result.totalDistanceMeters() / CITY_SPEED_MS);

        return new PathResponse(polyline, result.totalDistanceMeters(), estimatedTimeSecs);
    }
}
