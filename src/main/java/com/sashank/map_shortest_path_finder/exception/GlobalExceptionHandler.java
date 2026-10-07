package com.sashank.map_shortest_path_finder.exception;

import com.sashank.map_shortest_path_finder.intent.PlaceResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Translates application exceptions into HTTP responses with a consistent
 * { "error": "..." } JSON body.
 *
 * Spring's @RestControllerAdvice intercepts exceptions thrown from any
 * @RestController before they reach the client, letting us centralise
 * error formatting instead of scattering try/catch blocks in controllers.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RouteNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleRouteNotFound(RouteNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(PlaceResolver.PlaceNotFoundException.class)
    public ResponseEntity<Map<String, String>> handlePlaceNotFound(PlaceResolver.PlaceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Map.of("error", ex.getMessage()));
    }

    /** Same 404 as other route-not-found cases, plus fields a client can act on without parsing prose. */
    @ExceptionHandler(ConstraintsNotSatisfiableException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintsNotSatisfiable(ConstraintsNotSatisfiableException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
            "error", ex.getMessage(),
            "code", ConstraintsNotSatisfiableException.CODE,
            "retryWithAllowUnknown", ex.isRetryWithAllowUnknown()));
    }

    @ExceptionHandler(ClarificationNeededException.class)
    public ResponseEntity<Map<String, Object>> handleClarification(ClarificationNeededException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(Map.of("error", ex.getQuestion(), "clarificationNeeded", true, "question", ex.getQuestion()));
    }

    @ExceptionHandler(LlmUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleLlmUnavailable(LlmUnavailableException ex) {
        return ResponseEntity.status(ex.getKind().httpStatus())
            .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleServiceUnavailable(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of("error", ex.getMessage()));
    }
}
