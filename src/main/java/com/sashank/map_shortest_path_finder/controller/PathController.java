package com.sashank.map_shortest_path_finder.controller;

import com.sashank.map_shortest_path_finder.dto.LatLng;
import com.sashank.map_shortest_path_finder.dto.PathRequest;
import com.sashank.map_shortest_path_finder.dto.PathResponse;
import com.sashank.map_shortest_path_finder.exception.RouteNotFoundException;
import com.sashank.map_shortest_path_finder.model.Node;
import com.sashank.map_shortest_path_finder.service.AlternateRoutesService;
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
 *   3. Run Dijkstra on the in-memory adjacency map, minimising distance or time
 *      per the request's `optimize` field — or, if `?alternates=true`, run Yen's
 *      k-shortest-paths instead and return up to `k` distinct routes
 *   4. Convert the resulting node-ID path(s) into lat/lng coordinates
 *   5. Return the polyline + distance + travel time along the path — a single
 *      route object normally, or a JSON array of them when alternates=true
 */
@RestController
@RequestMapping("/api")
public class PathController {

    @Autowired private GraphService graphService;
    @Autowired private DijkstraService dijkstraService;
    @Autowired private AlternateRoutesService alternateRoutesService;
    @Autowired private SnapService snapService;

    @PostMapping("/shortest-path")
    public Object shortestPath(
            @RequestBody PathRequest req,
            @RequestParam(defaultValue = "false") boolean alternates,
            @RequestParam(defaultValue = "3") int k) {

        if (graphService.isEmpty()) {
            throw new IllegalStateException(
                "Graph not loaded. Run the import first: "
                + "./mvnw spring-boot:run -Dspring-boot.run.profiles=import");
        }
        if (alternates && k <= 0) {
            throw new IllegalArgumentException("k must be positive");
        }

        // ── Snap clicks to graph nodes ────────────────────────────────────────
        // SnapService throws IllegalArgumentException for out-of-region points;
        // GlobalExceptionHandler maps that to 400.
        Node startNode = snapService.snapToNearest(req.start().lat(), req.start().lng());
        Node endNode   = snapService.snapToNearest(req.end().lat(),   req.end().lng());

        DijkstraService.WeightType weightType = DijkstraService.WeightType.fromQueryParam(req.optimize());

        if (alternates) {
            List<AlternateRoutesService.RouteCandidate> routes = alternateRoutesService.findKShortestPaths(
                graphService.getAdjacency(), startNode.getId(), endNode.getId(), k, weightType);

            if (routes.isEmpty()) {
                throw new RouteNotFoundException(startNode.getId(), endNode.getId());
            }

            return routes.stream()
                .map(r -> toResponse(r.nodeIds(), r.totalDistanceMeters(), r.totalTimeSeconds()))
                .toList();
        }

        // ── Run Dijkstra ──────────────────────────────────────────────────────
        DijkstraService.PathResult result =
            dijkstraService.findShortestPath(
                graphService.getAdjacency(),
                startNode.getId(),
                endNode.getId(),
                weightType
            ).orElseThrow(() -> new RouteNotFoundException(startNode.getId(), endNode.getId()));

        return toResponse(result.nodeIds(), result.totalDistanceMeters(), result.totalTimeSeconds());
    }

    // Convert node IDs → lat/lng coordinates for the frontend polyline
    private PathResponse toResponse(List<Long> nodeIds, double totalDistanceMeters, double totalTimeSeconds) {
        List<LatLng> polyline = nodeIds.stream()
            .map(id -> {
                Node n = graphService.getNodeIndex().get(id);
                return new LatLng(n.getLat(), n.getLng());
            })
            .toList();

        return new PathResponse(polyline, totalDistanceMeters, Math.round(totalTimeSeconds));
    }
}
