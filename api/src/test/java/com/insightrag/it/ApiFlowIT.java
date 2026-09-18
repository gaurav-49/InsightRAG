package com.insightrag.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.insightrag.embedding.EmbeddingText;
import com.insightrag.embedding.HashEmbeddingProvider;
import com.insightrag.retrieval.VectorSearchRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Journeys A and B end to end through the HTTP API. The Python worker is stood in for by
 * {@link #indexLikeTheWorker}, which writes chunks with the same hash-v1 embedding; the real
 * worker is exercised by the worker's own integration tests and the Compose end-to-end run.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiFlowIT extends IntegrationTestBase {

    static final String POLICY = """
            # Leave and Notice Policy

            ## Notice Periods

            Senior individual contributors and managers at levels L5 and L6 must give 60 days notice.
            Directors and above must give 90 days notice.

            ## Annual Leave

            Full-time employees receive 25 days of paid annual leave per calendar year.
            Up to 5 unused days may be carried over into the next calendar year.
            """;

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    ObjectMapper json;

    static UUID documentId;

    static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("admin-1")).authorities(new SimpleGrantedAuthority("SCOPE_admin"));
    }

    static RequestPostProcessor user(String subject) {
        return jwt().jwt(j -> j.subject(subject)).authorities(new SimpleGrantedAuthority("SCOPE_query"));
    }

    MockHttpServletRequestBuilder ask(String question, String subject) {
        return post("/api/v1/query").with(user(subject)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\": " + quote(question) + "}");
    }

    static String quote(String s) {
        return "\"" + s.replace("\"", "\\\"") + "\"";
    }

    JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void flushCaches() {
        var keys = redis.keys("insightrag:l*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    /** Chunk per paragraph, embedded as heading + content, exactly as the worker embeds. */
    void indexLikeTheWorker(UUID doc, String text) {
        HashEmbeddingProvider emb = new HashEmbeddingProvider();
        String heading = null;
        int ordinal = 0;
        int offset = 0;
        for (String para : text.split("\n\n")) {
            int start = text.indexOf(para, offset);
            offset = start + para.length();
            String p = para.strip();
            if (p.startsWith("#")) {
                heading = p.replaceAll("^#+\\s*", "");
                continue;
            }
            String input = heading == null ? EmbeddingText.normalize(p) : EmbeddingText.normalize(heading) + "\n" + EmbeddingText.normalize(p);
            jdbc.update("INSERT INTO chunks (document_id, ordinal, content, token_count, section_heading, char_start, char_end, embedding)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS vector))", doc, ordinal++, p, p.split("\\s+").length, heading,
                    start, start + para.length(), VectorSearchRepository.vectorLiteral(emb.embed(input)));
        }
        jdbc.update("UPDATE documents SET status = 'INDEXED', chunk_count = ?, indexed_at = now() WHERE id = ?", ordinal, doc);
        jdbc.update("UPDATE corpus_state SET version = version + 1, embedding_model = coalesce(embedding_model, 'hash-v1')");
    }

    @Test
    @Order(1)
    void unauthenticatedAndUnderScopedCallsAreRejected() throws Exception {
        mvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/v1/documents").file(new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes()))
                .with(user("u"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/eval/run").with(user("u"))).andExpect(status().isForbidden());
    }

    @Test
    @Order(2)
    void uploadReturnsImmediatelyWithQueuedStatusAndEnqueuesAJob() throws Exception {
        MvcResult r = mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "leave-policy.md", "text/markdown", POLICY.getBytes(StandardCharsets.UTF_8)))
                        .param("source", "hr").with(admin()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andReturn();
        documentId = UUID.fromString(body(r).get("documentId").asText());

        var messages = redis.opsForStream().range("insightrag:ingest", Range.unbounded());
        assertThat(messages).anySatisfy(m -> {
            assertThat(m.getValue()).containsEntry("documentId", documentId.toString());
            String key = (String) m.getValue().get("storageKey");
            assertThat(Files.readString(BLOBS.resolve(key))).isEqualTo(POLICY);
        });
    }

    @Test
    @Order(3)
    void duplicateUploadShortCircuitsToTheExistingDocument() throws Exception {
        mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "copy-of-policy.md", "text/markdown", POLICY.getBytes(StandardCharsets.UTF_8)))
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.documentId").value(documentId.toString()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM documents WHERE filename LIKE '%policy.md'", Integer.class)).isEqualTo(1);
    }

    @Test
    @Order(4)
    void unsupportedOrMislabelledFilesAreRejected() throws Exception {
        mvc.perform(multipart("/api/v1/documents").file(new MockMultipartFile("file", "x.exe", null, "MZ".getBytes()))
                .with(admin())).andExpect(status().isUnsupportedMediaType());
        mvc.perform(multipart("/api/v1/documents").file(new MockMultipartFile("file", "x.pdf", null, "not a pdf".getBytes()))
                .with(admin())).andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("content_mismatch"));
    }

    @Test
    @Order(5)
    void statusEndpointReflectsIndexing() throws Exception {
        mvc.perform(get("/api/v1/documents/" + documentId).with(user("u"))).andExpect(jsonPath("$.status").value("QUEUED"));
        indexLikeTheWorker(documentId, POLICY);
        mvc.perform(get("/api/v1/documents/" + documentId).with(user("u")))
                .andExpect(jsonPath("$.status").value("INDEXED"))
                .andExpect(jsonPath("$.chunkCount").value(2));
        mvc.perform(get("/api/v1/documents").param("source", "hr").param("status", "INDEXED").with(user("u")))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].filename").value("leave-policy.md"));
    }

    @Test
    @Order(6)
    void answeredQueryIsGroundedAndCitedThenServedFromL1ThenL2() throws Exception {
        MvcResult first = mvc.perform(ask("What notice must L5 managers give?", "worker-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.cacheTier").value("MISS"))
                .andExpect(jsonPath("$.cached").value(false))
                .andReturn();
        JsonNode a = body(first);
        assertThat(a.get("answer").asText()).contains("60 days");
        assertThat(a.get("citations")).isNotEmpty();
        assertThat(a.get("citations").get(0).get("document").asText()).isEqualTo("leave-policy.md");
        assertThat(a.get("citations").get(0).get("section").asText()).isEqualTo("Notice Periods");

        // Same question, different casing and spacing: exact-match tier.
        mvc.perform(ask("  what NOTICE must L5   managers give? ", "worker-b"))
                .andExpect(jsonPath("$.cacheTier").value("L1"))
                .andExpect(jsonPath("$.cached").value(true))
                .andExpect(jsonPath("$.answer").value(a.get("answer").asText()));

        // Different text (so a different L1 key) whose embedding is within the 0.97 threshold:
        // semantic tier. With the lexical hash-v1 model that means the same content words.
        mvc.perform(ask("What notice must the L5 managers give?", "worker-b"))
                .andExpect(jsonPath("$.cacheTier").value("L2"))
                .andExpect(jsonPath("$.answer").value(a.get("answer").asText()));

        assertThat(jdbc.queryForList("SELECT cache_tier FROM query_log ORDER BY id DESC LIMIT 3", String.class))
                .containsExactly("L2", "L1", "MISS");
    }

    @Test
    @Order(7)
    void unanswerableQuestionIsRefusedWithoutAnswer() throws Exception {
        mvc.perform(ask("What is the dental insurance provider?", "worker-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_ANSWER"))
                .andExpect(jsonPath("$.answer").doesNotExist())
                .andExpect(jsonPath("$.citations").isEmpty())
                .andExpect(jsonPath("$.reason").exists());
    }

    @Test
    @Order(8)
    void metadataFiltersApplyInTheSameQuery() throws Exception {
        mvc.perform(post("/api/v1/query").with(user("worker-a")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"How many days of annual leave?\",\"filters\":{\"source\":\"finance\"}}"))
                .andExpect(jsonPath("$.status").value("NO_ANSWER"));
        mvc.perform(post("/api/v1/query").with(user("worker-a")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"How many days of annual leave?\",\"filters\":{\"source\":\"hr\",\"mimeType\":\"text/markdown\"}}"))
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("25 days")));
    }

    @Test
    @Order(9)
    void streamingEmitsMetaTokensAndDone() throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/query/stream").with(user("worker-s")).contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM).content("{\"question\":\"How many unused days can be carried over?\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        r.getAsyncResult(10_000);
        mvc.perform(asyncDispatch(r));
        String sse = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(sse).contains("event:meta").contains("event:token").contains("event:done")
                .contains("\"status\":\"ANSWERED\"").contains("leave-policy.md");
        assertThat(sse.indexOf("event:meta")).isLessThan(sse.indexOf("event:token"));
    }

    @Test
    @Order(10)
    void perClientRateLimitReturns429WithRetryAfter() throws Exception {
        int limited = 0;
        String retryAfter = null;
        for (int i = 0; i < 30; i++) {
            MvcResult r = mvc.perform(ask("What notice must directors give?", "greedy-client")).andReturn();
            if (r.getResponse().getStatus() == 429) {
                limited++;
                retryAfter = r.getResponse().getHeader("Retry-After");
            }
        }
        assertThat(limited).isGreaterThan(0);
        assertThat(retryAfter).isNotNull();
        // Another client is unaffected.
        mvc.perform(ask("What notice must directors give?", "polite-client")).andExpect(status().isOk());
    }

    @Test
    @Order(11)
    void evaluationEndpointScoresTheLabelledSet() throws Exception {
        String questions = json.writeValueAsString(Map.of("questions", List.of(
                Map.of("key", "t-1", "question", "What notice must L5 managers give?", "category", "factual",
                        "evidence", List.of("levels L5 and L6 must give 60 days notice")),
                Map.of("key", "t-2", "question", "How many days of annual leave do full-time employees get?",
                        "category", "factual", "evidence", List.of("25 days of paid annual leave")),
                Map.of("key", "t-neg", "question", "What is the parking policy at the office?", "category", "unanswerable",
                        "evidence", List.of()))));
        mvc.perform(put("/api/v1/eval/questions").with(admin()).contentType(MediaType.APPLICATION_JSON).content(questions))
                .andExpect(jsonPath("$.upserted").value(3));
        MvcResult r = mvc.perform(post("/api/v1/eval/run").with(admin())).andExpect(status().isOk()).andReturn();
        JsonNode report = body(r);
        assertThat(report.get("recallAt5").asDouble()).isEqualTo(1.0);
        assertThat(report.get("refusalAccuracy").asDouble()).isEqualTo(1.0);
        assertThat(report.get("mrr").asDouble()).isEqualTo(1.0);
        assertThat(report.get("perQuestion")).hasSize(3);
        assertThat(report.get("gates").get("pass").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT cardinality(expected_chunk_ids) FROM eval_questions WHERE external_key = 't-1'",
                Integer.class)).isEqualTo(1);
        mvc.perform(get("/api/v1/eval/runs/latest").with(admin())).andExpect(jsonPath("$.metrics.recallAt5").value(1.0));
    }

    @Test
    @Order(12)
    void healthAndMetricsAreOpenAndInformative() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.dependencies.postgres.embeddingModel").value("hash-v1"));
        String metrics = mvc.perform(get("/api/v1/metrics")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(metrics).contains("insightrag_queries_total{").contains("tier=\"L1\"").contains("tier=\"L2\"")
                .contains("insightrag_retrieval_top_score").contains("insightrag_ingest_queue_lag")
                .contains("insightrag_ratelimit_rejections_total");
    }

    @Test
    @Order(13)
    void deletingADocumentRemovesChunksAndInvalidatesCaches() throws Exception {
        long before = jdbc.queryForObject("SELECT version FROM corpus_state", Long.class);
        mvc.perform(ask("What notice must directors give?", "worker-d")).andExpect(jsonPath("$.status").value("ANSWERED"));
        mvc.perform(delete("/api/v1/documents/" + documentId).with(admin())).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chunks WHERE document_id = ?", Integer.class, documentId)).isZero();
        assertThat(jdbc.queryForObject("SELECT version FROM corpus_state", Long.class)).isGreaterThan(before);
        // The cached answer is unreachable under the new corpus version.
        mvc.perform(ask("What notice must directors give?", "worker-d"))
                .andExpect(jsonPath("$.status").value("NO_ANSWER"))
                .andExpect(jsonPath("$.cacheTier").value("MISS"));
        mvc.perform(get("/api/v1/documents/" + documentId).with(user("u"))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/documents/" + documentId).with(admin())).andExpect(status().isNotFound());
    }

    @Test
    @Order(14)
    void validationErrorsAreProblemDetails() throws Exception {
        mvc.perform(post("/api/v1/query").with(user("v")).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
        mvc.perform(get("/api/v1/documents/not-a-uuid").with(user("v"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/query").with(user("v")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"" + "x".repeat(2001) + "\"}"))
                .andExpect(status().isBadRequest());
    }
}
