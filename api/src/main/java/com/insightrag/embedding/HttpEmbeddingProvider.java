package com.insightrag.embedding;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.insightrag.common.ApiException;

/**
 * Remote embedding providers (Ollama, OpenAI). Transient failures (timeouts, 429, 5xx) are
 * retried with exponential backoff and jitter; exhaustion surfaces as 503 because a query
 * cannot be answered without its embedding.
 */
public final class HttpEmbeddingProvider implements EmbeddingProvider {

    public enum Kind { OLLAMA, OPENAI }

    private final Kind kind;
    private final String model;
    private final URI endpoint;
    private final String apiKey;
    private final Duration timeout;
    private final int maxAttempts;
    private final HttpClient http;
    private final ObjectMapper json;

    public HttpEmbeddingProvider(Kind kind, String baseUrl, String model, String apiKey, Duration timeout,
                                 int maxAttempts, ObjectMapper json) {
        this.kind = kind;
        this.model = model;
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.maxAttempts = maxAttempts;
        this.json = json;
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.endpoint = URI.create(base + (kind == Kind.OLLAMA ? "/api/embed" : "/v1/embeddings"));
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public static HttpEmbeddingProvider ollama(String baseUrl, String model, Duration timeout, int attempts, ObjectMapper json) {
        return new HttpEmbeddingProvider(Kind.OLLAMA, baseUrl.isBlank() ? "http://ollama:11434" : baseUrl,
                model.isBlank() ? "nomic-embed-text" : model, "", timeout, attempts, json);
    }

    public static HttpEmbeddingProvider openAi(String baseUrl, String model, String apiKey, Duration timeout, int attempts,
                                               ObjectMapper json) {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("insightrag.embedding.api-key is required for the openai provider");
        }
        return new HttpEmbeddingProvider(Kind.OPENAI, baseUrl.isBlank() ? "https://api.openai.com" : baseUrl,
                model.isBlank() ? "text-embedding-3-small" : model, apiKey, timeout, attempts, json);
    }

    @Override
    public String modelId() {
        return (kind == Kind.OLLAMA ? "ollama:" : "openai:") + model;
    }

    @Override
    public double[] embed(String normalizedText) {
        byte[] body;
        try {
            body = json.writeValueAsBytes(Map.of("model", model, "input", java.util.List.of(normalizedText)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        HttpRequest.Builder req = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (!apiKey.isBlank()) {
            req.header("Authorization", "Bearer " + apiKey);
        }
        for (int attempt = 1; ; attempt++) {
            try {
                HttpResponse<byte[]> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
                int status = resp.statusCode();
                if (status == 429 || status >= 500) {
                    throw new IOException("embedding provider returned HTTP " + status);
                }
                if (status >= 400) {
                    throw ApiException.unavailable("embedding_rejected", "Embedding provider rejected the request (HTTP " + status + ")");
                }
                return EmbeddingText.pad(parse(json.readTree(resp.body())));
            } catch (HttpTimeoutException e) {
                if (attempt >= maxAttempts) {
                    throw ApiException.unavailable("embedding_unavailable", "Embedding provider timed out");
                }
            } catch (IOException e) {
                if (attempt >= maxAttempts) {
                    throw ApiException.unavailable("embedding_unavailable", "Embedding provider unavailable: " + e.getMessage());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ApiException.unavailable("embedding_unavailable", "Interrupted");
            }
            sleep(ThreadLocalRandom.current().nextLong(1, 100L << Math.min(attempt, 5)));
        }
    }

    private double[] parse(JsonNode root) {
        JsonNode vec = kind == Kind.OLLAMA ? root.path("embeddings").path(0) : root.path("data").path(0).path("embedding");
        if (!vec.isArray() || vec.isEmpty()) {
            throw ApiException.unavailable("embedding_malformed", "Embedding provider returned no vector");
        }
        double[] out = new double[vec.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = vec.get(i).asDouble();
        }
        return out;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
