package com.insightrag.llm;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.insightrag.config.InsightRagProperties;
import com.insightrag.metrics.InsightMetrics;
import com.insightrag.prompt.GroundedPrompt;
import com.insightrag.ratelimit.RateLimiter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * §5.7 / NFR-03: every outbound generation goes through here. Each attempt first takes a token
 * from the aggregate LLM bucket (NFR-04); transient failures are retried up to
 * {@code llm.retry.maxAttempts} with exponential backoff and full jitter, honouring the
 * provider's Retry-After; only after exhaustion does the caller see
 * {@link LlmException.Unavailable} and degrade.
 */
@Component
public class ResilientLlmClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientLlmClient.class);

    private final GenerationProvider provider;
    private final RateLimiter rateLimiter;
    private final InsightMetrics metrics;
    private final InsightRagProperties.Llm.Retry retry;
    private final Sleeper sleeper;

    /** Indirection so tests can observe backoff without waiting. */
    public interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ResilientLlmClient(GenerationProvider provider, RateLimiter rateLimiter, InsightMetrics metrics,
                              InsightRagProperties props) {
        this(provider, rateLimiter, metrics, props.llm().retry(), d -> Thread.sleep(d.toMillis()));
    }

    public ResilientLlmClient(GenerationProvider provider, RateLimiter rateLimiter, InsightMetrics metrics,
                              InsightRagProperties.Llm.Retry retry, Sleeper sleeper) {
        this.provider = provider;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.retry = retry;
        this.sleeper = sleeper;
    }

    public String providerName() {
        return provider.name();
    }

    public Generation generate(GroundedPrompt prompt) {
        return withRetry(prompt, null);
    }

    /**
     * Streaming retries only until the first token reaches the client; after that a retry
     * would duplicate text, so a mid-stream failure is reported as Unavailable.
     */
    public Generation stream(GroundedPrompt prompt, Consumer<String> onDelta) {
        return withRetry(prompt, onDelta);
    }

    private Generation withRetry(GroundedPrompt prompt, Consumer<String> onDelta) {
        LlmException last = null;
        AtomicBoolean emitted = new AtomicBoolean(false);
        for (int attempt = 1; attempt <= retry.maxAttempts(); attempt++) {
            rateLimiter.checkLlm();
            try {
                Generation g = onDelta == null
                        ? provider.generate(prompt)
                        : provider.stream(prompt, d -> {
                            emitted.set(true);
                            onDelta.accept(d);
                        });
                metrics.llmCall(g.refused() ? "refused" : "success");
                return g;
            } catch (LlmException.Permanent e) {
                metrics.llmCall("failure");
                log.error("LLM request rejected permanently: {}", e.getMessage());
                throw new LlmException.Unavailable(e.getMessage(), e);
            } catch (LlmException.Transient e) {
                last = e;
                if (emitted.get()) {
                    metrics.llmCall("failure");
                    throw new LlmException.Unavailable("stream interrupted: " + e.getMessage(), e);
                }
                if (attempt == retry.maxAttempts()) {
                    break;
                }
                Duration wait = backoff(attempt, e.retryAfter());
                if (wait == null) {
                    log.warn("provider asked to retry after {}, beyond the {} cap; degrading", e.retryAfter(), retry.maxRetryAfter());
                    break;
                }
                metrics.llmCall("retry");
                log.warn("LLM attempt {}/{} failed ({}); retrying in {} ms", attempt, retry.maxAttempts(), e.getMessage(), wait.toMillis());
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        metrics.llmCall("failure");
        throw new LlmException.Unavailable("LLM unavailable after retries: " + (last == null ? "" : last.getMessage()), last);
    }

    /** Full jitter: uniform in [0, min(maxDelay, base * 2^(attempt-1))]; Retry-After wins if present. */
    Duration backoff(int attempt, Duration retryAfter) {
        if (retryAfter != null) {
            return retryAfter.compareTo(retry.maxRetryAfter()) > 0 ? null : retryAfter;
        }
        long cap = Math.min(retry.maxDelay().toMillis(), retry.baseDelay().toMillis() << Math.min(attempt - 1, 20));
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(0, Math.max(1, cap) + 1));
    }
}
