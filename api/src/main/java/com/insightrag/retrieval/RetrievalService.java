package com.insightrag.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.insightrag.common.ApiException;
import com.insightrag.config.InsightRagProperties;
import com.insightrag.corpus.CorpusState;
import com.insightrag.document.DocumentFilter;
import com.insightrag.embedding.EmbeddingProvider;

import org.springframework.stereotype.Service;

/** The retrieval algorithm of §5.3, steps 15–20. */
@Service
public class RetrievalService {

    private final VectorSearchRepository search;
    private final EmbeddingProvider embeddings;
    private final InsightRagProperties.Retrieval cfg;

    public RetrievalService(VectorSearchRepository search, EmbeddingProvider embeddings, InsightRagProperties props) {
        this.search = search;
        this.embeddings = embeddings;
        this.cfg = props.retrieval();
    }

    public record Result(List<RetrievedChunk> candidates, List<RetrievedChunk> kept, List<Passage> passages,
                         Double topScore, double floorApplied) {

        public boolean empty() {
            return kept.isEmpty();
        }
    }

    /**
     * Refuse to search when the stored vectors came from another model: similarity between
     * vectors of different models is meaningless rather than obviously wrong (§5.3 step 14).
     */
    public void assertModelMatches(CorpusState state) {
        if (state.embeddingModel() != null && !state.embeddingModel().equals(embeddings.modelId())) {
            throw ApiException.unavailable("embedding_model_mismatch", "The corpus was embedded with "
                    + state.embeddingModel() + " but the API is configured for " + embeddings.modelId()
                    + "; re-embed the corpus or fix the configuration");
        }
    }

    public Result retrieve(double[] queryVector, DocumentFilter filter) {
        return retrieve(queryVector, filter, cfg.topK(), cfg.floor(), cfg.relativeFloor());
    }

    public Result retrieve(double[] queryVector, DocumentFilter filter, int topK, double floor, double relativeFloor) {
        List<RetrievedChunk> candidates = search.search(queryVector, topK, filter);
        List<RetrievedChunk> kept = applyFloor(candidates, floor, relativeFloor);
        Double top = candidates.isEmpty() ? null : candidates.get(0).score();
        return new Result(candidates, kept, mergeAdjacent(kept), top, floor);
    }

    /**
     * Relevance floor (§5.3 step 17): an absolute minimum similarity, plus an optional cutoff
     * relative to the best candidate. Mirrors apply_floor in the offline sweep.
     */
    public static List<RetrievedChunk> applyFloor(List<RetrievedChunk> candidates, double floor, double relative) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        double best = candidates.stream().mapToDouble(RetrievedChunk::score).max().orElse(0);
        double cutoff = Math.max(floor, relative * best);
        return candidates.stream().filter(c -> c.score() >= cutoff)
                .sorted(Comparator.comparingDouble(RetrievedChunk::score).reversed()).toList();
    }

    /**
     * §5.3 step 19: consecutive chunks of one document are merged into a single passage, with
     * their shared overlap included once. Passages are then ordered by best score (step 20).
     */
    public static List<Passage> mergeAdjacent(List<RetrievedChunk> kept) {
        List<RetrievedChunk> byPosition = new ArrayList<>(kept);
        byPosition.sort(Comparator.comparing((RetrievedChunk c) -> c.documentId().toString())
                .thenComparingInt(RetrievedChunk::ordinal));
        List<Passage> out = new ArrayList<>();
        int i = 0;
        while (i < byPosition.size()) {
            RetrievedChunk first = byPosition.get(i);
            RetrievedChunk best = first;
            StringBuilder text = new StringBuilder(first.content());
            List<Long> ids = new ArrayList<>(List.of(first.id()));
            int end = first.charEnd();
            int j = i + 1;
            while (j < byPosition.size()) {
                RetrievedChunk next = byPosition.get(j);
                RetrievedChunk prev = byPosition.get(j - 1);
                if (!next.documentId().equals(first.documentId()) || next.ordinal() != prev.ordinal() + 1) {
                    break;
                }
                int overlap = end - next.charStart();
                if (overlap > 0 && overlap < next.content().length()) {
                    text.append(next.content(), overlap, next.content().length());
                } else if (overlap <= 0) {
                    text.append(' ').append(next.content());
                }
                end = Math.max(end, next.charEnd());
                ids.add(next.id());
                if (next.score() > best.score()) {
                    best = next;
                }
                j++;
            }
            out.add(new Passage(best.id(), List.copyOf(ids), first.documentId(), first.filename(),
                    first.pageNumber(), first.sectionHeading(), text.toString(), best.score()));
            i = j;
        }
        out.sort(Comparator.comparingDouble(Passage::score).reversed());
        return out;
    }
}
