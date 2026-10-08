package com.sashank.map_shortest_path_finder.pipeline;

import com.sashank.map_shortest_path_finder.config.RegionConfig;

/**
 * Where the importer gets raw OpenStreetMap road data for one tile (region.importer.source):
 * the public Overpass API ({@link OsmDataFetcher}) or a local Geofabrik-style extract ({@link PbfRoadNetworkSource}).
 * Both return the same Overpass-shaped {@link OsmResponse}, so everything downstream is source-agnostic.
 */
public interface RoadNetworkSource {

    /**
     * @param highwayFilter regex matched against the OSM {@code highway} tag
     * @return the matching ways touching {@code bbox}, plus every node those ways reference
     * @throws IllegalStateException if the data cannot be delivered completely
     */
    OsmResponse fetchRoadNetwork(RegionConfig.Bbox bbox, String highwayFilter);

    /** Pause the importer should leave between tiles (0 for a local file; politeness delay for a shared server). */
    default long requestDelayMillis() { return 0; }
}
