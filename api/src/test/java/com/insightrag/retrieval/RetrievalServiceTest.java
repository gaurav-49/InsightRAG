package com.insightrag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class RetrievalServiceTest {

    static final UUID DOC_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID DOC_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    static RetrievedChunk chunk(long id, UUID doc, int ordinal, String text, int start, double score) {
        return new RetrievedChunk(id, doc, doc.equals(DOC_A) ? "a.md" : "b.md", ordinal, text, null, "S",
                start, start + text.length(), score);
    }

    @Test
    void absoluteFloorDropsWeakCandidates() {
        var kept = RetrievalService.applyFloor(List.of(chunk(1, DOC_A, 0, "x", 0, 0.8), chunk(2, DOC_A, 5, "y", 50, 0.6)), 0.65, 0);
        assertThat(kept).extracting(RetrievedChunk::id).containsExactly(1L);
    }

    @Test
    void relativeFloorDropsCandidatesFarBelowTheBest() {
        var kept = RetrievalService.applyFloor(List.of(chunk(1, DOC_A, 0, "x", 0, 0.5), chunk(2, DOC_B, 0, "y", 0, 0.3),
                chunk(3, DOC_B, 4, "z", 90, 0.4)), 0.1, 0.7);
        assertThat(kept).extracting(RetrievedChunk::id).containsExactly(1L, 3L);
    }

    @Test
    void nothingClearsTheFloorMeansEmpty() {
        assertThat(RetrievalService.applyFloor(List.of(chunk(1, DOC_A, 0, "x", 0, 0.42)), 0.65, 0)).isEmpty();
        assertThat(RetrievalService.applyFloor(List.of(), 0.65, 0)).isEmpty();
    }

    @Test
    void adjacentChunksMergeWithoutDuplicatingTheOverlap() {
        // chunk 0 covers [0, 20), chunk 1 covers [15, 35): 5 characters of overlap.
        String doc = "The notice period is sixty days for staff.";
        RetrievedChunk c0 = chunk(10, DOC_A, 0, doc.substring(0, 20), 0, 0.7);
        RetrievedChunk c1 = chunk(11, DOC_A, 1, doc.substring(15, 35), 15, 0.9);
        List<Passage> merged = RetrievalService.mergeAdjacent(List.of(c1, c0));
        assertThat(merged).hasSize(1);
        Passage p = merged.get(0);
        assertThat(p.text()).isEqualTo(doc.substring(0, 35));
        assertThat(p.chunkIds()).containsExactly(10L, 11L);
        assertThat(p.chunkId()).isEqualTo(11L); // best-scoring chunk is the citation anchor
        assertThat(p.score()).isEqualTo(0.9);
    }

    @Test
    void nonAdjacentOrDifferentDocumentsStaySeparateAndOrderedByScore() {
        List<Passage> ps = RetrievalService.mergeAdjacent(List.of(
                chunk(1, DOC_A, 0, "alpha", 0, 0.5), chunk(2, DOC_A, 2, "gamma", 40, 0.9), chunk(3, DOC_B, 1, "beta", 20, 0.7)));
        assertThat(ps).extracting(Passage::chunkId).containsExactly(2L, 3L, 1L);
    }
}
