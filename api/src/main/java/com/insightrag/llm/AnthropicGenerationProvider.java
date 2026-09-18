package com.insightrag.llm;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;

import com.insightrag.config.InsightRagProperties;
import com.insightrag.prompt.GroundedPrompt;

/**
 * Claude via the official Anthropic Java SDK. The SDK's own retries are disabled
 * ({@code maxRetries(0)}): retry, backoff and the shared rate limit live in
 * {@link ResilientLlmClient} so they are identical for every provider and observable in our
 * metrics. The base URL is configurable, which is also how the WireMock contract tests stand in
 * for the API.
 */
public class AnthropicGenerationProvider implements GenerationProvider {

    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final AnthropicClient client;
    private final InsightRagProperties.Llm cfg;

    public AnthropicGenerationProvider(InsightRagProperties.Llm cfg) {
        this.cfg = cfg;
        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder()
                .maxRetries(0)
                .timeout(cfg.timeout());
        if (!cfg.apiKey().isBlank()) {
            b.apiKey(cfg.apiKey());
        } else {
            b.fromEnv(); // ANTHROPIC_API_KEY / ANTHROPIC_AUTH_TOKEN / profile
        }
        if (!cfg.baseUrl().isBlank()) {
            b.baseUrl(cfg.baseUrl());
        }
        this.client = b.build();
    }

    @Override
    public String name() {
        return "anthropic:" + cfg.model();
    }

    MessageCreateParams params(GroundedPrompt prompt) {
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(cfg.model())
                .maxTokens(cfg.maxTokens())
                .system(prompt.system())
                .addUserMessage(prompt.user())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(cfg.effort())).build());
        if (cfg.serverSideFallbacks()) {
            // Route safety-classifier refusals to a fallback model server-side instead of
            // stopping; a refusal that still happens is surfaced as Generation.refused.
            b.putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
             .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return b.build();
    }

    @Override
    public Generation generate(GroundedPrompt prompt) {
        try {
            Message m = client.messages().create(params(prompt));
            String text = m.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(t -> t.text())
                    .reduce("", String::concat);
            boolean refused = m.stopReason().map(StopReason.REFUSAL::equals).orElse(false);
            return new Generation(text.strip(), m.usage().inputTokens(), m.usage().outputTokens(), refused);
        } catch (RuntimeException e) {
            throw classify(e);
        }
    }

    @Override
    public Generation stream(GroundedPrompt prompt, Consumer<String> onDelta) {
        StringBuilder text = new StringBuilder();
        long[] usage = new long[2];
        boolean[] refused = new boolean[1];
        try (StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(params(prompt))) {
            stream.stream().forEach(event -> {
                event.messageStart().ifPresent(s -> usage[0] = s.message().usage().inputTokens());
                event.contentBlockDelta().flatMap(d -> d.delta().text()).ifPresent(t -> {
                    text.append(t.text());
                    onDelta.accept(t.text());
                });
                event.messageDelta().ifPresent(d -> {
                    usage[1] = d.usage().outputTokens();
                    d.usage().inputTokens().ifPresent(in -> usage[0] = Math.max(usage[0], in));
                    refused[0] |= d.delta().stopReason().map(StopReason.REFUSAL::equals).orElse(false);
                });
            });
        } catch (RuntimeException e) {
            throw classify(e);
        }
        return new Generation(text.toString().strip(), usage[0], usage[1], refused[0]);
    }

    /** Most-specific first: 429 and 5xx and I/O are transient; other statuses are permanent. */
    static LlmException classify(RuntimeException e) {
        if (e instanceof LlmException le) {
            return le;
        }
        if (e instanceof RateLimitException rl) {
            return new LlmException.Transient("provider rate limited (429)", retryAfter(rl), e);
        }
        if (e instanceof InternalServerException ise) {
            return new LlmException.Transient("provider error (" + ise.statusCode() + ")", retryAfter(ise), e);
        }
        if (e instanceof AnthropicServiceException se) {
            int status = se.statusCode();
            if (status == 408 || status == 409 || status >= 500) {
                return new LlmException.Transient("provider error (" + status + ")", retryAfter(se), e);
            }
            return new LlmException.Permanent("provider rejected request (" + status + ")", e);
        }
        if (e instanceof AnthropicIoException) {
            return new LlmException.Transient("provider I/O failure or timeout", null, e);
        }
        if (e instanceof AnthropicInvalidDataException) {
            return new LlmException.Transient("malformed provider response", null, e);
        }
        return new LlmException.Transient("provider call failed: " + e.getClass().getSimpleName(), null, e);
    }

    static Duration retryAfter(AnthropicServiceException e) {
        List<String> values = e.headers().values("retry-after");
        Optional<String> v = values.stream().findFirst();
        if (v.isEmpty()) {
            return null;
        }
        try {
            return Duration.ofMillis((long) (Double.parseDouble(v.get().trim()) * 1000));
        } catch (NumberFormatException ex) {
            return null; // HTTP-date form: fall back to our own backoff
        }
    }
}
