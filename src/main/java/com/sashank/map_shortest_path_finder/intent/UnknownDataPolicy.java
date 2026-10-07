package com.sashank.map_shortest_path_finder.intent;

import java.util.Locale;

/**
 * How a HARD constraint treats road segments whose relevant OSM tag is missing. This is
 * the user's decision (a request field), never inferred by the language model.
 *
 * <ul>
 *   <li>{@link #STRICT} (default): a segment may be used only if the data POSITIVELY
 *       establishes it complies — toll=no, a paved surface tag, a known road class outside
 *       the restricted set. A successful route therefore provably satisfies every hard
 *       constraint, but because OSM tagging is sparse (toll and surface especially) there
 *       may be no such route; the API then says so instead of guessing.</li>
 *   <li>{@link #ALLOW_UNKNOWN}: segments are excluded only when KNOWN to violate (toll=yes,
 *       unpaved surface, restricted class). Compliance on untagged segments is unverified,
 *       and the response reports how much of the route that is.</li>
 * </ul>
 */
public enum UnknownDataPolicy {
    STRICT, ALLOW_UNKNOWN;

    /** Null or blank → STRICT. @throws IllegalArgumentException for anything else unrecognised. */
    public static UnknownDataPolicy parse(String value) {
        if (value == null || value.isBlank()) return STRICT;
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Invalid unknownDataPolicy '" + value + "': must be STRICT or ALLOW_UNKNOWN");
        }
    }
}
