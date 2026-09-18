package com.insightrag.metrics;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import com.insightrag.document.IngestionQueue;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The operator metrics of design doc §9.3, exposed at /api/v1/metrics in Prometheus format.
 * Cache hit rate by tier and NO_ANSWER rate are ratios of {@code insightrag_queries_total}
 * series; cost per answered query is {@code insightrag_llm_cost_usd_total} over answered MISS
 * queries.
 */
@Component
public class InsightMetrics {

    private static final Logger log = LoggerFactory.getLogger(InsightMetrics.class);

    private final MeterRegistry registry;
    private final IngestionQueue queue;
    private final DistributionSummary topScore;
    private final Counter promptTokens;
    private final Counter completionTokens;
    private final Counter costUsd;
    private final AtomicLong queueLag = new AtomicLong();
    private final AtomicLong queuePending = new AtomicLong();
    private final AtomicLong deadLetters = new AtomicLong();

    public InsightMetrics(MeterRegistry registry, IngestionQueue queue) {
        this.registry = registry;
        this.queue = queue;
        this.topScore = DistributionSummary.builder("insightrag.retrieval.top.score")
                .description("Similarity of the best retrieved passage per query (drift = corpus/query divergence)")
                .serviceLevelObjectives(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.65, 0.7, 0.8, 0.9)
                .register(registry);
        this.promptTokens = Counter.builder("insightrag.llm.tokens").tag("type", "prompt").register(registry);
        this.completionTokens = Counter.builder("insightrag.llm.tokens").tag("type", "completion").register(registry);
        this.costUsd = Counter.builder("insightrag.llm.cost.usd").description("Estimated LLM spend").register(registry);
        Gauge.builder("insightrag.ingest.queue.lag", queueLag, AtomicLong::get)
                .description("Ingestion jobs not yet delivered to a worker").register(registry);
        Gauge.builder("insightrag.ingest.queue.pending", queuePending, AtomicLong::get)
                .description("Ingestion jobs delivered but not acknowledged").register(registry);
        Gauge.builder("insightrag.ingest.dead.letters", deadLetters, AtomicLong::get)
                .description("Jobs in the dead-letter stream").register(registry);
    }

    public void query(String tier, String outcome, Duration latency) {
        Counter.builder("insightrag.queries").tag("tier", tier).tag("outcome", outcome).register(registry).increment();
        Timer.builder("insightrag.query.latency").tag("tier", tier).publishPercentileHistogram()
                .register(registry).record(latency);
    }

    public void topScore(double score) {
        topScore.record(score);
    }

    public void llmUsage(long prompt, long completion, double cost) {
        promptTokens.increment(prompt);
        completionTokens.increment(completion);
        costUsd.increment(cost);
    }

    /** outcome: success | retry | failure | rate_limited | refused */
    public void llmCall(String outcome) {
        Counter.builder("insightrag.llm.calls").tag("outcome", outcome).register(registry).increment();
    }

    public void embeddingCall() {
        Counter.builder("insightrag.embedding.calls").register(registry).increment();
    }

    public void cacheError(String tier) {
        Counter.builder("insightrag.cache.errors").tag("tier", tier).register(registry).increment();
    }

    public void upload(String result) {
        Counter.builder("insightrag.uploads").tag("result", result).register(registry).increment();
    }

    public void rateLimited(String scope) {
        Counter.builder("insightrag.ratelimit.rejections").tag("scope", scope).register(registry).increment();
    }

    @Scheduled(fixedDelayString = "${insightrag.metrics.queue-refresh:10s}")
    void refreshQueueDepth() {
        try {
            IngestionQueue.Depth d = queue.depth();
            queueLag.set(d.lag());
            queuePending.set(d.pending());
            deadLetters.set(d.deadLetters());
        } catch (RuntimeException e) {
            log.debug("queue depth unavailable: {}", e.getMessage());
        }
    }
}
