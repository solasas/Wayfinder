package com.sashank.map_shortest_path_finder.intent;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.Arrays;
import java.util.List;

/**
 * HARD requirements: the route must not use matching road segments. The engine
 * excludes them outright; only if that leaves no route at all are they relaxed to a heavy
 * penalty, and the response then says the constraint was not fully met.
 *
 * To add a constraint: add a component here, then handle it in
 * {@link IntentValidator#constraints}, {@link PreferenceRoutingService#multiplier} and
 * {@link RouteExplainer}. {@link #keys()} drives validation and the LLM tool schema
 * automatically, and IntentValidatorTest fails if the validator is not updated.
 */
public record Constraints(
    @JsonPropertyDescription("true only if the user insists on avoiding toll roads")
    boolean avoidTolls,
    @JsonPropertyDescription("true only if the user insists on avoiding highways/motorways/expressways")
    boolean avoidHighways,
    @JsonPropertyDescription("true only if the user insists on avoiding unpaved/dirt/gravel roads")
    boolean avoidUnpaved) {

    public static final Constraints NONE = new Constraints(false, false, false);

    /** JSON field names, derived from the record components. */
    public static List<String> keys() {
        return Arrays.stream(Constraints.class.getRecordComponents()).map(c -> c.getName()).toList();
    }

    public boolean any() { return avoidTolls || avoidHighways || avoidUnpaved; }
}
