package com.sashank.map_shortest_path_finder.dto;

import java.util.List;

/**
 * Response for POST /api/shortest-path.
 *
 * path              — ordered lat/lng points forming the route; draw these as a polyline.
 * distanceMeters    — total Haversine distance along the path.
 * estimatedTimeSecs — rough estimate using 30 km/h average city speed.
 */
public record PathResponse(
    List<LatLng> path,
    double distanceMeters,
    long estimatedTimeSecs
) {}
