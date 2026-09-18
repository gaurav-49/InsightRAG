package com.insightrag.llm;

import java.time.Duration;

public abstract class LlmException extends RuntimeException {

    protected LlmException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Timeouts, 429, 5xx, connection failures, malformed responses: retry with backoff. */
    public static class Transient extends LlmException {
        private final Duration retryAfter;

        public Transient(String message, Duration retryAfter, Throwable cause) {
            super(message, cause);
            this.retryAfter = retryAfter;
        }

        /** Provider-requested delay (Retry-After), or null. */
        public Duration retryAfter() {
            return retryAfter;
        }
    }

    /** Authentication, invalid request, unknown model: retrying cannot help. */
    public static class Permanent extends LlmException {
        public Permanent(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Retries exhausted or permanent failure: the caller degrades rather than erroring. */
    public static class Unavailable extends LlmException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
