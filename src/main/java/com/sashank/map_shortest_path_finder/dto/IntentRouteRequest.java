package com.sashank.map_shortest_path_finder.dto;

/**
 * Request body for POST /api/intent-route.
 *
 * query             — free text, e.g. "fastest way to the railway station, avoid highways".
 * start             — where the trip begins (e.g. the user's pin). Optional only if the text
 *                     itself names a starting place.
 * unknownDataPolicy — "STRICT" (default) or "ALLOW_UNKNOWN": what hard constraints (avoid
 *                     tolls/highways/unpaved) do about road segments with missing OSM tags.
 *                     The user's decision only; the language model never sets it.
 *                     See intent.UnknownDataPolicy.
 */
public record IntentRouteRequest(String query, LatLng start, String unknownDataPolicy) {
    public IntentRouteRequest(String query, LatLng start) {
        this(query, start, null);
    }
}
