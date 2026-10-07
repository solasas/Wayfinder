package com.sashank.map_shortest_path_finder.intent;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * The shape the language model is asked to produce — and nothing more. The JSON schema sent
 * to the model is generated from this record, and the model's reply is deserialised into it
 * with a strict mapper (unknown fields and wrong types fail), so the model is held to exactly
 * this vocabulary. It is a DRAFT: nothing here is trusted until it has been turned into a
 * {@link RouteIntent} by {@link SpringAiIntentParser}, whose constructor re-validates it.
 *
 * It reuses {@link Constraints} and {@link Preferences} directly, so the model's vocabulary
 * can't drift from what the routing engine understands. There are no fields for coordinates,
 * SQL, code or graph operations — there is nowhere to put them.
 */
@JsonClassDescription("A structured routing intent extracted from a driver's request.")
public record IntentDraft(
    @JsonPropertyDescription("OK: the destination is clear and the instructions are consistent. "
        + "CLARIFY: the destination is missing or could mean several different places, or the instructions contradict "
        + "each other. REJECT: the text is not a request to travel somewhere.")
    Status status,

    @JsonPropertyDescription("The destination place name exactly as the user wrote it. Never coordinates. Empty if none.")
    String destination,

    @JsonPropertyDescription("The starting place name, only if the user names one; otherwise null.")
    String origin,

    @JsonPropertyDescription("FASTEST (default; minimise time) or SHORTEST (minimise distance).")
    ObjectiveChoice objective,

    @JsonPropertyDescription("HARD rules the user insists on ('avoid', 'no', 'without').")
    Constraints constraints,

    @JsonPropertyDescription("SOFT wishes ('prefer', 'if possible').")
    Preferences preferences,

    @JsonPropertyDescription("Short phrases for anything requested that no other field covers (traffic, scenic, "
        + "eco-friendly, cheapest, avoiding a named road...). Do not approximate these with other fields.")
    List<String> unsupported,

    @JsonPropertyDescription("For CLARIFY only: ONE short question for the user. Otherwise null.")
    String clarification
) {
    public enum Status { OK, CLARIFY, REJECT }

    /** Only objectives the engine can honour; ECO_FRIENDLY is deliberately not offered to the model. */
    public enum ObjectiveChoice {
        FASTEST(Objective.FASTEST), SHORTEST(Objective.SHORTEST);

        private final Objective objective;
        ObjectiveChoice(Objective objective) { this.objective = objective; }
        public Objective toObjective() { return objective; }
    }
}
