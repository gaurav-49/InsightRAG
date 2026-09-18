package com.insightrag.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import com.insightrag.config.InsightRagProperties;
import com.insightrag.metrics.InsightMetrics;
import com.insightrag.prompt.GroundedPrompt;
import com.insightrag.ratelimit.RateLimiter;

import org.junit.jupiter.api.Test;

class ResilientLlmClientTest {

    static final GroundedPrompt PROMPT = new GroundedPrompt("sys", "CONTEXT:\n[1] x\n\nQUESTION: q", List.of(), 10);
    static final InsightRagProperties.Llm.Retry RETRY = new InsightRagProperties.Llm.Retry(3, Duration.ofMillis(200),
            Duration.ofSeconds(2), Duration.ofSeconds(5));

    /** Scripted provider: each call pops the next behaviour. */
    static class Scripted implements GenerationProvider {
        final Deque<Object> script = new ArrayDeque<>();
        int calls;

        Scripted then(Object o) {
            script.add(o);
            return this;
        }

        @Override
        public String name() {
            return "scripted";
        }

        @Override
        public Generation generate(GroundedPrompt p) {
            return stream(p, d -> { });
        }

        @Override
        public Generation stream(GroundedPrompt p, Consumer<String> onDelta) {
            calls++;
            Object next = script.pop();
            if (next instanceof String partial) {
                onDelta.accept(partial);
                throw new LlmException.Transient("dropped mid-stream", null, null);
            }
            if (next instanceof RuntimeException e) {
                throw e;
            }
            onDelta.accept(((Generation) next).text());
            return (Generation) next;
        }
    }

    final List<Duration> sleeps = new ArrayList<>();

    ResilientLlmClient client(GenerationProvider p) {
        return new ResilientLlmClient(p, mock(RateLimiter.class), mock(InsightMetrics.class), RETRY, sleeps::add);
    }

    static LlmException.Transient transientErr(Duration retryAfter) {
        return new LlmException.Transient("503", retryAfter, null);
    }

    @Test
    void singleTransientFailureIsInvisibleToTheCaller() {
        Scripted p = new Scripted().then(transientErr(null)).then(new Generation("ok [1]", 10, 2, false));
        assertThat(client(p).generate(PROMPT).text()).isEqualTo("ok [1]");
        assertThat(p.calls).isEqualTo(2);
        assertThat(sleeps).hasSize(1);
        assertThat(sleeps.get(0)).isBetween(Duration.ZERO, Duration.ofMillis(200));
    }

    @Test
    void backoffGrowsExponentiallyWithinCaps() {
        ResilientLlmClient c = client(new Scripted());
        for (int i = 0; i < 200; i++) {
            assertThat(c.backoff(1, null)).isBetween(Duration.ZERO, Duration.ofMillis(200));
            assertThat(c.backoff(2, null)).isBetween(Duration.ZERO, Duration.ofMillis(400));
            assertThat(c.backoff(10, null)).isBetween(Duration.ZERO, Duration.ofSeconds(2));
        }
    }

    @Test
    void honoursProviderRetryAfter() {
        Scripted p = new Scripted().then(transientErr(Duration.ofMillis(1500))).then(new Generation("ok", 1, 1, false));
        client(p).generate(PROMPT);
        assertThat(sleeps).containsExactly(Duration.ofMillis(1500));
    }

    @Test
    void retryAfterBeyondTheCapDegradesImmediately() {
        Scripted p = new Scripted().then(transientErr(Duration.ofMinutes(5)));
        assertThatThrownBy(() -> client(p).generate(PROMPT)).isInstanceOf(LlmException.Unavailable.class);
        assertThat(p.calls).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void exhaustionAfterMaxAttemptsSurfacesAsUnavailable() {
        Scripted p = new Scripted().then(transientErr(null)).then(transientErr(null)).then(transientErr(null));
        assertThatThrownBy(() -> client(p).generate(PROMPT)).isInstanceOf(LlmException.Unavailable.class);
        assertThat(p.calls).isEqualTo(3);
        assertThat(sleeps).hasSize(2);
    }

    @Test
    void permanentErrorsAreNotRetried() {
        Scripted p = new Scripted().then(new LlmException.Permanent("401", null));
        assertThatThrownBy(() -> client(p).generate(PROMPT)).isInstanceOf(LlmException.Unavailable.class);
        assertThat(p.calls).isEqualTo(1);
    }

    @Test
    void streamRetriesBeforeFirstTokenButNotAfter() {
        Scripted before = new Scripted().then(transientErr(null)).then(new Generation("hello", 1, 1, false));
        List<String> got = new ArrayList<>();
        assertThat(client(before).stream(PROMPT, got::add).text()).isEqualTo("hello");
        assertThat(got).containsExactly("hello");

        Scripted after = new Scripted().then("partial ").then(new Generation("never", 1, 1, false));
        assertThatThrownBy(() -> client(after).stream(PROMPT, d -> { })).isInstanceOf(LlmException.Unavailable.class);
        assertThat(after.calls).isEqualTo(1);
    }

    @Test
    void everyAttemptTakesAnLlmRateLimitToken() {
        RateLimiter limiter = mock(RateLimiter.class);
        Scripted p = new Scripted().then(transientErr(null)).then(new Generation("ok", 1, 1, false));
        new ResilientLlmClient(p, limiter, mock(InsightMetrics.class), RETRY, sleeps::add).generate(PROMPT);
        org.mockito.Mockito.verify(limiter, org.mockito.Mockito.times(2)).checkLlm();
    }
}
