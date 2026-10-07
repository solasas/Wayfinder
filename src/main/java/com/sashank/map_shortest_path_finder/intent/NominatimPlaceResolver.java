package com.sashank.map_shortest_path_finder.intent;

import com.sashank.map_shortest_path_finder.config.RegionConfig;
import com.sashank.map_shortest_path_finder.dto.LatLng;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Geocodes place names with OSM's Nominatim, hard-limited to the region's bounding
 * box (viewbox + bounded=1) so a name like "airport" can only resolve to something
 * we actually have road data for. Results are cached in memory, which also keeps
 * us well inside Nominatim's 1 request/second usage policy for a demo.
 */
@Service
public class NominatimPlaceResolver implements PlaceResolver {

    private static final Logger log = LoggerFactory.getLogger(NominatimPlaceResolver.class);

    private final RegionConfig regionConfig;
    private final RestClient restClient;
    private final Map<String, ResolvedPlace> cache = new ConcurrentHashMap<>();

    @Autowired
    public NominatimPlaceResolver(IntentConfig config, RegionConfig regionConfig) {
        this(regionConfig, RestClient.builder()
            .baseUrl(config.getGeocoder().getBaseUrl())
            .defaultHeader("User-Agent", config.getGeocoder().getUserAgent())
            .requestFactory(timeouts(config))
            .build());
    }

    /** Test seam: lets unit tests supply a RestClient bound to a mock server. */
    NominatimPlaceResolver(RegionConfig regionConfig, RestClient restClient) {
        this.regionConfig = regionConfig;
        this.restClient = restClient;
    }

    private static SimpleClientHttpRequestFactory timeouts(IntentConfig config) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(config.getGeocoder().getTimeoutSeconds()));
        return factory;
    }

    @Override
    public ResolvedPlace resolve(String name) {
        String key = name.strip().toLowerCase(Locale.ROOT);
        ResolvedPlace cached = cache.get(key);
        if (cached != null) return cached;

        RegionConfig.Bbox b = regionConfig.getBbox();
        // Nominatim viewbox order: left(west),top(north),right(east),bottom(south)
        String viewbox = "%f,%f,%f,%f".formatted(b.getWest(), b.getNorth(), b.getEast(), b.getSouth());

        List<Map<String, Object>> results;
        try {
            results = restClient.get()
                .uri(u -> u.path("/search")
                    .queryParam("q", name)
                    .queryParam("format", "jsonv2")
                    .queryParam("limit", 1)
                    .queryParam("viewbox", viewbox)
                    .queryParam("bounded", 1)
                    .build())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        } catch (RestClientException e) {
            log.warn("Geocoder call failed: {}", e.getMessage());
            throw new IllegalStateException("The place-lookup service is unavailable right now. Please try again.");
        }

        if (results == null || results.isEmpty()) {
            throw new PlaceNotFoundException(name, regionConfig.getName());
        }
        Map<String, Object> hit = results.get(0);
        double lat;
        double lon;
        try {
            lat = Double.parseDouble(String.valueOf(hit.get("lat")));
            lon = Double.parseDouble(String.valueOf(hit.get("lon")));
        } catch (NumberFormatException e) {
            throw new PlaceNotFoundException(name, regionConfig.getName());
        }
        if (!b.contains(lat, lon)) {
            throw new PlaceNotFoundException(name, regionConfig.getName());
        }

        Object display = hit.get("display_name");
        ResolvedPlace place = new ResolvedPlace(display == null ? name : String.valueOf(display), new LatLng(lat, lon));
        cache.put(key, place);
        return place;
    }
}
