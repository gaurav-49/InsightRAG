package com.insightrag.ratelimit;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.insightrag.common.RateLimitedException;
import com.insightrag.config.InsightRagProperties;
import com.insightrag.metrics.InsightMetrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Distributed token buckets (§5.6). State lives in Redis so the limit holds across API
 * replicas; the script uses Redis' own clock so replicas with skewed clocks agree. Per-client
 * buckets guard the query and ingestion endpoints; one aggregate bucket guards outbound LLM
 * calls so a single client cannot exhaust the shared provider quota.
 *
 * If Redis is unreachable the limiter degrades to per-instance buckets rather than failing
 * open or closed: the bound becomes per-replica instead of global, which is the smallest loss.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    static final String LUA = """
            local key = KEYS[1]
            local rate = tonumber(ARGV[1]) / 60000.0
            local capacity = tonumber(ARGV[2])
            local cost = tonumber(ARGV[3])
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local state = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(state[1])
            local ts = tonumber(state[2])
            if tokens == nil then tokens = capacity; ts = now end
            local elapsed = now - ts
            if elapsed < 0 then elapsed = 0 end
            tokens = math.min(capacity, tokens + elapsed * rate)
            local allowed = 0
            local wait = 0
            if tokens >= cost then
              tokens = tokens - cost
              allowed = 1
            else
              wait = math.ceil((cost - tokens) / rate)
            end
            redis.call('HSET', key, 'tokens', tostring(tokens), 'ts', tostring(now))
            redis.call('PEXPIRE', key, math.ceil(capacity / rate) * 2 + 1000)
            return {allowed, wait}
            """;

    private static final RedisScript<List> SCRIPT = new DefaultRedisScript<>(LUA, List.class);

    private final StringRedisTemplate redis;
    private final InsightRagProperties.RateLimit cfg;
    private final InsightMetrics metrics;
    private final Map<String, TokenBucket> local = new ConcurrentHashMap<>();

    public RateLimiter(StringRedisTemplate redis, InsightRagProperties props, InsightMetrics metrics) {
        this.redis = redis;
        this.cfg = props.ratelimit();
        this.metrics = metrics;
    }

    public void checkQuery(String clientId) {
        enforce("query", "insightrag:rl:query:" + clientId, cfg.queryPerMinute(), cfg.queryBurst());
    }

    public void checkIngest(String clientId) {
        enforce("ingest", "insightrag:rl:ingest:" + clientId, cfg.ingestPerMinute(), cfg.ingestBurst());
    }

    /** Aggregate outbound LLM budget: shed load locally before the provider rejects it (§5.7). */
    public void checkLlm() {
        enforce("llm", "insightrag:rl:llm", cfg.llmPerMinute(), cfg.llmBurst());
    }

    private void enforce(String scope, String key, int perMinute, int burst) {
        if (!cfg.enabled()) {
            return;
        }
        TokenBucket.Decision d = acquire(key, perMinute, burst);
        if (!d.allowed()) {
            metrics.rateLimited(scope);
            throw new RateLimitedException(scope, (d.retryAfterMs() + 999) / 1000);
        }
    }

    TokenBucket.Decision acquire(String key, int perMinute, int burst) {
        try {
            List<?> r = redis.execute(SCRIPT, List.of(key), Integer.toString(perMinute), Integer.toString(burst), "1");
            if (r != null && r.size() == 2) {
                boolean allowed = ((Number) r.get(0)).longValue() == 1;
                return new TokenBucket.Decision(allowed, -1, ((Number) r.get(1)).longValue());
            }
        } catch (RuntimeException e) {
            log.debug("redis rate limiter unavailable, using local bucket: {}", e.getMessage());
            metrics.cacheError("ratelimit");
        }
        return local.computeIfAbsent(key, k -> new TokenBucket(perMinute, burst, System.currentTimeMillis()))
                .tryAcquire(System.currentTimeMillis());
    }
}
