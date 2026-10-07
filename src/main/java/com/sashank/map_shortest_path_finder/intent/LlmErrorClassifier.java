package com.sashank.map_shortest_path_finder.intent;

import com.sashank.map_shortest_path_finder.exception.LlmUnavailableException;
import com.sashank.map_shortest_path_finder.exception.LlmUnavailableException.Kind;

import java.lang.reflect.Method;
import java.io.InterruptedIOException;
import java.util.concurrent.TimeoutException;

/**
 * Maps whatever a provider call throws onto our small {@link Kind} set, provider-agnostically.
 * Spring AI wraps vendor SDK exceptions, so the whole cause chain is inspected. A status code
 * is read from the exception when it exposes one (both the Anthropic and OpenAI SDKs do);
 * otherwise the exception class name is used. When several signals are present the most
 * actionable wins: rate limit, then credentials, then timeout, then generic.
 *
 * Only the Kind is returned to callers — never the provider's message, which could contain
 * request fragments or account details.
 */
final class LlmErrorClassifier {

    private static final int MAX_DEPTH = 10;

    private LlmErrorClassifier() {}

    static LlmUnavailableException classify(Throwable error) {
        boolean rateLimited = false, credentials = false, timeout = false;
        Throwable t = error;
        for (int depth = 0; t != null && depth < MAX_DEPTH; depth++, t = t.getCause()) {
            String name = t.getClass().getSimpleName();
            Integer status = statusOf(t);

            if ((status != null && status == 429) || name.contains("RateLimit")) rateLimited = true;
            if ((status != null && (status == 401 || status == 403))
                    || name.contains("Unauthorized") || name.contains("PermissionDenied")
                    || name.contains("Authentication") || name.contains("NoCredentials")
                    || name.contains("CredentialResolution") || invalidApiKeyMessage(t)) credentials = true;
            if ((status != null && (status == 408 || status == 504))
                    || t instanceof InterruptedIOException // incl. SocketTimeoutException; OkHttp (inside the SDKs) reports call timeouts as a bare InterruptedIOException("timeout")
                    || t instanceof TimeoutException
                    || name.contains("Timeout")) timeout = true;
        }
        if (rateLimited) {
            return new LlmUnavailableException(Kind.RATE_LIMITED,
                "The language model is rate-limited right now. Please retry in a few seconds.");
        }
        if (credentials) {
            return new LlmUnavailableException(Kind.CONFIGURATION,
                "The language model is not configured correctly (credentials missing or rejected). Contact the administrator.");
        }
        if (timeout) {
            return new LlmUnavailableException(Kind.TIMEOUT,
                "The language model took too long to respond. Please try again.");
        }
        return new LlmUnavailableException(Kind.UNAVAILABLE,
            "The language model is unavailable right now. Please try again.");
    }

    /**
     * HTTP status exposed by vendor SDK exceptions: statusCode() (Anthropic, OpenAI) or code() (Google GenAI).
     * Only values that look like HTTP statuses are accepted, since code() is a common method name.
     */
    private static Integer statusOf(Throwable t) {
        for (String accessor : new String[]{"statusCode", "code"}) {
            try {
                Method m = t.getClass().getMethod(accessor);
                Object v = m.invoke(t);
                if (v instanceof Integer i && i >= 100 && i <= 599) return i;
            } catch (ReflectiveOperationException | RuntimeException e) {
                // no such accessor on this exception — try the next
            }
        }
        return null;
    }

    /**
     * Google AI Studio reports an invalid or missing API key as HTTP 400 (not 401): "API key not valid..." /
     * reason API_KEY_INVALID. Recognised from the message, which is used only for classification and never returned.
     */
    private static boolean invalidApiKeyMessage(Throwable t) {
        String m = t.getMessage();
        return m != null && (m.contains("API key not valid") || m.contains("API_KEY_INVALID") || m.contains("API key is missing"));
    }
}
