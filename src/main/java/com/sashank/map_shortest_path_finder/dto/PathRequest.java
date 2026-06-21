package com.sashank.map_shortest_path_finder.dto;

/**
 * Request body for POST /api/shortest-path.
 *
 * Both coordinates are snapped to the nearest graph node before routing,
 * so the user can click anywhere on the map (not just exact road intersections).
 */
public record PathRequest(LatLng start, LatLng end) {}
