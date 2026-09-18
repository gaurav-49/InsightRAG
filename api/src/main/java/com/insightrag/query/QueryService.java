package com.insightrag.query;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.insightrag.cache.AnswerCache;
import com.insightrag.cache.CacheKeys;
import com.insightrag.cache.SemanticCache;
import com.insightrag.config.InsightRagProperties;
import com.insightrag.corpus.CorpusState;
import com.insightrag.corpus.CorpusStateRepository;
import com.insightrag.document.DocumentFilter;
import com.insightrag.embedding.EmbeddingProvider;
import com.insightrag.embedding.EmbeddingText;
import com.insightrag.llm.Generation;
import com.insightrag.llm.LlmException;
import com.insightrag.llm.ResilientLlmClient;
import com.insightrag.metrics.InsightMetrics;
import com.insightrag.prompt.GroundedPrompt;
import com.insightrag.prompt.PromptBuilder;
import com.insightrag.retrieval.Passage;
import com.insightrag.retrieval.RetrievalService;
import com.insightrag.retrieval.RetrievedChunk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Journey B (§2.2) and the query path of §4.4:
 * normalise → L1 → embed → L2 → vector search → relevance floor → (NO_ANSWER) → grounded
 * prompt → LLM with retry → write L1 + L2 → respond with citations.
 */
