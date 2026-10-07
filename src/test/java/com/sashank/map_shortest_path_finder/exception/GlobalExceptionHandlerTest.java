package com.sashank.map_shortest_path_finder.exception;

import com.sashank.map_shortest_path_finder.exception.LlmUnavailableException.Kind;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void clarification_is422_withTheQuestionExposedForClients() {
        ResponseEntity<Map<String, Object>> r = handler.handleClarification(new ClarificationNeededException("Which station?"));
        assertEquals(422, r.getStatusCode().value());
        assertEquals("Which station?", r.getBody().get("question"));
        assertEquals(true, r.getBody().get("clarificationNeeded"));
    }

    @Test
    void llmProblems_mapToTheirOwnStatuses() {
        assertEquals(429, handler.handleLlmUnavailable(new LlmUnavailableException(Kind.RATE_LIMITED, "x")).getStatusCode().value());
        assertEquals(504, handler.handleLlmUnavailable(new LlmUnavailableException(Kind.TIMEOUT, "x")).getStatusCode().value());
        assertEquals(503, handler.handleLlmUnavailable(new LlmUnavailableException(Kind.NOT_CONFIGURED, "x")).getStatusCode().value());
        assertEquals(503, handler.handleLlmUnavailable(new LlmUnavailableException(Kind.CONFIGURATION, "x")).getStatusCode().value());
        assertEquals(503, handler.handleLlmUnavailable(new LlmUnavailableException(Kind.UNAVAILABLE, "x")).getStatusCode().value());
    }

    @Test
    void constraintFailures_stayA404_withFieldsAClientCanActOn() {
        var r = handler.handleConstraintsNotSatisfiable(new ConstraintsNotSatisfiableException(true, "no verified route"));
        assertEquals(404, r.getStatusCode().value());
        assertEquals("no verified route", r.getBody().get("error"));
        assertEquals("CONSTRAINTS_NOT_SATISFIABLE", r.getBody().get("code"));
        assertEquals(true, r.getBody().get("retryWithAllowUnknown"));

        var impossible = handler.handleConstraintsNotSatisfiable(new ConstraintsNotSatisfiableException(false, "none exists"));
        assertEquals(false, impossible.getBody().get("retryWithAllowUnknown"));
    }

    @Test
    void routeNotFoundMessage_containsTheNodeIds_notRawFormatPlaceholders() {
        String msg = new RouteNotFoundException(17, 42).getMessage();
        assertTrue(msg.contains("node 17") && msg.contains("node 42"), msg);
        assertFalse(msg.contains("%"), msg);
    }

    @Test
    void plainRouteNotFound_keepsItsOriginalShape() {
        var r = handler.handleRouteNotFound(new RouteNotFoundException(1, 2));
        assertEquals(404, r.getStatusCode().value());
        assertEquals(java.util.Set.of("error"), r.getBody().keySet());
    }
}
