package com.insightrag.common;

import org.springframework.http.HttpStatus;

/** §5.6: exhaustion is rejected with 429 and Retry-After, never silently queued. */
public class RateLimitedException extends ApiException {

    private final long retryAfterSeconds;

    public RateLimitedException(String scope, long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                "Rate limit exceeded for " + scope + "; retry after " + retryAfterSeconds + "s");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
