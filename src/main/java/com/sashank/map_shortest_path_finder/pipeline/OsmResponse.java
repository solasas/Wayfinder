package com.sashank.map_shortest_path_finder.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Jackson mapping for the Overpass API JSON response.
 *
 * The response is a flat list of "elements" that can be either nodes or ways:
 *
 *   { "elements": [
 *       { "type": "node", "id": 123, "lat": 16.98, "lon": 81.78 },
 *       { "type": "way",  "id": 456, "nodes": [123, 124, 125],
 *         "tags": { "highway": "residential", "oneway": "yes" } }
 *   ]}
 *
 * Fields not present for a given element type are simply null (Jackson handles this).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OsmResponse {

    private List<OsmElement> elements;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OsmElement {

        private String type;   // "node" or "way"
        private long id;

        // Present on type="node" only
        private double lat;

        @JsonProperty("lon")   // OSM uses "lon"; we rename it to "lon" to stay close to source
        private double lon;

        // Present on type="way" only: ordered list of node ref IDs that form the road
        private List<Long> nodes;

        // Present on type="way" only: e.g. {"highway":"residential","oneway":"yes"}
        private Map<String, String> tags;
    }
}
