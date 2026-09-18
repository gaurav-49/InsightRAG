package com.insightrag.llm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.insightrag.config.InsightRagProperties;
import com.insightrag.prompt.GroundedPrompt;

/** Local model via Ollama's /api/chat, the development fallback of §3.3 (no token spend). */
public class OllamaGenerationProvider implements GenerationProvider {

    private final HttpClient http;
    private final ObjectMapper json;
    private final URI endpoint;
    private final String model;
    private final InsightRagProperties.Llm cfg;

    public OllamaGenerationProvider(InsightRagProperties.Llm cfg, ObjectMapper json) {
        this.cfg = cfg;
        this.json = json;
        String base = cfg.baseUrl().isBlank() ? "http://ollama:11434" : cfg.baseUrl().replaceAll("/+$", "");
        this.endpoint = URI.create(base + "/api/chat");
        this.model = cfg.model().startsWith("claude") ? "llama3.2" : cfg.model();
        this.http = HttpClient.newBuilder().connectTimeout(cfg.timeout()).build();
    }

    @Override
    public String name() {
        return "ollama:" + model;
    }

    private HttpRequest request(GroundedPrompt p, boolean stream) {
        Map<String, Object> body = Map.of(
                "model", model,
                "stream", stream,
                "options", Map.of("temperature", 0),
                "messages", List.of(
                        Map.of("role", "system", "content", p.system()),
                        Map.of("role", "user", "content", p.user())));
        try {
            return HttpRequest.newBuilder(endpoint).timeout(cfg.timeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
        } catch (IOException e) {
            throw new LlmException.Permanent("could not encode request", e);
        }
    }

    @Override
    public Generation generate(GroundedPrompt prompt) {
        return stream(prompt, d -> { });
    }

    @Override
    public Generation stream(GroundedPrompt prompt, Consumer<String> onDelta) {
        try {
            HttpResponse<InputStream> resp = http.send(request(prompt, true), HttpResponse.BodyHandlers.ofInputStream());
            int status = resp.statusCode();
            if (status == 429 || status >= 500) {
                resp.body().close();
                throw new LlmException.Transient("ollama returned " + status, null, null);
            }
            if (status >= 400) {
                resp.body().close();
                throw new LlmException.Permanent("ollama rejected request (" + status + ")", null);
            }
            StringBuilder text = new StringBuilder();
            long prompted = 0;
            long completed = 0;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode node = json.readTree(line);
                    String delta = node.path("message").path("content").asText("");
                    if (!delta.isEmpty()) {
                        text.append(delta);
                        onDelta.accept(delta);
                    }
                    if (node.path("done").asBoolean(false)) {
                        prompted = node.path("prompt_eval_count").asLong(0);
                        completed = node.path("eval_count").asLong(0);
                    }
                }
            }
            return new Generation(text.toString().strip(), prompted, completed, false);
        } catch (HttpTimeoutException e) {
            throw new LlmException.Transient("ollama timed out", null, e);
        } catch (IOException e) {
            throw new LlmException.Transient("ollama I/O failure", null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException.Transient("interrupted", null, e);
        }
    }
}
