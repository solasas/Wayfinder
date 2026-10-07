package com.sashank.map_shortest_path_finder.exception;

/**
 * Thrown when Dijkstra cannot reach the target node from the source.
 * This can happen with genuinely disconnected road components (e.g., islands
 * or areas with no road connection within the bounding box).
 */
public class RouteNotFoundException extends RuntimeException {
    public RouteNotFoundException(long sourceId, long targetId) {
        // Parenthesised: without them .formatted() binds only to the last literal (which has no placeholders)
        // and the message would contain raw "%d".
        super(("No route found between node %d and node %d. "
            + "The points may be in disconnected parts of the road network.")
            .formatted(sourceId, targetId));
    }

    /** For subclasses with a more specific explanation. */
    protected RouteNotFoundException(String message) {
        super(message);
    }
}
