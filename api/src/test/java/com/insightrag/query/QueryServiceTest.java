package com.insightrag.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.insightrag.TestProps;
import com.insightrag.cache.AnswerCache;
import com.insightrag.cache.SemanticCache;
import com.insightrag.corpus.CorpusState;
import com.insightrag.corpus.CorpusStateRepository;
import com.insightrag.embedding.HashEmbeddingProvider;
import com.insightrag.llm.Generation;
import com.insightrag.llm.LlmException;
import com.insightrag.llm.ResilientLlmClient;
import com.insightrag.metrics.InsightMetrics;
import com.insightrag.retrieval.Passage;
import com.insightrag.retrieval.RetrievalService;
import com.insightrag.retrieval.RetrievedChunk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Structural properties of the query path (§8, "Deliberate testing position"): which calls
 * happen, which tier answers, that NO_ANSWER never reaches the LLM, that failures degrade,
 * and that answers carry citations — never the model's wording.
 */
class QueryServiceTest {

    CorpusStateRepository corpus = mock(CorpusStateRepository.class);
    AnswerCache l1 = mock(AnswerCache.class);
    SemanticCache l2 = mock(SemanticCache.class);
    RetrievalService retrieval = mock(RetrievalService.class);
    ResilientLlmClient llm = mock(ResilientLlmClient.class);
    QueryLogRepository log = mock(QueryLogRepository.class);
    QueryService service;

    static final UUID DOC = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(corpus.current()).thenReturn(new CorpusState(7, "hash-v1"));
        when(l1.get(anyString())).thenReturn(Optional.empty());
        when(l2.lookup(anyString(), any())).thenReturn(Optional.empty());
        when(llm.providerName()).thenReturn("test");
        service = new QueryService(corpus, l1, l2, new HashEmbeddingProvider(), retrieval, llm, log,
                mock(InsightMetrics.class), TestProps.of(Map.of("insightrag.retrieval.floor", "0.65")));
    }

    static RetrievalService.Result found(double... scores) {
        List<RetrievedChunk> kept = new java.util.ArrayList<>();
        for (int i = 0; i < scores.length; i++) {
            kept.add(new RetrievedChunk(100 + i, DOC, "policy.md", i * 3, "Passage " + i + " text.", 4, "Notice", i * 100, i * 100 + 16, scores[i]));
        }
        List<Passage> passages = RetrievalService.mergeAdjacent(kept);
        return new RetrievalService.Result(kept, kept, passages, scores.length == 0 ? null : scores[0], 0.65);
    }

    static QueryRequest ask(String q) {
        return new QueryRequest(q, null);
    }

    @Test
    void noPassageAboveFloorReturnsNoAnswerWithoutCallingTheLlm() {
        when(retrieval.retrieve(any(), any())).thenReturn(new RetrievalService.Result(
                List.of(new RetrievedChunk(1, DOC, "a.md", 0, "t", null, null, 0, 1, 0.42)), List.of(), List.of(), 0.42, 0.65));
        QueryResponse r = service.answer(ask("What is the parking policy?"), "c1");
        assertThat(r.status()).isEqualTo(QueryStatus.NO_ANSWER);
        assertThat(r.answer()).isNull();
        assertThat(r.citations()).isEmpty();
        assertThat(r.reason()).isEqualTo("No passage cleared the relevance threshold (best 0.42 < 0.65).");
        verify(llm, never()).generate(any());
        verify(l2, never()).put(anyString(), any(), any());
    }

    @Test
    void answeredQueryCitesTheReferencedPassagesAndFillsBothTiers() {
        when(retrieval.retrieve(any(), any())).thenReturn(found(0.9, 0.8));
        when(llm.generate(any())).thenReturn(new Generation("It is 60 days [2].", 900, 40, false));
        QueryResponse r = service.answer(ask("What is the notice period?"), "c1");
        assertThat(r.status()).isEqualTo(QueryStatus.ANSWERED);
        assertThat(r.cacheTier()).isEqualTo("MISS");
        assertThat(r.cached()).isFalse();
        assertThat(r.citations()).extracting(Citation::ref).containsExactly(2);
        assertThat(r.citations().get(0).document()).isEqualTo("policy.md");
        verify(l1).put(anyString(), any());
        verify(l2).put(anyString(), any(), any());
    }

    @Test
    void answerWithoutCitationMarkersStillReturnsAllEvidence() {
        when(retrieval.retrieve(any(), any())).thenReturn(found(0.9, 0.8));
        when(llm.generate(any())).thenReturn(new Generation("It is 60 days.", 900, 40, false));
        assertThat(service.answer(ask("notice period?"), "c1").citations()).hasSize(2);
    }

    @Test
    void l1HitSkipsEmbeddingRetrievalAndGeneration() {
        QueryResponse cached = new QueryResponse("60 days [1]", QueryStatus.ANSWERED, null, List.of(), false, "MISS", 2100);
        when(l1.get(anyString())).thenReturn(Optional.of(cached));
        QueryResponse r = service.answer(ask("What is the notice period?"), "c1");
        assertThat(r.cacheTier()).isEqualTo("L1");
        assertThat(r.cached()).isTrue();
        verify(retrieval, never()).retrieve(any(), any());
        verify(llm, never()).generate(any());
    }

    @Test
    void l2HitSkipsRetrievalAndGeneration() {
        QueryResponse cached = new QueryResponse("60 days [1]", QueryStatus.ANSWERED, null, List.of(), false, "MISS", 2100);
        when(l2.lookup(anyString(), any())).thenReturn(Optional.of(new SemanticCache.Hit(cached, 0.99)));
        QueryResponse r = service.answer(ask("How much notice?"), "c1");
        assertThat(r.cacheTier()).isEqualTo("L2");
        verify(retrieval, never()).retrieve(any(), any());
        verify(llm, never()).generate(any());
    }

    @Test
    void llmExhaustionDegradesWithEvidenceAndIsNotCached() {
        when(retrieval.retrieve(any(), any())).thenReturn(found(0.9));
        when(llm.generate(any())).thenThrow(new LlmException.Unavailable("down", null));
        QueryResponse r = service.answer(ask("What is the notice period?"), "c1");
        assertThat(r.status()).isEqualTo(QueryStatus.DEGRADED);
        assertThat(r.citations()).hasSize(1);
        verify(l1, never()).put(anyString(), any());
        verify(l2, never()).put(anyString(), any(), any());
    }

    @Test
    void stopWordOnlyQuestionIsRefusedBeforeSearching() {
        QueryResponse r = service.answer(ask("what is the"), "c1");
        assertThat(r.status()).isEqualTo(QueryStatus.NO_ANSWER);
        verify(retrieval, never()).retrieve(any(), any());
    }

    @Test
    void everyQueryIsLoggedWithItsTier() {
        when(retrieval.retrieve(any(), any())).thenReturn(found(0.9));
        when(llm.generate(any())).thenReturn(new Generation("x [1]", 10, 2, false));
        service.answer(ask("notice?"), "client-7");
        verify(log).record(org.mockito.ArgumentMatchers.argThat(e ->
                e.cacheTier().equals("MISS") && e.clientId().equals("client-7") && e.promptTokens() == 10L
                        && e.outcome().equals("ANSWERED")));
    }
}
