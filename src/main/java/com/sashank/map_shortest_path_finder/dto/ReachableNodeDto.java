package com.sashank.map_shortest_path_finder.dto;

/**
 * One node reachable within an isochrone's travel budget.
 *
 * distance — cumulative cost to reach this node, in the units of the request's
 * `optimize` parameter: metres for "distance", seconds for "time".
 */
public record ReachableNodeDto(double lat, double lng, double distance) {}