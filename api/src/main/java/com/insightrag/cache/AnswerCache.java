package com.insightrag.cache;

import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.insightrag.config.InsightRagProperties;
import com.insightrag.metrics.InsightMetrics;
import com.insightrag.query.QueryResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * L1 exact-match cache (§5.5). Redis evicts it LRU under maxmemory (volatile-lru, so only
 * TTL'd cache keys are candidates and the ingestion stream never is), with the TTL as the
 * secondary bound. Redis is an optimisation, never a correctness dependency: every failure is
 * a miss.
 */
@Component
public class AnswerCache {

    private static final Logger log = LoggerFactory.getLogger(AnswerCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final InsightRagProperties.Cache cfg;
    private final InsightMetrics metrics;

    public AnswerCache(StringRedisTemplate redis, ObjectMapper json, InsightRagProperties props, InsightMetrics metrics) {
        this.redis = redis;
        this.json = json;
        this.cfg = props.cache();
        this.metrics = metrics;
    }

    public Optional<QueryResponse> get(String key) {
        if (!cfg.enabled()) {
            return Optional.empty();
        }
        try {
            String v = redis.opsForValue().get(key);
            return v == null ? Optional.empty() : Optional.of(json.readValue(v, QueryResponse.class));
        } catch (JsonProcessingException e) {
            log.warn("discarding unreadable L1 entry: {}", e.getMessage());
            return Optional.empty();
        } catch (RuntimeException e) {
            metrics.cacheError("L1");
            log.debug("L1 unavailable: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void put(String key, QueryResponse response) {
        if (!cfg.enabled()) {
            return;
        }
        try {
            redis.opsForValue().set(key, json.writeValueAsString(response), cfg.l1Ttl());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        } catch (RuntimeException e) {
            metrics.cacheError("L1");
            log.debug("L1 write skipped: {}", e.getMessage());
        }
    }
}
