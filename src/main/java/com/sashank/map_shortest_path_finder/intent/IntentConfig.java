package com.sashank.map_shortest_path_finder.intent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Settings for intent-based routing (intent.* in application.properties). The LLM provider,
 * model, credentials and timeouts are NOT here: they are Spring AI's own spring.ai.* properties,
 * driven by environment variables (see application.properties).
 */
@Component
@ConfigurationProperties(prefix = "intent")
@Data
public class IntentConfig {

    private int maxQueryLength = 500;

    /**
     * Hard cap on one LLM call, in seconds, applied by the parser itself and therefore identical for every provider.
     * Needed because not every Spring AI provider exposes a timeout (Gemini's does not); Anthropic/OpenAI also have
     * their own lower per-request timeouts.
     */
    private int llmTimeoutSeconds = 30;
    private Geocoder geocoder = new Geocoder();
    private Routing routing = new Routing();

    /**
     * Routing policy knobs (intent.routing.*). All multipliers are dimensionless factors on
     * an edge's own cost, so they stay in the units of the chosen objective (seconds for
     * FASTEST, metres for SHORTEST); turn penalties are the only additive terms.
     */
    @Data
    public static class Routing {
        /**
         * Explicit classification policy for avoidHighways: edges of these road classes are
         * "highways". Default is motorways and trunk roads (incl. their _link ramps). Primary
         * roads are NOT restricted by default, even though Indian national highways are
         * sometimes tagged primary in OSM — add PRIMARY here if that is what your users mean.
         */
        private java.util.List<com.sashank.map_shortest_path_finder.model.RoadClass> highwayClasses =
            java.util.List.of(com.sashank.map_shortest_path_finder.model.RoadClass.MOTORWAY,
                              com.sashank.map_shortest_path_finder.model.RoadClass.TRUNK);

        /** preferWellLit: factor on edges tagged lit=yes / lit unknown / lit=no. Unknown sits between so absent data isn't treated as either. */
        private double litFactor = 1.0;
        private double unknownLitFactor = 1.25;
        private double unlitFactor = 1.5;

        /** minimizeTurns: flat extra cost, in seconds, for each turn sharper than the threshold. */
        private double turnPenaltySeconds = 6.0;
        private double turnAngleThresholdDegrees = 30.0;
        /** Converts the turn penalty to metres for SHORTEST: penalty_m = seconds * speed. */
        private double nominalSpeedKmh = 40.0;

        /**
         * Soft preferences may lengthen the route, but never beyond this multiple of the plain
         * route's objective cost (same hard constraints, no preferences). Beyond it the plain
         * route is returned and the response says so.
         */
        private double maxDetourFactor = 1.3;

        /**
         * minimizeTurns needs an edge-expanded search, which is only affordable for trips up to this straight-line
         * length. Longer trips get the plain route (the response says turn minimisation was skipped).
         */
        private double maxTurnAwareDistanceMeters = 12_000;
        /** Extra space around the trip in which the turn-aware search looks for roads (never less than half the trip length). */
        private double turnAwareWindowMarginMeters = 2_000;
    }

    @Data
    public static class Geocoder {
        private String baseUrl = "https://nominatim.openstreetmap.org";
        /** Nominatim's usage policy requires an identifying User-Agent. */
        private String userAgent = "WayFinder-portfolio-project";
        private int timeoutSeconds = 10;
    }
}
