package com.sashank.map_shortest_path_finder.dto;

/**
 * Response for GET /api/region.
 * The frontend uses this to centre the map and know where the supported area is.
 */
public record RegionResponse(String name, LatLng center, BboxDto bbox) {

    public record BboxDto(double south, double north, double west, double east) {}
}
