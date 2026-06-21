package com.sashank.map_shortest_path_finder.dto;

/**
 * A geographic coordinate pair. Used in both request and response bodies.
 * Java records are immutable value objects — no setters, equality is field-based.
 */
public record LatLng(double lat, double lng) {}
