package com.sashank.map_shortest_path_finder.dto;

/**
 * Request body for POST /api/shortest-path.
 *
 * Both coordinates are snapped to the nearest graph node before routing,
 * so the user can click anywhere on the map (not just exact road intersections).
 *
 * optimize — "distance" or "time" (case-insensitive); defaults to "time" when
 * omitted or blank, since that's what most map users expect.
 */
public record PathRequest(LatLng start, LatLng end, String optimize) {
    public PathRequest {
        if (optimize == null || optimize.isBlank()) {
            optimize = "time";
        }
    }
}