@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);
    private static final Pattern CITATION = Pattern.compile("\\[(\\d{1,2})]");

    private final CorpusStateRepository corpus;
    private final AnswerCache l1;
    private final SemanticCache l2;
    private final EmbeddingProvider embeddings;
    private final RetrievalService retrieval;
    private final ResilientLlmClient llm;
    private final QueryLogRepository queryLog;
    private final InsightMetrics metrics;
    private final InsightRagProperties props;

    public QueryService(CorpusStateRepository corpus, AnswerCache l1, SemanticCache l2, EmbeddingProvider embeddings,
                        RetrievalService retrieval, ResilientLlmClient llm, QueryLogRepository queryLog,
                        InsightMetrics metrics, InsightRagProperties props) {
        this.corpus = corpus;
        this.l1 = l1;
        this.l2 = l2;
        this.embeddings = embeddings;
        this.retrieval = retrieval;
        this.llm = llm;
        this.queryLog = queryLog;
        this.metrics = metrics;
        this.props = props;
    }

    /** Receives streaming events; a no-op for the plain JSON endpoint. */
    public interface StreamSink {
        void meta(List<Citation> citations, String cacheTier);

        void token(String text);

        StreamSink NONE = new StreamSink() {
            @Override
            public void meta(List<Citation> citations, String cacheTier) {
            }

            @Override
            public void token(String text) {
            }
        };
    }

    public QueryResponse answer(QueryRequest request, String clientId) {
        return run(request, clientId, StreamSink.NONE, false);
    }

    public QueryResponse stream(QueryRequest request, String clientId, StreamSink sink) {
        return run(request, clientId, sink, true);
    }

    /** Everything that can change an answer besides question, filters and corpus (§5.5). */
    String fingerprint() {
        var r = props.retrieval();
        return String.format(Locale.ROOT, "k=%d|floor=%.4f|rel=%.4f|emb=%s|llm=%s|budget=%d",
                r.topK(), r.floor(), r.relativeFloor(), embeddings.modelId(), llm.providerName(),
                props.llm().contextBudgetTokens());
    }

    private QueryResponse run(QueryRequest request, String clientId, StreamSink sink, boolean streaming) {
        long started = System.nanoTime();
        String question = QuestionNormalizer.clean(request.question());
        DocumentFilter filter = request.toFilter();

        // Corpus version from Postgres: if the store is down this throws and the request fails
        // fast with 503 before any paid call (§5.7).
        CorpusState state = corpus.current();
        retrieval.assertModelMatches(state);
        String fingerprint = fingerprint();

        // L1: exact match on normalised question + filters + corpus version + config.
        String l1Key = CacheKeys.l1(QuestionNormalizer.forHashing(question), filter.canonical(), state.version(), fingerprint);
        Optional<QueryResponse> exact = l1.get(l1Key);
        if (exact.isPresent()) {
            return served(exact.get(), "L1", question, clientId, started, sink);
        }

        // Embed once; the same vector serves L2 and the vector search.
        String embeddingInput = EmbeddingText.normalize(question);
        if (!embeddings.hasSignal(embeddingInput)) {
            QueryResponse none = noAnswer("The question contains no searchable terms.", started);
            return finish(none, "MISS", question, clientId, started, List.of(), null, null, l1Key, null, null, sink);
        }
        double[] vector = embeddings.embed(embeddingInput);
        metrics.embeddingCall();

        // L2: semantic near-duplicate within the same scope.
        String scope = CacheKeys.l2Scope(filter.canonical(), state.version(), fingerprint);
        Optional<SemanticCache.Hit> similar = l2.lookup(scope, vector);
        if (similar.isPresent()) {
            log.debug("L2 hit at similarity {}", similar.get().similarity());
            l1.put(l1Key, similar.get().response());
            return served(similar.get().response(), "L2", question, clientId, started, sink);
        }

        // Retrieval with the relevance floor.
        RetrievalService.Result found = retrieval.retrieve(vector, filter);
        if (found.topScore() != null) {
            metrics.topScore(found.topScore());
        }
        List<Long> retrievedIds = found.kept().stream().map(RetrievedChunk::id).toList();
        if (found.empty()) {
            String reason = found.topScore() == null
                    ? "The corpus contains no indexed passages matching the filters."
                    : String.format(Locale.ROOT, "No passage cleared the relevance threshold (best %.2f < %.2f).",
                        found.topScore(), found.floorApplied());
            QueryResponse none = noAnswer(reason, started);
            return finish(none, "MISS", question, clientId, started, retrievedIds, found.topScore(), null, l1Key, scope, vector, sink);
        }

        // Grounded prompt under the context budget, then generation with retry and backoff.
        GroundedPrompt prompt = PromptBuilder.build(question, found.passages(), props.llm().contextBudgetTokens());
        List<Citation> all = citations(prompt.passages());
        if (streaming) {
            sink.meta(all, "MISS");
        }
        Generation g;
        try {
            g = streaming ? llm.stream(prompt, sink::token) : llm.generate(prompt);
        } catch (LlmException.Unavailable e) {
            // §5.7: after exhaustion, degrade — return the evidence, do not error, do not cache.
            QueryResponse degraded = new QueryResponse(null, QueryStatus.DEGRADED,
                    "Answer generation is temporarily unavailable; the most relevant passages are listed as citations.",
                    all, false, "MISS", elapsed(started));
            return finish(degraded, "MISS", question, clientId, started, retrievedIds, found.topScore(), null, null, null, null, sink);
        }
        double cost = g.promptTokens() / 1e6 * props.llm().inputCostPerMTok()
                + g.completionTokens() / 1e6 * props.llm().outputCostPerMTok();
        metrics.llmUsage(g.promptTokens(), g.completionTokens(), cost);

        if (g.refused() || g.text().isBlank()) {
            QueryResponse degraded = new QueryResponse(null, QueryStatus.DEGRADED,
                    "The model declined to answer; the most relevant passages are listed as citations.",
                    all, false, "MISS", elapsed(started));
            return finish(degraded, "MISS", question, clientId, started, retrievedIds, found.topScore(), g, null, null, null, sink);
        }
        QueryResponse answered = new QueryResponse(g.text(), QueryStatus.ANSWERED, null, cited(g.text(), all),
                false, "MISS", elapsed(started));
        return finish(answered, "MISS", question, clientId, started, retrievedIds, found.topScore(), g, l1Key, scope, vector, sink);
    }

    private QueryResponse served(QueryResponse cached, String tier, String question, String clientId, long started,
                                 StreamSink sink) {
        QueryResponse out = cached.withCache(tier, elapsed(started));
        if (sink != StreamSink.NONE) {
            sink.meta(out.citations(), tier);
            if (out.answer() != null) {
                sink.token(out.answer());
            }
        }
        List<Long> ids = out.citations() == null ? List.of()
                : out.citations().stream().map(Citation::chunkId).toList();
        queryLog.record(new QueryLogRepository.Entry(question, tier, ids, null, null, null, out.latencyMs(),
                out.status().name(), clientId));
        metrics.query(tier, out.status().name(), Duration.ofNanos(System.nanoTime() - started));
        return out;
    }

    private QueryResponse finish(QueryResponse response, String tier, String question, String clientId, long started,
                                 List<Long> retrievedIds, Double topScore, Generation g,
                                 String l1Key, String l2Scope, double[] vector, StreamSink sink) {
        QueryResponse out = response.withCache(tier, elapsed(started));
        if (l1Key != null) {
            l1.put(l1Key, out);
        }
        // Only generated answers go to L2: a NO_ANSWER is cheap to recompute and must not
        // shadow a paraphrase that might clear the floor.
        if (l2Scope != null && out.status() == QueryStatus.ANSWERED) {
            l2.put(l2Scope, vector, out);
        }
        if (sink != StreamSink.NONE && out.status() == QueryStatus.NO_ANSWER) {
            sink.meta(List.of(), tier);
        }
        queryLog.record(new QueryLogRepository.Entry(question, tier, retrievedIds, topScore,
                g == null ? null : g.promptTokens(), g == null ? null : g.completionTokens(),
                out.latencyMs(), out.status().name(), clientId));
        metrics.query(tier, out.status().name(), Duration.ofNanos(System.nanoTime() - started));
        return out;
    }

    private static QueryResponse noAnswer(String reason, long started) {
        return new QueryResponse(null, QueryStatus.NO_ANSWER, reason, List.of(), false, "MISS", elapsed(started));
    }

    static List<Citation> citations(List<Passage> passages) {
        List<Citation> out = new ArrayList<>();
        for (int i = 0; i < passages.size(); i++) {
            Passage p = passages.get(i);
            out.add(new Citation(i + 1, p.chunkId(), p.chunkIds(), p.documentId(), p.filename(), p.pageNumber(),
                    p.sectionHeading(), Math.round(p.score() * 1000) / 1000.0));
        }
        return out;
    }

    /**
     * FR-08: return the passages the answer actually cites; if the model cited none, every
     * passage it was given is returned so no answer ever leaves without its evidence.
     */
    static List<Citation> cited(String answer, List<Citation> all) {
        Set<Integer> refs = new LinkedHashSet<>();
        Matcher m = CITATION.matcher(answer);
        while (m.find()) {
            refs.add(Integer.parseInt(m.group(1)));
        }
        List<Citation> used = all.stream().filter(c -> refs.contains(c.ref())).toList();
        return used.isEmpty() ? all : used;
    }

    static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
