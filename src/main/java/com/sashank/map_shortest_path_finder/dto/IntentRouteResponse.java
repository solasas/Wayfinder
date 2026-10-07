package com.sashank.map_shortest_path_finder.dto;

import com.sashank.map_shortest_path_finder.intent.PreferenceRoutingService;
import com.sashank.map_shortest_path_finder.intent.RouteIntent;
import com.sashank.map_shortest_path_finder.intent.UnknownDataPolicy;

import java.util.List;

/**
 * Response for POST /api/intent-route.
 *
 * intent      — what the language model understood, after validation (so the UI can show it).
 * origin / destination — the places actually routed between (destination resolved from its name).
 * unknownDataPolicy — how hard constraints treated segments with missing tags (echoed).
 * route       — same shape as POST /api/shortest-path; distance/time are the true totals.
 * details     — measured facts about the route: metres with unverified toll/surface/class data,
 *               lighting breakdown (from OSM tags only), sharp-turn count.
 * plainRoute  — distance/time/turns of the route with the same constraints but no soft
 *               preferences, so the cost of the preferences is visible.
 * explanation — plain-text account of how the route relates to each requested preference,
 *               computed from the route's real road segments (not written by the model).
 * warnings    — anything not fully honoured or verified (unsupported requests, partly verified constraints, sparse data).
 */
public record IntentRouteResponse(
    RouteIntent intent,
    UnknownDataPolicy unknownDataPolicy,
    Place origin,
    Place destination,
    PathResponse route,
    PreferenceRoutingService.RouteStats details,
    PreferenceRoutingService.Baseline plainRoute,
    String explanation,
    List<String> warnings
) {
    public record Place(String name, LatLng location) {}
}
