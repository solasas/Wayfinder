package com.sashank.map_shortest_path_finder.intent;

import com.sashank.map_shortest_path_finder.exception.LlmUnavailableException.Kind;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/** Fake exceptions named/shaped like the vendor SDK ones, so the test doesn't need to construct real SDK errors. */
class LlmErrorClassifierTest {

    static class RateLimitException extends RuntimeException {
        RateLimitException() { super("secret provider body: you sent: Where is 16.98?"); }
    }
    static class UnauthorizedException extends RuntimeException { }
    static class NoCredentialsException extends RuntimeException { }
    static class SdkStatusException extends RuntimeException {
        private final int status;
        SdkStatusException(int status) { this.status = status; }
        public int statusCode() { return status; }
    }
    /** Shaped like com.google.genai.errors.ApiException: HTTP status via code(), not statusCode(). */
    static class GoogleApiException extends RuntimeException {
        private final int code;
        GoogleApiException(int code, String message) { super(message); this.code = code; }
        public int code() { return code; }
    }
    /** Has a code() that is NOT an HTTP status — must not be misread as one. */
    static class UnrelatedCodeException extends RuntimeException {
        public int code() { return 7; }
    }
    static class SdkIoException extends RuntimeException {
        SdkIoException(Throwable cause) { super(cause); }
    }

    @Test
    void rateLimit_byClassName_andByStatusCode() {
        assertEquals(Kind.RATE_LIMITED, LlmErrorClassifier.classify(new RateLimitException()).getKind());
        assertEquals(Kind.RATE_LIMITED, LlmErrorClassifier.classify(new SdkStatusException(429)).getKind());
    }

    @Test
    void credentials_byClassName_andByStatusCode() {
        assertEquals(Kind.CONFIGURATION, LlmErrorClassifier.classify(new UnauthorizedException()).getKind());
        assertEquals(Kind.CONFIGURATION, LlmErrorClassifier.classify(new NoCredentialsException()).getKind());
        assertEquals(Kind.CONFIGURATION, LlmErrorClassifier.classify(new SdkStatusException(401)).getKind());
        assertEquals(Kind.CONFIGURATION, LlmErrorClassifier.classify(new SdkStatusException(403)).getKind());
    }

    @Test
    void timeouts_areDetectedAnywhereInTheCauseChain() {
        assertEquals(Kind.TIMEOUT, LlmErrorClassifier.classify(new SdkIoException(new SocketTimeoutException("read"))).getKind());
        assertEquals(Kind.TIMEOUT, LlmErrorClassifier.classify(new RuntimeException("wrapped", new SdkStatusException(504))).getKind());
    }

    @Test
    void okHttpCallTimeout_realChainSeenFromTheAnthropicSdk_isATimeout() {
        // AnthropicIoException("Request failed") <- InterruptedIOException("timeout") <- SocketException("Socket closed")
        var chain = new SdkIoException(new java.io.InterruptedIOException("timeout"));
        chain.getCause().initCause(new java.net.SocketException("Socket closed"));
        assertEquals(Kind.TIMEOUT, LlmErrorClassifier.classify(chain).getKind());
    }

    @Test
    void gemini_statusComesFromCode() {
        assertEquals(Kind.RATE_LIMITED, LlmErrorClassifier.classify(new GoogleApiException(429, "quota")).getKind());
        assertEquals(Kind.CONFIGURATION, LlmErrorClassifier.classify(new GoogleApiException(403, "denied")).getKind());
        assertEquals(Kind.TIMEOUT, LlmErrorClassifier.classify(new GoogleApiException(504, "deadline")).getKind());
        assertEquals(Kind.UNAVAILABLE, LlmErrorClassifier.classify(new GoogleApiException(500, "internal")).getKind());
    }

    @Test
    void gemini_invalidApiKey_arrivesAs400_andIsStillAConfigurationProblem() {
        // Google AI Studio answers a bad key with HTTP 400 "API key not valid", not 401
        var bad = new GoogleApiException(400, "API key not valid. Please pass a valid API key.");
        assertEquals(Kind.CONFIGURATION, LlmErrorClassifier.classify(bad).getKind());
        assertEquals(Kind.CONFIGURATION, LlmErrorClassifier.classify(new RuntimeException("wrapped", bad)).getKind());
        // an ordinary 400 is not a credentials problem
        assertEquals(Kind.UNAVAILABLE, LlmErrorClassifier.classify(new GoogleApiException(400, "Invalid JSON payload")).getKind());
    }

    @Test
    void aCodeMethodThatIsNotAnHttpStatus_isIgnored() {
        assertEquals(Kind.UNAVAILABLE, LlmErrorClassifier.classify(new UnrelatedCodeException()).getKind());
    }

    @Test
    void everythingElse_isGenericUnavailable() {
        assertEquals(Kind.UNAVAILABLE, LlmErrorClassifier.classify(new SdkStatusException(500)).getKind());
        assertEquals(Kind.UNAVAILABLE, LlmErrorClassifier.classify(new SdkIoException(new java.net.ConnectException("refused"))).getKind());
        assertEquals(Kind.UNAVAILABLE, LlmErrorClassifier.classify(new RuntimeException()).getKind());
    }

    @Test
    void rateLimitOutranksTheRest_whenSeveralSignalsArePresent() {
        var wrapped = new RuntimeException("x", new SdkIoException(new RateLimitException()));
        assertEquals(Kind.RATE_LIMITED, LlmErrorClassifier.classify(wrapped).getKind());
    }

    @Test
    void messageNeverLeaksProviderDetail() {
        String msg = LlmErrorClassifier.classify(new RateLimitException()).getMessage();
        assertFalse(msg.contains("secret"));
        assertFalse(msg.contains("16.98"));
    }
}
