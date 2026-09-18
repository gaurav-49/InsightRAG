package com.insightrag.config;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * The configuration surface of design doc §9.2, plus the operational knobs around it.
 * Defaults are the documented defaults; the Docker Compose profile overrides the retrieval
 * values with those selected by the evaluation sweep for the active embedding model.
 */
@Validated
@ConfigurationProperties(prefix = "insightrag")
public record InsightRagProperties(
        @Valid @DefaultValue Retrieval retrieval,
        @Valid @DefaultValue Cache cache,
        @Valid @DefaultValue RateLimit ratelimit,
        @Valid @DefaultValue Llm llm,
        @Valid @DefaultValue Embedding embedding,
        @Valid @DefaultValue Upload upload,
        @Valid @DefaultValue Queue queue,
        @Valid @DefaultValue Auth auth,
        @Valid @DefaultValue Eval eval) {

    /** retrieval.topK, retrieval.floor (§9.2) and the relative cutoff selected by the sweep. */
    public record Retrieval(
            @DefaultValue("5") @Min(1) int topK,
            @DefaultValue("0.65") @DecimalMin("0") @DecimalMax("1") double floor,
            // Candidates below relativeFloor x best score are dropped as well (0 disables).
            @DefaultValue("0") @DecimalMin("0") @DecimalMax("1") double relativeFloor,
            @DefaultValue("100") @Min(1) int hnswEfSearch) {
    }

    public record Cache(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("24h") Duration l1Ttl,
            // cache.semantic.threshold (§5.5, §9.2)
            @DefaultValue("0.97") @DecimalMin("0") @DecimalMax("1") double semanticThreshold,
            @DefaultValue("24h") Duration l2Ttl,
            @DefaultValue("2000") @Min(1) int l2MaxEntries) {
    }

    /** Token buckets (§5.6). perMinute is the sustained rate, burst the bucket capacity. */
    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            // ratelimit.tokens.perMinute (§9.2)
            @DefaultValue("60") @Min(1) int queryPerMinute,
            @DefaultValue("20") @Min(1) int queryBurst,
            @DefaultValue("20") @Min(1) int ingestPerMinute,
            @DefaultValue("10") @Min(1) int ingestBurst,
            // Aggregate outbound LLM calls across all clients and API replicas.
            @DefaultValue("600") @Min(1) int llmPerMinute,
            @DefaultValue("60") @Min(1) int llmBurst) {
    }

    public record Llm(
            @DefaultValue("extractive") String provider,
            @DefaultValue("claude-opus-5") String model,
            @DefaultValue("") String baseUrl,
            @DefaultValue("") String apiKey,
            @DefaultValue("16000") @Min(256) long maxTokens,
            // Claude effort level; grounded Q&A over a handful of passages rarely needs more.
            @DefaultValue("low") String effort,
            // Anthropic server-side refusal fallbacks (beta), on by default for claude-opus-5.
            @DefaultValue("true") boolean serverSideFallbacks,
            @DefaultValue("30s") Duration timeout,
            @DefaultValue("3000") @Min(200) int contextBudgetTokens,
            @Valid @DefaultValue Retry retry,
            // USD per million tokens, for the cost-per-query metric (§9.3).
            @DefaultValue("5.00") double inputCostPerMTok,
            @DefaultValue("25.00") double outputCostPerMTok) {

        /** llm.retry.maxAttempts (§9.2) and the backoff shape of §5.7. */
        public record Retry(
                @DefaultValue("3") @Min(1) int maxAttempts,
                @DefaultValue("200ms") Duration baseDelay,
                @DefaultValue("2s") Duration maxDelay,
                // A provider Retry-After longer than this is not waited for; the query degrades.
                @DefaultValue("5s") Duration maxRetryAfter) {
        }
    }

    public record Embedding(
            @DefaultValue("hash") String provider,
            @DefaultValue("") String model,
            @DefaultValue("") String baseUrl,
            @DefaultValue("") String apiKey,
            @DefaultValue("10s") Duration timeout,
            @DefaultValue("3") @Min(1) int maxAttempts) {
    }

    public record Upload(
            @DefaultValue("25MB") DataSize maxSize,
            @DefaultValue("/data/uploads") String blobRoot) {
    }

    public record Queue(
            @DefaultValue("insightrag:ingest") String stream,
            @DefaultValue("insightrag:ingest:dlq") String deadLetterStream,
            @DefaultValue("insightrag-workers") String group,
            @DefaultValue("100000") long maxLength,
            // Outbox sweeper: re-enqueue documents stuck in QUEUED (e.g. Redis was down at upload).
            @DefaultValue("60s") Duration sweepInterval,
            @DefaultValue("10m") Duration requeueAfter) {
    }

    public record Auth(
            // HS256 shared secret, at least 32 bytes.
            @DefaultValue("change-me-change-me-change-me-change-me") String jwtSecret,
            @DefaultValue("insightrag") String issuer,
            // Development convenience: POST /api/v1/auth/dev-token mints tokens. Never enable in production.
            @DefaultValue("false") boolean devTokenEnabled) {
    }

    public record Eval(
            @DefaultValue("0.85") double recallGate,
            @DefaultValue("0.80") double refusalGate,
            @DefaultValue("0.70") double precisionGate,
            // Reported with each run so results are attributable to a chunking configuration.
            @DefaultValue("512") int chunkSizeTokens,
            @DefaultValue("64") int chunkOverlapTokens) {
    }
}
