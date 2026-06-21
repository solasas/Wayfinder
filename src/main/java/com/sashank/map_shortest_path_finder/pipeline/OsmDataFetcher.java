package com.sashank.map_shortest_path_finder.pipeline;

import com.sashank.map_shortest_path_finder.config.RegionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Fetches raw road-network data from the Overpass API (OpenStreetMap's public
 * query service). The response is a JSON dump of nodes and ways in the region.
 *
 * We POST an Overpass QL query rather than GET because the query string can be
 * long; POST body avoids URL length limits and is the recommended approach.
 */
@Service
public class OsmDataFetcher {

    private static final Logger log = LoggerFactory.getLogger(OsmDataFetcher.class);
    private static final String OVERPASS_URL = "https://overpass-api.de/api/interpreter";

    private final RestTemplate restTemplate;

    public OsmDataFetcher() {
        // Overpass can be slow on the public instance; give it 2 minutes.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(15));
        factory.setReadTimeout(Duration.ofMinutes(2));
        this.restTemplate = new RestTemplate(factory);
    }

    public OsmResponse fetchRoadNetwork(RegionConfig.Bbox bbox) {
        String query = buildOverpassQuery(bbox.toOverpassFormat());
        log.info("Querying Overpass API for bbox {}...", bbox.toOverpassFormat());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        // Overpass expects the query as a form field named "data"
        String body = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

        ResponseEntity<OsmResponse> response = restTemplate.postForEntity(
            OVERPASS_URL, new HttpEntity<>(body, headers), OsmResponse.class);

        OsmResponse osmResponse = response.getBody();
        log.info("Received {} OSM elements from Overpass.",
                 osmResponse != null ? osmResponse.getElements().size() : 0);
        return osmResponse;
    }

    /**
     * Overpass QL query that fetches all drivable/walkable roads in the bbox,
     * then recurses (>;) to include all node geometries those ways reference.
     *
     * Highway filter excludes footways, cycleways, construction zones, and
     * informal tracks — keeping the graph to roads a vehicle can actually use.
     *
     * [timeout:90] tells the Overpass server its own timeout in seconds.
     * The HTTP client timeout is set separately above.
     */
    private String buildOverpassQuery(String bboxStr) {
        return """
                [out:json][timeout:90];
                (
                  way["highway"~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service)$"]
                  (%s);
                );
                out body;
                >;
                out skel qt;
                """.formatted(bboxStr);
    }
}
