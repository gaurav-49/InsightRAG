package com.insightrag.health;

import java.util.LinkedHashMap;
import java.util.Map;

import com.insightrag.corpus.CorpusState;
import com.insightrag.corpus.CorpusStateRepository;
import com.insightrag.document.IngestionQueue;
import com.insightrag.embedding.EmbeddingProvider;
import com.insightrag.llm.ResilientLlmClient;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness plus dependency status (§6.2). Postgres down is DOWN (503): nothing can be answered
 * without the vector store. Redis down is DEGRADED (200): caching and queueing pause but
 * queries are still answered (§5.7, "Redis is an optimisation, never a dependency for
 * correctness").
 */
@RestController
public class HealthController {

    private final CorpusStateRepository corpus;
    private final StringRedisTemplate redis;
    private final IngestionQueue queue;
    private final EmbeddingProvider embeddings;
    private final ResilientLlmClient llm;

    public HealthController(CorpusStateRepository corpus, StringRedisTemplate redis, IngestionQueue queue,
                            EmbeddingProvider embeddings, ResilientLlmClient llm) {
        this.corpus = corpus;
        this.redis = redis;
        this.queue = queue;
        this.embeddings = embeddings;
        this.llm = llm;
    }

    @GetMapping("/api/v1/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> deps = new LinkedHashMap<>();
        boolean dbUp;
        try {
            CorpusState s = corpus.current();
            boolean modelOk = s.embeddingModel() == null || s.embeddingModel().equals(embeddings.modelId());
            deps.put("postgres", Map.of("status", "UP", "corpusVersion", s.version(),
                    "embeddingModel", s.embeddingModel() == null ? "none" : s.embeddingModel(),
                    "embeddingModelMatches", modelOk));
            dbUp = modelOk;
        } catch (RuntimeException e) {
            deps.put("postgres", Map.of("status", "DOWN", "error", e.getClass().getSimpleName()));
            dbUp = false;
        }
        boolean redisUp;
        try {
            String pong = redis.execute((RedisCallback<String>) c -> c.ping());
            IngestionQueue.Depth d = queue.depth();
            deps.put("redis", Map.of("status", "PONG".equalsIgnoreCase(pong) ? "UP" : "DOWN",
                    "queueLag", d.lag(), "queuePending", d.pending(), "deadLetters", d.deadLetters()));
            redisUp = "PONG".equalsIgnoreCase(pong);
        } catch (RuntimeException e) {
            deps.put("redis", Map.of("status", "DOWN", "error", e.getClass().getSimpleName()));
            redisUp = false;
        }
        deps.put("embedding", Map.of("model", embeddings.modelId()));
        deps.put("llm", Map.of("provider", llm.providerName()));

        String status = !dbUp ? "DOWN" : redisUp ? "UP" : "DEGRADED";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("dependencies", deps);
        return ResponseEntity.status(dbUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
