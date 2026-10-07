package com.sashank.map_shortest_path_finder.dto;

/**
 * Request body for POST /api/routes/intent.
 *
 * start / end       — the route endpoints as coordinates (same {@link LatLng} as POST /api/shortest-path).
 *                     Both are snapped to the nearest graph node and must lie inside the supported region.
 * instruction       — free text describing HOW to travel ("fastest route without tolls"). It is not
 *                     used to find places: a place name in it is ignored (and reported).
 * unknownDataPolicy — optional "STRICT" (default) or "ALLOW_UNKNOWN": what hard constraints do about
 *                     road segments with missing OSM tags. The user's decision; see intent.UnknownDataPolicy.
 * objective         — optional "FASTEST" or "SHORTEST" chosen explicitly (e.g. by a UI selector). When present
 *                     it overrides whatever the instruction implies; null, blank or "AUTO" lets the instruction
 *                     decide (default FASTEST). "ECO_FRIENDLY" is rejected: there is no emissions model.
 */
public record RouteIntentRequest(LatLng start, LatLng end, String instruction, String unknownDataPolicy, String objective) {
    public RouteIntentRequest(LatLng start, LatLng end, String instruction, String unknownDataPolicy) {
        this(start, end, instruction, unknownDataPolicy, null);
    }

    public RouteIntentRequest(LatLng start, LatLng end, String instruction) {
        this(start, end, instruction, null, null);
    }
}
