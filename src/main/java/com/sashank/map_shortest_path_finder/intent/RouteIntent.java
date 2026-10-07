package com.sashank.map_shortest_path_finder.intent;

import java.util.ArrayList;
import java.util.List;

/**
 * The validated, structured form of a natural-language request — the only thing the
 * LLM's output is ever allowed to become. The routing engine consumes this and never
 * sees model text.
 *
 * <pre>
 * { "destination": "railway station", "objective": "FASTEST",
 *   "constraints": { "avoidTolls": true, "avoidHighways": true, "avoidUnpaved": false },
 *   "preferences": { "preferWellLit": true, "minimizeTurns": false } }
 * </pre>
 *
 * origin / destination are place NAMES; coordinates always come from PlaceResolver,
 * never from the model. Both are optional here: when the caller supplies coordinates for the
 * route endpoints (POST /api/routes/intent) the instruction need not name any place. Endpoints
 * that DO need a destination (POST /api/intent-route) require it at the parser/service level. unsupported lists things the user asked for that no field covers
 * (e.g. "avoid traffic"); they are surfaced as warnings, not approximated.
 *
 * The canonical constructor enforces every invariant, so an invalid RouteIntent cannot
 * exist regardless of who constructs it.
 */
public record RouteIntent(
    String origin,
    String destination,
    Objective objective,
    Constraints constraints,
    Preferences preferences,
    List<String> unsupported
) {
    public static final int MAX_NAME_LENGTH = 120;

    /**
     * Characters a place name may contain: letters/marks/digits in any script, spaces, and . , ' ’ & ( ) / # + -.
     * Names are only ever sent to the geocoder as a URL-encoded query, but an allowlist means that
     * even a manipulated model can't smuggle markup, quotes-and-semicolons or control characters through.
     */
    private static final java.util.regex.Pattern NAME_CHARS =
        java.util.regex.Pattern.compile("^[\\p{L}\\p{M}\\p{N} .,'’&()/#+-]+$");
    public static final int MAX_UNSUPPORTED = 5;

    public RouteIntent {
        destination = (destination == null || destination.isBlank()) ? null : requireName(destination, "destination");
        origin = (origin == null || origin.isBlank()) ? null : requireName(origin, "origin");

        if (objective == null) {
            objective = Objective.FASTEST;
        } else if (!objective.isSupported()) {
            throw new IllegalArgumentException(objective + " is not available yet. Supported: " + Objective.supportedNames());
        }
        constraints = constraints == null ? Constraints.NONE : constraints;
        preferences = preferences == null ? Preferences.NONE : preferences;

        // Informational only (echoed back to the user), so sanitised rather than rejected: stripped of
        // anything outside the name allowlist, blanks dropped, length- and count-capped.
        List<String> cleaned = new ArrayList<>();
        if (unsupported != null) {
            for (String s : unsupported) {
                if (s == null || s.isBlank()) continue;
                String t = s.strip().replaceAll("[^\\p{L}\\p{M}\\p{N} .,'’&()/#+-]", " ").replaceAll("\\s+", " ").strip();
                if (t.isEmpty()) continue;
                cleaned.add(t.length() > MAX_NAME_LENGTH ? t.substring(0, MAX_NAME_LENGTH) : t);
                if (cleaned.size() == MAX_UNSUPPORTED) break;
            }
        }
        unsupported = List.copyOf(cleaned);
    }

    /** Same intent with a different objective (an explicit user choice overriding what the text implied). */
    public RouteIntent withObjective(Objective newObjective) {
        return new RouteIntent(origin, destination, newObjective, constraints, preferences, unsupported);
    }

    private static String requireName(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                "I couldn't tell the " + field + ". Please name " + (field.equals("destination") ? "where you want to go." : "the starting place."));
        }
        String t = value.strip();
        if (t.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(field + " is too long (max " + MAX_NAME_LENGTH + " characters)");
        }
        if (!NAME_CHARS.matcher(t).matches()) {
            throw new IllegalArgumentException(field + " contains characters that are not allowed in a place name");
        }
        return t;
    }
}
