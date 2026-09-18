package com.insightrag.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenBucketTest {

    @Test
    void allowsBurstUpToCapacityThenRejects() {
        TokenBucket b = new TokenBucket(60, 5, 0);
        for (int i = 0; i < 5; i++) {
            assertThat(b.tryAcquire(0).allowed()).isTrue();
        }
        TokenBucket.Decision d = b.tryAcquire(0);
        assertThat(d.allowed()).isFalse();
        assertThat(d.retryAfterMs()).isEqualTo(1000); // 60/min = one token per second
    }

    @Test
    void refillsContinuouslyAtTheSustainedRate() {
        TokenBucket b = new TokenBucket(60, 1, 0);
        assertThat(b.tryAcquire(0).allowed()).isTrue();
        assertThat(b.tryAcquire(500).allowed()).isFalse();
        assertThat(b.tryAcquire(1000).allowed()).isTrue();
    }

    @Test
    void neverExceedsCapacityAfterIdling() {
        TokenBucket b = new TokenBucket(600, 3, 0);
        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            if (b.tryAcquire(3_600_000).allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).isEqualTo(3);
    }

    @Test
    void noDoubleRateBurstAcrossAWindowBoundary() {
        // A fixed 1-minute window of 60 would allow 60 at 59.9s and 60 more at 60.1s.
        TokenBucket b = new TokenBucket(60, 10, 0);
        int allowed = 0;
        for (long t = 59_900; t <= 60_100; t += 1) {
            if (b.tryAcquire(t).allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).isLessThanOrEqualTo(11);
    }

    @Test
    void retryAfterReflectsDeficit() {
        TokenBucket b = new TokenBucket(30, 1, 0); // one token per 2 s
        b.tryAcquire(0);
        assertThat(b.tryAcquire(500).retryAfterMs()).isEqualTo(1500);
    }
}
