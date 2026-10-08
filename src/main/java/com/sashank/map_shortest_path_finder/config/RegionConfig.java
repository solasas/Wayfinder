package com.sashank.map_shortest_path_finder.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The supported region and how much of its road network is imported where (region.* in application.properties).
 *
 * <pre>
 * bbox        overall extent: what the map shows, and the first check on any user-supplied point
 * corridor    a road filter applied over the WHOLE bbox (e.g. major roads between cities)
 * areas       sub-boxes imported with a (usually fuller) road filter — e.g. street-level detail in each city
 * </pre>
 * With no corridor and no areas the whole bbox is imported with full detail — the original single-city setup.
 *
 * Why not full detail everywhere: a 330 km corridor has millions of road nodes, nearly all of them rural
 * residential/service tracks that no city-to-city route needs, and the graph lives in memory.
 */
@Component
@ConfigurationProperties(prefix = "region")
@Data
public class RegionConfig {

    /** Everything a car can use, as the importer has always fetched it, plus the *_link ramps that join highways. */
    public static final String FULL_DETAIL_FILTER =
        "^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service)(_link)?$";

    private String name;
    private Bbox bbox = new Bbox();

    /**
     * How far (metres) a requested point may be from the nearest imported road before it is refused. Essential
     * once the bbox contains sea and empty countryside. 0 = no limit (the original single-city behaviour).
     */
    private double maxSnapMeters = 0;

    private Corridor corridor = new Corridor();
    private List<Area> areas = new ArrayList<>();
    private Importer importer = new Importer();

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

    /** A road filter over the whole bbox. Blank filter = no corridor layer. */
    @Data
    public static class Corridor {
        /** Regex on the OSM highway tag, e.g. {@code ^(motorway|trunk|primary|secondary|tertiary)(_link)?$}. */
        private String highwayFilter = "";
        private double tileSizeDegrees = 0.5;

        public boolean isEnabled() { return highwayFilter != null && !highwayFilter.isBlank(); }
    }

    /** A named sub-box imported with its own road filter (default: full detail). */
    @Data
    public static class Area {
        private String name;
        private Bbox bbox = new Bbox();
        private String highwayFilter = FULL_DETAIL_FILTER;
        private double tileSizeDegrees = 0.1;
    }

    /** Where the import reads OSM data from, plus politeness/robustness settings for the public Overpass API. */
    @Data
    public static class Importer {
        /**
         * "overpass" (default): query the public Overpass API tile by tile. "pbf": read a local .osm.pbf extract
         * (e.g. from Geofabrik) at {@link #pbfPath} — no network, no rate limits.
         */
        private String source = "overpass";
        /** Path of the .osm.pbf file used when source=pbf. Relative paths resolve against the working directory. */
        private String pbfPath = "data/region.osm.pbf";
        /**
         * Overpass endpoints, tried in rotation: attempt 1 uses the first, a failed attempt moves to the next.
         * Setting region.importer.urls replaces this list.
         */
        private List<String> urls = new ArrayList<>(List.of(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"));
        /** Overpass rejects anonymous clients; identify the project (and ideally add a contact URL/e-mail). */
        private String userAgent = "WayFinder-portfolio-project/1.0";
        private long requestDelayMillis = 3_000;
        private int maxAttempts = 6;
        private long retryBackoffMillis = 10_000;
        private int readTimeoutSeconds = 300;
    }

    /** True if the point is inside the overall supported bbox. */
    public boolean contains(double lat, double lng) {
        return bbox.contains(lat, lng);
    }
}
