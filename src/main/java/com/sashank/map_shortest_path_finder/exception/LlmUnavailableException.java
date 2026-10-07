package com.sashank.map_shortest_path_finder.exception;

/**
 * The language model could not be used for this request. A subclass of IllegalStateException
 * so anything that only knows the generic "503 service unavailable" mapping still behaves;
 * GlobalExceptionHandler maps each {@link Kind} to a more specific status.
 *
 * Messages are deliberately generic: provider error bodies, keys and request text are never
 * included in what reaches the client.
 */
public class LlmUnavailableException extends IllegalStateException {

    public enum Kind {
        /** No provider/credentials configured. */
        NOT_CONFIGURED(503),
        /** Provider rejected our credentials or permissions. */
        CONFIGURATION(503),
        /** Provider rate limit hit (429). */
        RATE_LIMITED(429),
        /** Provider didn't answer in time. */
        TIMEOUT(504),
        /** Anything else: 5xx, network failure, malformed provider reply. */
        UNAVAILABLE(503);

        private final int httpStatus;
        Kind(int httpStatus) { this.httpStatus = httpStatus; }
        public int httpStatus() { return httpStatus; }
    }

    private final Kind kind;

    public LlmUnavailableException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind getKind() { return kind; }
}
