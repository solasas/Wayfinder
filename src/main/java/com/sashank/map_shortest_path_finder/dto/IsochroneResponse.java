package com.sashank.map_shortest_path_finder.dto;

import java.util.List;

/**
 * Response for GET /api/isochrone.
 *
 * center         — the graph node the query snapped to (not necessarily the requested lat/lng).
 * minutes        — the travel budget that was requested.
 * reachableNodes — every graph node reachable within that budget.
 */
public record IsochroneResponse(
    LatLng center,
    double minutes,
    List<ReachableNodeDto> reachableNodes
) {}