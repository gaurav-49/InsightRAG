package com.insightrag.eval;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.insightrag.config.InsightRagProperties;
import com.insightrag.corpus.CorpusState;
import com.insightrag.corpus.CorpusStateRepository;
import com.insightrag.document.DocumentFilter;
import com.insightrag.embedding.EmbeddingProvider;
import com.insightrag.embedding.EmbeddingText;
import com.insightrag.query.QueryLogRepository;
import com.insightrag.retrieval.RetrievalService;
import com.insightrag.retrieval.RetrievedChunk;

import org.springframework.stereotype.Service;

/**
 * §7.3: run the labelled set against the live corpus and configuration, score it, compare to
 * the previous run, and report whether the CI gates hold. Retrieval only: generation quality
 * is not asserted here (§8, "Deliberate testing position").
 */
@Service
public class EvalService {

    private final EvalRepository repo;
    private final RetrievalService retrieval;
    private final EmbeddingProvider embeddings;
    private final CorpusStateRepository corpus;
    private final QueryLogRepository queryLog;
    private final InsightRagProperties props;

    public EvalService(EvalRepository repo, RetrievalService retrieval, EmbeddingProvider embeddings,
                       CorpusStateRepository corpus, QueryLogRepository queryLog, InsightRagProperties props) {
        this.repo = repo;
        this.retrieval = retrieval;
        this.embeddings = embeddings;
        this.corpus = corpus;
        this.queryLog = queryLog;
        this.props = props;
    }

    public record RunOptions(Integer topK, Double floor, Double relativeFloor, Boolean persist) {
    }

    public Map<String, Object> run(RunOptions opts) {
        var r = props.retrieval();
        int k = opts.topK() == null ? r.topK() : opts.topK();
        double floor = opts.floor() == null ? r.floor() : opts.floor();
        double relative = opts.relativeFloor() == null ? r.relativeFloor() : opts.relativeFloor();
        CorpusState state = corpus.current();
        retrieval.assertModelMatches(state);

        List<RetrievalMetrics.Scored> scored = new ArrayList<>();
        for (EvalRepository.Question q : repo.questions()) {
            List<Set<Long>> groups = new ArrayList<>();
            Set<Long> expected = new HashSet<>();
            for (String snippet : q.evidence()) {
                Set<Long> g = new HashSet<>(repo.chunksContaining(snippet));
                groups.add(g);
                expected.addAll(g);
            }
            repo.setExpected(q.id(), new ArrayList<>(expected));

            String input = EmbeddingText.normalize(q.question());
            List<Long> ids = List.of();
            List<Double> scores = List.of();
            Double top = null;
            if (embeddings.hasSignal(input)) {
                RetrievalService.Result res = retrieval.retrieve(embeddings.embed(input), DocumentFilter.NONE, k, floor, relative);
                ids = res.kept().stream().map(RetrievedChunk::id).toList();
                scores = res.kept().stream().map(c -> RetrievalMetrics.round(c.score())).toList();
                top = res.topScore() == null ? null : RetrievalMetrics.round(res.topScore());
            }
            scored.add(RetrievalMetrics.score(q.key(), q.category(), q.answerable(), groups, ids, scores, top));
        }

        Map<String, Object> metrics = RetrievalMetrics.aggregate(scored, k);
        Double tokens = queryLog.meanTokensPerAnsweredQuery(7);
        metrics.put("costPerQueryTokens", tokens == null ? null : RetrievalMetrics.round(tokens));

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("topK", k);
        config.put("floor", floor);
        config.put("relativeFloor", relative);
        config.put("embeddingModel", embeddings.modelId());
        config.put("chunkSizeTokens", props.eval().chunkSizeTokens());
        config.put("chunkOverlapTokens", props.eval().chunkOverlapTokens());
        config.put("corpusVersion", state.version());

        double recall = ((Number) metrics.get("recallAt" + k)).doubleValue();
        double precision = ((Number) metrics.get("precisionAt" + k)).doubleValue();
        double refusal = ((Number) metrics.get("refusalAccuracy")).doubleValue();
        var e = props.eval();
        Map<String, Object> gates = new LinkedHashMap<>();
        gates.put("recallGate", e.recallGate());
        gates.put("refusalGate", e.refusalGate());
        gates.put("precisionTarget", e.precisionGate());
        // §7.3 step 23: the build fails on recall or refusal accuracy; precision is reported
        // against its success-criteria target (§1.3).
        gates.put("pass", recall >= e.recallGate() && refusal >= e.refusalGate());
        gates.put("precisionMet", precision >= e.precisionGate());

        Map<String, Object> out = new LinkedHashMap<>(metrics);
        out.put("config", config);
        out.put("gates", gates);
        out.put("baseline", repo.latestMetrics().orElse(null));
        out.put("perQuestion", scored);
        if (opts.persist() == null || opts.persist()) {
            out.put("runId", repo.save(config, metrics, scored));
        }
        return out;
    }
}
