package com.insightrag.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Same worked example as worker/tests: keeps Java and Python metric definitions in lockstep. */
class RetrievalMetricsTest {

    @Test
    void scoresAnAnswerableQuestion() {
        // two evidence snippets; the first lives in chunks {1, 2} (overlap), the second in {9}
        var r = RetrievalMetrics.score("q", "synthesis", true, List.of(Set.of(1L, 2L), Set.of(9L)),
                List.of(5L, 2L, 7L), List.of(0.9, 0.8, 0.7), 0.9);
        assertThat(r.recall()).isEqualTo(0.5);
        assertThat(r.precision()).isEqualTo(1.0 / 3);
        assertThat(r.reciprocalRank()).isEqualTo(0.5);
        assertThat(r.refused()).isFalse();
    }

    @Test
    void emptyRetrievalOnAnswerableIsAFalseRefusal() {
        var r = RetrievalMetrics.score("q", "factual", true, List.of(Set.of(1L)), List.of(), List.of(), 0.3);
        assertThat(r.recall()).isZero();
        assertThat(r.precision()).isZero();
        assertThat(r.refused()).isTrue();
    }

    @Test
    void unresolvedEvidenceIsCountedNotScored() {
        var r = RetrievalMetrics.score("q", "factual", true, List.of(Set.of(), Set.of(4L)), List.of(4L), List.of(0.9), 0.9);
        assertThat(r.recall()).isEqualTo(1.0);
        assertThat(r.unresolvedEvidence()).isEqualTo(1);
    }

    @Test
    void aggregatesRefusalAccuracyOverUnanswerables() {
        var ok = RetrievalMetrics.score("n1", "unanswerable", false, List.of(), List.of(), List.of(), 0.1);
        var bad = RetrievalMetrics.score("n2", "unanswerable", false, List.of(), List.of(3L), List.of(0.7), 0.7);
        var hit = RetrievalMetrics.score("a1", "factual", true, List.of(Set.of(3L)), List.of(3L), List.of(0.7), 0.7);
        Map<String, Object> m = RetrievalMetrics.aggregate(List.of(ok, bad, hit), 5);
        assertThat(m).containsEntry("refusalAccuracy", 0.5).containsEntry("recallAt5", 1.0)
                .containsEntry("precisionAt5", 1.0).containsEntry("mrr", 1.0).containsEntry("falseRefusalRate", 0.0);
    }
}
