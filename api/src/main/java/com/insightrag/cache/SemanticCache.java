package com.insightrag.cache;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.insightrag.config.InsightRagProperties;
import com.insightrag.metrics.InsightMetrics;
import com.insightrag.query.QueryResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * L2 semantic near-duplicate cache (§5.5). Recent query embeddings are retained per scope
 * (filters + corpus version + config); an incoming embedding with cosine similarity at or
 * above the threshold (0.97 by default, deliberately conservative) is treated as the same
 * question and served without a generation call.
 *
 * <p>Layout in Redis, shared by every API replica:
 * <ul>
 *   <li>{@code insightrag:l2:<scope>:ids} — list of entry ids, newest first, trimmed to
 *       {@code l2MaxEntries}, with a TTL;</li>
 *   <li>{@code insightrag:l2:e:<id>} — hash with the float32 vector and the serialised answer,
 *       with a TTL.</li>
 * </ul>
 * Each replica mirrors the vectors it has already seen in memory and fetches only new ones,
 * so a lookup costs one LRANGE plus the brute-force dot products (a few thousand 1536-d dot
 * products is well under a millisecond), not a transfer of every vector.
 */
@Component
public class SemanticCache {

    private static final Logger log = LoggerFactory.getLogger(SemanticCache.class);
    private static final int MAX_SCOPES_MIRRORED = 8;

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final InsightRagProperties.Cache cfg;
    private final InsightMetrics metrics;
    // scope -> (entry id -> unit vector); access-ordered so stale scopes fall out
    private final Map<String, Map<String, float[]>> mirror = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<String, float[]>> e) {
                    return size() > MAX_SCOPES_MIRRORED;
                }
            });

    public SemanticCache(StringRedisTemplate redis, ObjectMapper json, InsightRagProperties props, InsightMetrics metrics) {
        this.redis = redis;
        this.json = json;
        this.cfg = props.cache();
        this.metrics = metrics;
    }

    public record Hit(QueryResponse response, double similarity) {
    }

    public Optional<Hit> lookup(String scope, double[] queryVector) {
        if (!cfg.enabled()) {
            return Optional.empty();
        }
        try {
            float[] q = unit(queryVector);
            List<String> ids = redis.opsForList().range(idsKey(scope), 0, cfg.l2MaxEntries() - 1);
            if (ids == null || ids.isEmpty()) {
                return Optional.empty();
            }
            Map<String, float[]> local = mirror.computeIfAbsent(scope, s -> new ConcurrentHashMap<>());
            List<String> missing = ids.stream().filter(id -> !local.containsKey(id)).toList();
            if (!missing.isEmpty()) {
                fetchVectors(missing).forEach(local::put);
            }
            Set<String> live = new HashSet<>(ids);
            local.keySet().retainAll(live);

            String bestId = null;
            double best = -1;
            for (String id : ids) {
                float[] v = local.get(id);
                if (v == null) {
                    continue;
                }
                double s = dot(q, v);
                if (s > best) {
                    best = s;
                    bestId = id;
                }
            }
            if (bestId == null || best < cfg.semanticThreshold()) {
                return Optional.empty();
            }
            Object answer = redis.opsForHash().get(entryKey(bestId), "answer");
            if (answer == null) {
                local.remove(bestId); // expired between LRANGE and HGET
                return Optional.empty();
            }
            return Optional.of(new Hit(json.readValue(answer.toString(), QueryResponse.class), best));
        } catch (JsonProcessingException e) {
            log.warn("discarding unreadable L2 entry: {}", e.getMessage());
            return Optional.empty();
        } catch (RuntimeException e) {
            metrics.cacheError("L2");
            log.debug("L2 unavailable: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void put(String scope, double[] queryVector, QueryResponse response) {
        if (!cfg.enabled()) {
            return;
        }
        try {
            String id = UUID.randomUUID().toString();
            float[] v = unit(queryVector);
            String entry = entryKey(id);
            String idsKey = idsKey(scope);
            String answer = json.writeValueAsString(response);
            String encoded = encode(v);
            Duration ttl = cfg.l2Ttl();
            redis.executePipelined((RedisCallback<Object>) conn -> {
                byte[] ek = entry.getBytes(StandardCharsets.UTF_8);
                byte[] ik = idsKey.getBytes(StandardCharsets.UTF_8);
                conn.hashCommands().hSet(ek, "vec".getBytes(StandardCharsets.UTF_8), encoded.getBytes(StandardCharsets.UTF_8));
                conn.hashCommands().hSet(ek, "answer".getBytes(StandardCharsets.UTF_8), answer.getBytes(StandardCharsets.UTF_8));
                conn.keyCommands().expire(ek, ttl.toSeconds());
                conn.listCommands().lPush(ik, id.getBytes(StandardCharsets.UTF_8));
                conn.listCommands().lTrim(ik, 0, cfg.l2MaxEntries() - 1);
                conn.keyCommands().expire(ik, ttl.toSeconds());
                return null;
            });
            mirror.computeIfAbsent(scope, s -> new ConcurrentHashMap<>()).put(id, v);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        } catch (RuntimeException e) {
            metrics.cacheError("L2");
            log.debug("L2 write skipped: {}", e.getMessage());
        }
    }

    private Map<String, float[]> fetchVectors(List<String> ids) {
        List<Object> raw = redis.executePipelined((RedisCallback<Object>) conn -> {
            for (String id : ids) {
                conn.hashCommands().hGet(entryKey(id).getBytes(StandardCharsets.UTF_8), "vec".getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });
        Map<String, float[]> out = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            Object v = i < raw.size() ? raw.get(i) : null;
            if (v != null) {
                out.put(ids.get(i), decode(v.toString()));
            }
        }
        return out;
    }

    static String idsKey(String scope) {
        return CacheKeys.L2_PREFIX + scope + ":ids";
    }

    static String entryKey(String id) {
        return CacheKeys.L2_PREFIX + "e:" + id;
    }

    static float[] unit(double[] v) {
        double n = 0;
        for (double x : v) {
            n += x * x;
        }
        n = Math.sqrt(n);
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (n == 0 ? 0 : v[i] / n);
        }
        return out;
    }

    static double dot(float[] a, float[] b) {
        double s = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    static String encode(float[] v) {
        ByteBuffer buf = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v) {
            buf.putFloat(f);
        }
        return Base64.getEncoder().encodeToString(buf.array());
    }

    static float[] decode(String s) {
        ByteBuffer buf = ByteBuffer.wrap(Base64.getDecoder().decode(s)).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[buf.remaining() / 4];
        for (int i = 0; i < out.length; i++) {
            out[i] = buf.getFloat();
        }
        return out;
    }

    /** Test hook. */
    void clearMirror() {
        mirror.clear();
    }

    List<String> mirroredScopes() {
        return new ArrayList<>(mirror.keySet());
    }
}
