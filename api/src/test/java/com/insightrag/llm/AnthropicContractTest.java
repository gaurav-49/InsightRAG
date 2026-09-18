package com.insightrag.llm;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import com.insightrag.TestProps;
import com.insightrag.config.InsightRagProperties;
import com.insightrag.metrics.InsightMetrics;
import com.insightrag.prompt.GroundedPrompt;
import com.insightrag.ratelimit.RateLimiter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Contract tests (§8): the real Anthropic SDK client against WireMock standing in for the
 * Messages API. Verifies request shape and our retry/backoff behaviour on timeout, 429, 5xx
 * and malformed responses — without spending a token.
 */
class AnthropicContractTest {

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    static final GroundedPrompt PROMPT = new GroundedPrompt("SYSTEM RULES",
            "CONTEXT:\n[1] Notice is 60 days.\n(source: hr.md, p.2)\n\nQUESTION: What is the notice period?", List.of(), 50);

    static final String OK = """
            {"id":"msg_01","type":"message","role":"assistant","model":"claude-opus-5",
             "content":[{"type":"text","text":"The notice period is 60 days [1]."}],
             "stop_reason":"end_turn","stop_sequence":null,
             "usage":{"input_tokens":812,"output_tokens":14}}
            """;

    final List<Duration> sleeps = new ArrayList<>();
    AnthropicGenerationProvider provider;
    ResilientLlmClient client;

    @BeforeEach
    void setUp() {
        InsightRagProperties props = TestProps.of(Map.of(
                "insightrag.llm.provider", "anthropic",
                "insightrag.llm.base-url", api.baseUrl(),
                "insightrag.llm.api-key", "sk-test",
                "insightrag.llm.timeout", "700ms"));
        provider = new AnthropicGenerationProvider(props.llm());
        client = new ResilientLlmClient(provider, mock(RateLimiter.class), mock(InsightMetrics.class),
                props.llm().retry(), sleeps::add);
    }

    static com.github.tomakehurst.wiremock.client.MappingBuilder messages() {
        return post(urlEqualTo("/v1/messages"));
    }

    @Test
    void sendsAGroundedRequestAndReadsUsage() {
        api.stubFor(messages().willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(OK)));
        Generation g = client.generate(PROMPT);
        assertThat(g.text()).isEqualTo("The notice period is 60 days [1].");
        assertThat(g.promptTokens()).isEqualTo(812);
        assertThat(g.completionTokens()).isEqualTo(14);
        assertThat(g.refused()).isFalse();
        api.verify(postRequestedFor(urlEqualTo("/v1/messages"))
                .withHeader("x-api-key", equalTo("sk-test"))
                .withHeader("anthropic-version", equalTo("2023-06-01"))
                .withHeader("anthropic-beta", equalTo(AnthropicGenerationProvider.FALLBACK_BETA))
                .withRequestBody(matchingJsonPath("$.model", equalTo("claude-opus-5")))
                .withRequestBody(matchingJsonPath("$.system", equalTo("SYSTEM RULES")))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("user")))
                .withRequestBody(matchingJsonPath("$.output_config.effort", equalTo("low")))
                .withRequestBody(matchingJsonPath("$.fallbacks", equalTo("default"))));
    }

    @Test
    void transient5xxIsRetriedAndInvisibleToTheCaller() {
        api.stubFor(messages().inScenario("5xx").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(529).withBody("{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"))
                .willSetStateTo("recovered"));
        api.stubFor(messages().inScenario("5xx").whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(OK)));
        assertThat(client.generate(PROMPT).text()).contains("60 days");
        api.verify(2, postRequestedFor(urlEqualTo("/v1/messages")));
        assertThat(sleeps).hasSize(1);
    }

    @Test
    void rateLimitHonoursRetryAfter() {
        api.stubFor(messages().inScenario("429").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("retry-after", "2")
                        .withBody("{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}"))
                .willSetStateTo("ok"));
        api.stubFor(messages().inScenario("429").whenScenarioStateIs("ok")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(OK)));
        client.generate(PROMPT);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2));
    }

    @Test
    void timeoutIsRetried() {
        api.stubFor(messages().inScenario("slow").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withFixedDelay(2_000).withHeader("Content-Type", "application/json").withBody(OK))
                .willSetStateTo("fast"));
        api.stubFor(messages().inScenario("slow").whenScenarioStateIs("fast")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(OK)));
        assertThat(client.generate(PROMPT).text()).contains("60 days");
        api.verify(2, postRequestedFor(urlEqualTo("/v1/messages")));
    }

    @Test
    void malformedResponsesExhaustRetriesThenDegrade() {
        api.stubFor(messages().willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"this is\": not json")));
        assertThatThrownBy(() -> client.generate(PROMPT)).isInstanceOf(LlmException.Unavailable.class);
        api.verify(3, postRequestedFor(urlEqualTo("/v1/messages")));
    }

    @Test
    void authenticationFailureIsNotRetried() {
        api.stubFor(messages().willReturn(aResponse().withStatus(401)
                .withBody("{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}")));
        assertThatThrownBy(() -> client.generate(PROMPT)).isInstanceOf(LlmException.Unavailable.class);
        api.verify(1, postRequestedFor(urlEqualTo("/v1/messages")));
    }

    @Test
    void refusalStopReasonIsSurfaced() {
        api.stubFor(messages().willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(OK.replace("\"end_turn\"", "\"refusal\""))));
        assertThat(client.generate(PROMPT).refused()).isTrue();
    }

    @Test
    void streamsTextDeltasAndUsage() {
        String sse = String.join("\n",
                "event: message_start",
                "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":640,\"output_tokens\":1}}}",
                "",
                "event: content_block_start",
                "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Sixty \"}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"days [1].\"}}",
                "",
                "event: content_block_stop",
                "data: {\"type\":\"content_block_stop\",\"index\":0}",
                "",
                "event: message_delta",
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":9}}",
                "",
                "event: message_stop",
                "data: {\"type\":\"message_stop\"}",
                "", "");
        api.stubFor(messages().willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/event-stream").withBody(sse)));
        List<String> deltas = new ArrayList<>();
        Generation g = client.stream(PROMPT, deltas::add);
        assertThat(deltas).containsExactly("Sixty ", "days [1].");
        assertThat(g.text()).isEqualTo("Sixty days [1].");
        assertThat(g.promptTokens()).isEqualTo(640);
        assertThat(g.completionTokens()).isEqualTo(9);
        api.verify(postRequestedFor(urlEqualTo("/v1/messages")).withRequestBody(matchingJsonPath("$.stream", equalTo("true"))));
    }
}
