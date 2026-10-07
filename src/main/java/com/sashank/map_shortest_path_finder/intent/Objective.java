package com.sashank.map_shortest_path_finder.intent;

import com.sashank.map_shortest_path_finder.service.DijkstraService;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * What the route should minimise. Distinct from constraints (hard rules) and preferences
 * (soft nudges): exactly one objective is chosen per request.
 *
 * ECO_FRIENDLY is reserved but NOT available: it needs a documented emissions model
 * (vehicle class, speed-dependent fuel/CO2 curves, gradient) and per-segment data this
 * project doesn't have. Until both exist it is rejected at validation time and is not
 * offered to the language model. To enable it later: give it a model-backed cost, then
 * pass a non-null weight type here.
 */
public enum Objective {
    FASTEST(DijkstraService.WeightType.TIME),
    SHORTEST(DijkstraService.WeightType.DISTANCE),
    ECO_FRIENDLY(null);

    private final DijkstraService.WeightType weightType;

    Objective(DijkstraService.WeightType weightType) {
        this.weightType = weightType;
    }

    public boolean isSupported() { return weightType != null; }

    /** The engine weight this objective minimises. @throws IllegalStateException if not supported. */
    public DijkstraService.WeightType weightType() {
        if (weightType == null) {
            throw new IllegalStateException(name() + " has no routing cost model yet");
        }
        return weightType;
    }

    /**
     * Parses an objective name (case-insensitive; spaces/hyphens treated as underscores).
     *
     * @throws IllegalArgumentException for unknown names and for reserved-but-unavailable ones
     */
    public static Objective parse(String value) {
        String normalised = value.strip().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        Objective o;
        try {
            o = valueOf(normalised);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown objective '" + value + "': must be one of " + supportedNames());
        }
        if (!o.isSupported()) {
            throw new IllegalArgumentException(
                o.name() + " is not available yet: it needs a documented emissions model and per-segment data "
                + "(vehicle type, gradient, speed profile) that this system doesn't have. Supported: " + supportedNames());
        }
        return o;
    }

    public static String supportedNames() {
        return Arrays.stream(values()).filter(Objective::isSupported)
            .map(Enum::name).collect(Collectors.joining(", "));
    }
}
