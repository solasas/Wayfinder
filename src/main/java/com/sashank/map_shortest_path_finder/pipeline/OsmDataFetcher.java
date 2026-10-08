package com.sashank.map_shortest_path_finder.pipeline;

import com.sashank.map_shortest_path_finder.config.RegionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Fetches raw road-network data from the Overpass API (OpenStreetMap's public query service). The response is a
 * JSON dump of the ways matching a highway filter inside a bounding box, plus every node those ways reference.
 *
 * Built for a long, polite, restartable import against a shared public server:
 *  - every request carries an identifying User-Agent — Overpass answers anonymous/default clients with
 *    "406 Not Acceptable";
 *  - several endpoints (region.importer.urls) are tried in rotation, one per attempt, so a single overloaded
 *    server does not sink the import;
 *  - rate limiting (429) and overload (502/503/504) are retried with growing backoff;
 *  - a 200 response whose body carries a "runtime error" remark (Overpass's way of reporting a query that timed
 *    out or ran out of memory, leaving the data PARTIAL) is treated as a failure, never as an empty/complete tile.
 */
@Service
@ConditionalOnProperty(name = "region.importer.source", havingValue = "overpass", matchIfMissing = true)
public class OsmDataFetcher implements RoadNetworkSource {

    private static final Logger log = LoggerFactory.getLogger(OsmDataFetcher.class);
    private static final Set<Integer> RETRYABLE = Set.of(429, 500, 502, 503, 504);

    @FunctionalInterface
    interface Sleeper { void sleep(long millis) throws InterruptedException; }

    private final RestTemplate restTemplate;
    private final List<String> urls;
    private final RegionConfig.Importer settings;
    private final Sleeper sleeper;

    @Autowired
    public OsmDataFetcher(RegionConfig regionConfig) {
        this(restTemplateFor(regionConfig.getImporter()), regionConfig.getImporter().getUrls(), regionConfig.getImporter(), Thread::sleep);
    }

    /** Test seam: inject the HTTP client, endpoint and a no-op sleeper. */
    OsmDataFetcher(RestTemplate restTemplate, String url, RegionConfig.Importer settings, Sleeper sleeper) {
        this(restTemplate, List.of(url), settings, sleeper);
    }

    /** Test seam: as above, with several endpoints to rotate through. */
    OsmDataFetcher(RestTemplate restTemplate, List<String> urls, RegionConfig.Importer settings, Sleeper sleeper) {
        if (urls == null || urls.isEmpty()) {
            throw new IllegalStateException("region.importer.urls must list at least one Overpass endpoint.");
        }
        this.restTemplate = restTemplate;
        this.urls = List.copyOf(urls);
        this.settings = settings;
        this.sleeper = sleeper;
    }

    private static RestTemplate restTemplateFor(RegionConfig.Importer settings) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(15));
        factory.setReadTimeout(Duration.ofSeconds(settings.getReadTimeoutSeconds()));
        return new RestTemplate(factory);
    }

    @Override
    public long requestDelayMillis() {
        return settings.getRequestDelayMillis();
    }

    /** Full-detail fetch of one box (the original single-city behaviour). */
    public OsmResponse fetchRoadNetwork(RegionConfig.Bbox bbox) {
        return fetchRoadNetwork(bbox, RegionConfig.FULL_DETAIL_FILTER);
    }

    /**
     * @param highwayFilter regex matched against the OSM {@code highway} tag (operator-supplied configuration)
     * @throws IllegalStateException if Overpass cannot deliver the tile after all attempts, or delivers a partial one
     */
    @Override
    public OsmResponse fetchRoadNetwork(RegionConfig.Bbox bbox, String highwayFilter) {
        String query = buildOverpassQuery(bbox.toOverpassFormat(), highwayFilter);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set(HttpHeaders.USER_AGENT, settings.getUserAgent());
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        // Overpass expects the query as a form field named "data"
        String body = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

        String lastProblem = "no attempt made";
        for (int attempt = 1; attempt <= settings.getMaxAttempts(); attempt++) {
            // Rotate endpoints: a failed attempt moves on to the next server, wrapping around after the last
            String url = urls.get((attempt - 1) % urls.size());
            try {
                ResponseEntity<OsmResponse> response = restTemplate.postForEntity(url, new HttpEntity<>(body, headers), OsmResponse.class);
                OsmResponse osm = response.getBody();
                if (osm == null || osm.getElements() == null) {
                    lastProblem = "empty response body";
                } else if (osm.getRemark() != null && osm.getRemark().toLowerCase().contains("runtime error")) {
                    lastProblem = "Overpass reported: " + osm.getRemark(); // partial data — never accept silently
                } else {
                    log.debug("Received {} OSM elements for {}.", osm.getElements().size(), bbox.toOverpassFormat());
                    return osm;
                }
            } catch (HttpStatusCodeException e) {
                int status = e.getStatusCode().value();
                if (status == 406) {
                    throw new IllegalStateException("Overpass rejected the request as 406 Not Acceptable. It requires an "
                        + "identifying User-Agent: set region.importer.user-agent (currently '" + settings.getUserAgent() + "').", e);
                }
                if (!RETRYABLE.contains(status)) {
                    throw new IllegalStateException("Overpass refused the query (HTTP " + status + "): "
                        + abbreviate(e.getResponseBodyAsString()), e);
                }
                lastProblem = "HTTP " + status + " from " + url;
            } catch (ResourceAccessException e) {
                lastProblem = "network/timeout from " + url + ": " + e.getMostSpecificCause().getClass().getSimpleName();
            }

            if (attempt < settings.getMaxAttempts()) {
                long wait = settings.getRetryBackoffMillis() * attempt;
                log.warn("Overpass attempt {}/{} for {} failed ({}). Retrying in {} s.",
                    attempt, settings.getMaxAttempts(), bbox.toOverpassFormat(), lastProblem, wait / 1000);
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting to retry Overpass", ie);
                }
            }
        }
        throw new IllegalStateException("Overpass did not deliver " + bbox.toOverpassFormat() + " after "
            + settings.getMaxAttempts() + " attempts (last problem: " + lastProblem + "). Re-run the import to resume.");
    }

    /**
     * Overpass QL for the ways matching {@code highwayFilter} inside the box, then (>;) every node they reference.
     * [timeout:300] is the server-side limit; the HTTP read timeout is configured separately.
     */
    static String buildOverpassQuery(String bboxStr, String highwayFilter) {
        String escaped = highwayFilter.replace("\\", "\\\\").replace("\"", "\\\"");
        return """
                [out:json][timeout:300];
                (
                  way["highway"~"%s"](%s);
                );
                out body;
                >;
                out skel qt;
                """.formatted(escaped, bboxStr);
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }
}
