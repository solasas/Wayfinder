package com.sashank.map_shortest_path_finder.model;

/**
 * Coarse road category derived from the OSM `highway` tag. Intent-based routing
 * reasons about these categories ("avoid highways", "prefer main roads") rather
 * than raw OSM tag strings.
 */
public enum RoadClass {
    MOTORWAY, TRUNK, PRIMARY, SECONDARY, TERTIARY, RESIDENTIAL, SERVICE,
    /** Tagged with a `highway` value this system has no category for. */
    OTHER,
    /** No `highway` data at all (edges imported before road attributes were captured). Never treated as verified. */
    UNKNOWN;

    /** Maps an OSM `highway` value (including `_link` ramps) to a category; null → UNKNOWN, unrecognised → OTHER. */
    public static RoadClass fromOsm(String highway) {
        if (highway == null) return UNKNOWN;
        return switch (highway.replace("_link", "")) {
            case "motorway" -> MOTORWAY;
            case "trunk" -> TRUNK;
            case "primary" -> PRIMARY;
            case "secondary" -> SECONDARY;
            case "tertiary" -> TERTIARY;
            case "residential", "living_street", "unclassified" -> RESIDENTIAL;
            case "service" -> SERVICE;
            default -> OTHER;
        };
    }
}
