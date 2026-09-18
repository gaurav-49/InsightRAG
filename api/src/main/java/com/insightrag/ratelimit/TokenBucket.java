package com.insightrag.ratelimit;

/**
 * Token-bucket arithmetic (§5.6), shared by the in-process fallback and mirrored line for line
 * by the Redis Lua script. A bucket holds up to {@code capacity} tokens and refills
 * continuously at {@code perMinute / 60 000} tokens per millisecond: bursts up to capacity are
 * allowed, the sustained rate is bounded, and there is no fixed-window boundary to double-dip.
 */
public final class TokenBucket {

    public record Decision(boolean allowed, double remaining, long retryAfterMs) {
    }

    private double tokens;
    private long lastRefillMs;
    private final double capacity;
    private final double ratePerMs;

    public TokenBucket(int perMinute, int capacity, long nowMs) {
        this.capacity = capacity;
        this.ratePerMs = perMinute / 60_000.0;
        this.tokens = capacity;
        this.lastRefillMs = nowMs;
    }

    public synchronized Decision tryAcquire(long nowMs) {
        return tryAcquire(nowMs, 1);
    }

    public synchronized Decision tryAcquire(long nowMs, int cost) {
        long elapsed = Math.max(0, nowMs - lastRefillMs);
        tokens = Math.min(capacity, tokens + elapsed * ratePerMs);
        lastRefillMs = nowMs;
        if (tokens >= cost) {
            tokens -= cost;
            return new Decision(true, tokens, 0);
        }
        long wait = (long) Math.ceil((cost - tokens) / ratePerMs);
        return new Decision(false, tokens, wait);
    }
}
