package com.sashank.map_shortest_path_finder.dto;

import com.sashank.map_shortest_path_finder.intent.Constraints;
import com.sashank.map_shortest_path_finder.intent.Objective;
import com.sashank.map_shortest_path_finder.intent.Preferences;
import com.sashank.map_shortest_path_finder.intent.PreferenceRoutingService;
import com.sashank.map_shortest_path_finder.intent.UnknownDataPolicy;

import java.util.List;

/**
 * Response for POST /api/routes/intent. Every number comes from the graph engine.
 *
 * path, distanceMeters, estimatedTimeSecs — same fields and meaning as POST /api/shortest-path: the route
 *     as ordered lat/lng points, its TRUE length and travel time (never penalised search costs).
 * objective            — what was minimised (FASTEST = time, SHORTEST = distance).
 * constraints          — HARD rules in force. The route satisfies all of them; constraints are never relaxed.
 * preferences          — SOFT wishes that were requested.
 * preferencesApplied   — which of those are evidenced in the route. A preference is NOT applied when it would
 *                        have cost more than the allowed margin, nor (preferWellLit) when the route has no
 *                        lighting-tagged segments to show for it; see notices / dataLimitations.
 * unknownDataPolicy    — how constraints treated road segments with missing tags.
 * snappedStart/End     — the graph points the requested coordinates were snapped to (the path starts/ends here).
 * explanation          — concise account of the decision, computed from the route's measured properties.
 * dataLimitations      — what the map data cannot establish or lacks (e.g. toll tags on 0.3% of segments).
 * notices              — other things to know: unsupported requests that were ignored, place names ignored, etc.
 * details              — measured facts: metres with unverified toll/surface/class data, lighting breakdown, sharp turns.
 * plainRoute           — length/time/turns of the route with the same constraints but no soft preferences
 *                        (null when no soft preference was requested), so the cost of a preference is visible.
 */
public record RouteIntentResponse(
    List<LatLng> path,
    double distanceMeters,
    long estimatedTimeSecs,
    Objective objective,
    Constraints constraints,
    Preferences preferences,
    Preferences preferencesApplied,
    UnknownDataPolicy unknownDataPolicy,
    LatLng snappedStart,
    LatLng snappedEnd,
    String explanation,
    List<String> dataLimitations,
    List<String> notices,
    PreferenceRoutingService.RouteStats details,
    PreferenceRoutingService.Baseline plainRoute
) {}
