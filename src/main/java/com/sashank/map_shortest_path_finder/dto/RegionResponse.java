package com.sashank.map_shortest_path_finder.dto;

import java.util.List;

/**
 * Response for GET /api/region.
 * name / center / bbox describe the OVERALL supported extent (unchanged shape). For a multi-city region that
 * extent can span hundreds of km, so a client should fit the map to {@code bbox} rather than use a fixed zoom.
 *
 * areas — the detailed sub-regions (e.g. each city), additive: older clients ignore it.
 * maxSnapMeters — how far from a road a point may be before the API refuses it (0 = no limit).
 */
public record RegionResponse(String name, LatLng center, BboxDto bbox, List<AreaDto> areas, double maxSnapMeters) {

    public record BboxDto(double south, double north, double west, double east) {}

    public record AreaDto(String name, LatLng center, BboxDto bbox) {}
}
