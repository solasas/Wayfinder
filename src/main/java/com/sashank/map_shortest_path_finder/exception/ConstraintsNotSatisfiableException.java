package com.sashank.map_shortest_path_finder.exception;

/**
 * Thrown when a route exists between two points but none satisfies the requested hard
 * constraints (or the road data can't verify that any does). Maps to 404 like
 * RouteNotFoundException; the message says which case it is and what the user can decide.
 *
 * retryWithAllowUnknown tells a client whether the user can fix this themselves by resending
 * with unknownDataPolicy=ALLOW_UNKNOWN (true: the constraints are merely unverifiable under
 * STRICT) or not (false: no route exists even when unknown data is accepted).
 */
public class ConstraintsNotSatisfiableException extends RouteNotFoundException {

    public static final String CODE = "CONSTRAINTS_NOT_SATISFIABLE";

    private final boolean retryWithAllowUnknown;

    public ConstraintsNotSatisfiableException(boolean retryWithAllowUnknown, String message) {
        super(message);
        this.retryWithAllowUnknown = retryWithAllowUnknown;
    }

    public boolean isRetryWithAllowUnknown() { return retryWithAllowUnknown; }
}
