package com.sashank.map_shortest_path_finder.intent;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.Arrays;
import java.util.List;

/**
 * SOFT preferences: nudges that reweight road segments but never forbid them, so a
 * route always exists whenever one exists without preferences. Contrast {@link Constraints}.
 *
 * Extension steps are the same as for Constraints (validator, routing multiplier, explainer).
 *
 * minimizeTurns is part of the schema and validated, but the routing engine cannot apply
 * it yet: turns are a property of consecutive edges, which the node-based weight model
 * can't express (it needs an edge-expanded graph). The response flags it as not applied.
 */
public record Preferences(
    @JsonPropertyDescription("true if the user would like well-lit roads, e.g. 'prefer lit roads', 'at night'")
    boolean preferWellLit,
    @JsonPropertyDescription("true if the user would like fewer turns, e.g. 'simple route', 'few turns'")
    boolean minimizeTurns) {

    public static final Preferences NONE = new Preferences(false, false);

    /** JSON field names, derived from the record components. */
    public static List<String> keys() {
        return Arrays.stream(Preferences.class.getRecordComponents()).map(c -> c.getName()).toList();
    }
}
