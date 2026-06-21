package com.sashank.map_shortest_path_finder.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Holds the bounding box for the supported road network region.
 * Values come from application.properties (region.*).
 * To expand to a new region: update the properties and re-run the data import.
 */
@Component
@ConfigurationProperties(prefix = "region")
@Data
public class RegionConfig {

    private String name;
    private Bbox bbox = new Bbox();

    @Data
    public static class Bbox {
        private double south;
        private double north;
        private double west;
        private double east;

        /** True if the given point falls within the supported bounding box. */
        public boolean contains(double lat, double lng) {
            return lat >= south && lat <= north && lng >= west && lng <= east;
        }

        /** Overpass API expects coordinates in south,west,north,east order. */
        public String toOverpassFormat() {
            return "%.6f,%.6f,%.6f,%.6f".formatted(south, west, north, east);
        }

        public double getCenterLat() { return (south + north) / 2.0; }
        public double getCenterLng() { return (west + east) / 2.0; }
    }
}
