package com.sashank.map_shortest_path_finder.intent;

import com.sashank.map_shortest_path_finder.exception.ClarificationNeededException;
import com.sashank.map_shortest_path_finder.exception.LlmUnavailableException;

/** Turns a natural-language travel request into a validated {@link RouteIntent}. */
public interface IntentParser {

    /**
     * @param destinationRequired true when the destination must come from the text (asks the user
     *        if it is missing); false when the caller already has route endpoints as coordinates
     *        and the text only describes how to travel — a place name in it is then ignored
     * @throws IllegalArgumentException      if the text isn't a usable routing request (→ 400)
     * @throws ClarificationNeededException  if it is too ambiguous or contradictory to act on (→ 422)
     * @throws LlmUnavailableException       if the model is not configured or unreachable (→ 429/503/504)
     */
    RouteIntent parse(String query, boolean destinationRequired);

    /** Parses text that must name a destination (the endpoint resolves it by name). */
    default RouteIntent parse(String query) {
        return parse(query, true);
    }
}
