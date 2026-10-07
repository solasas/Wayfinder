package com.sashank.map_shortest_path_finder.intent;

import java.util.List;
import java.util.Locale;

/**
 * Builds the human-readable explanation from the route's *measured* properties.
 * Deliberately not LLM-written: every claim is computed from the real road segments,
 * so the text can't promise something the route doesn't do.
 *
 * Wording rules: hard-constraint claims say whether compliance was VERIFIED by tags or
 * merely not contradicted; lighting is described only as what OSM tags say ("tagged lit"),
 * never as "safe" — no safety score exists in the data.
 */
final class RouteExplainer {

    /** Below this share of the route having lit=yes/no tagged, we flag the lighting claim as weak. */
    private static final double LIT_COVERAGE_WARN = 0.5;

    private RouteExplainer() {}

    /**
     * @param text            the explanation sentence(s)
     * @param dataLimitations things the map DATA cannot establish or lacks (partly verified constraints, no lighting tags)
     * @param notices         everything else the user should know (unsupported requests, preferences not applied)
     */
    record Explanation(String text, List<String> dataLimitations, List<String> notices) {
        /** Both lists in one, data limitations first — the single "warnings" list of /api/intent-route. */
        List<String> combined() {
            List<String> all = new java.util.ArrayList<>(dataLimitations);
            all.addAll(notices);
            return List.copyOf(all);
        }
    }

    /** @param destinationName shown as "to X" when non-null (name-based endpoint); null when routing between given coordinates */
    static Explanation explain(RouteIntent intent, String destinationName, UnknownDataPolicy policy,
                               PreferenceRoutingService.RouteOutcome o) {
        List<String> limitations = new java.util.ArrayList<>();
        List<String> notices = new java.util.ArrayList<>();
        PreferenceRoutingService.RouteStats s = o.stats();
        Constraints c = intent.constraints();
        Preferences p = intent.preferences();
        double total = Math.max(o.distanceMeters(), 1.0);
        StringBuilder sb = new StringBuilder();

        sb.append(intent.objective() == Objective.FASTEST ? "Fastest" : "Shortest")
          .append(" route").append(destinationName == null ? "" : " to " + shortName(destinationName)).append(": ")
          .append(String.format(Locale.ROOT, "%.1f km, about %d min.",
              o.distanceMeters() / 1000.0, Math.max(1, Math.round(o.timeSeconds() / 60.0))));

        // ── Hard constraints: the route satisfies them by construction (violating edges were
        //    never searchable); what varies is how much of that is verified by data. ──
        if (c.avoidTolls()) {
            verified(sb, limitations, policy, s.tollUnverifiedMeters(), total,
                "Every segment is tagged non-toll in OpenStreetMap.", "toll status");
        }
        if (c.avoidHighways()) {
            verified(sb, limitations, policy, s.highwayClassUnknownMeters(), total,
                "It uses no motorway/trunk-class roads (road class known for every segment).", "road class");
        }
        if (c.avoidUnpaved()) {
            verified(sb, limitations, policy, s.surfaceUnverifiedMeters(), total,
                "Every segment is tagged with a paved surface in OpenStreetMap.", "surface");
        }

        // ── Soft preferences: what the data shows, and what the preference cost ──
        if (p.preferWellLit()) {
            double known = s.litMeters() + s.unlitMeters();
            if (known == 0) {
                sb.append(" Lighting: OpenStreetMap has no lighting tags along this route, so the preference had nothing to act on.");
                limitations.add("No street-lighting data exists for this route; 'prefer well-lit roads' had no effect.");
            } else {
                sb.append(String.format(Locale.ROOT, " Lighting (per OpenStreetMap tags): %.0f%% lit, %.0f%% unlit, %.0f%% untagged.",
                    100.0 * s.litMeters() / total, 100.0 * s.unlitMeters() / total, 100.0 * s.unknownLitMeters() / total));
                if (known / total < LIT_COVERAGE_WARN) {
                    limitations.add("Lighting is tagged on only %.0f%% of this route, so the lighting preference is only partly informed."
                        .formatted(100.0 * known / total));
                }
            }
        }
        boolean turnsSkipped = p.minimizeTurns() && o.turnMinimisationSkipped();
        boolean turnsApplicable = p.minimizeTurns() && !turnsSkipped;
        if (turnsApplicable) {
            sb.append(String.format(Locale.ROOT, " It has %d sharp turn%s (plain route: %d).",
                s.turns(), s.turns() == 1 ? "" : "s", o.baseline().turns()));
        }
        if (turnsSkipped) {
            notices.add("'Minimise turns' was not applied: this trip is longer than the distance that feature supports, "
                + "so the route may have many turns.");
        }
        if (p.preferWellLit() || turnsApplicable) {
            PreferenceRoutingService.Baseline b = o.baseline();
            if (o.fellBackToBaseline()) {
                sb.append(" Your preferences were not applied: they would have made the route more than the allowed margin worse.");
                notices.add("Soft preferences would have lengthened the route beyond the allowed margin, so the plain %s route was returned."
                    .formatted(intent.objective() == Objective.FASTEST ? "fastest" : "shortest"));
            } else {
                sb.append(String.format(Locale.ROOT, " Versus the plain %s route under the same constraints: %+.1f%% time, %+.1f%% distance.",
                    intent.objective() == Objective.FASTEST ? "fastest" : "shortest",
                    pct(o.timeSeconds(), b.timeSeconds()), pct(o.distanceMeters(), b.distanceMeters())));
            }
        }

        for (String u : intent.unsupported()) {
            notices.add("Not supported, so ignored: " + u);
        }
        return new Explanation(sb.toString(), List.copyOf(limitations), List.copyOf(notices));
    }

    private static void verified(StringBuilder sb, List<String> warnings, UnknownDataPolicy policy,
                                 double unverifiedMeters, double total, String strictClaim, String what) {
        if (unverifiedMeters == 0) {
            sb.append(' ').append(strictClaim);
        } else {
            sb.append(String.format(Locale.ROOT,
                " No known violations, but %.0f%% of the route has unknown %s, so compliance is not verified for that part.",
                100.0 * unverifiedMeters / total, what));
            warnings.add(("Constraint on %s is only partly verified (policy %s): %.0f m of the route has no %s data.")
                .formatted(what, policy, unverifiedMeters, what));
        }
    }

    private static double pct(double value, double base) {
        return base <= 0 ? 0 : 100.0 * (value - base) / base;
    }

    /** First comma-separated part of a geocoder display name ("Rajahmundry Railway Station, ..., India"). */
    private static String shortName(String displayName) {
        int comma = displayName.indexOf(',');
        return comma > 0 ? displayName.substring(0, comma) : displayName;
    }
}
