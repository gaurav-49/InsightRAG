package com.insightrag.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.insightrag.retrieval.Passage;

import org.junit.jupiter.api.Test;

class PromptBuilderTest {

    static Passage passage(long id, String text, double score, Integer page) {
        return new Passage(id, List.of(id), UUID.randomUUID(), "doc-" + id + ".pdf", page, "Section " + id, text, score);
    }

    @Test
    void numbersPassagesInRelevanceOrderWithSources() {
        GroundedPrompt p = PromptBuilder.build("What is the notice period?",
                List.of(passage(7, "Notice is 60 days.", 0.9, 14), passage(3, "Leave is 25 days.", 0.8, null)), 3000);
        assertThat(p.user()).contains("[1] Notice is 60 days.\n(source: doc-7.pdf, p.14)");
        assertThat(p.user()).contains("[2] Leave is 25 days.\n(source: doc-3.pdf, section \"Section 3\")");
        assertThat(p.user()).endsWith("QUESTION: What is the notice period?");
        assertThat(p.system()).contains("strictly from the numbered context").contains("Do not use knowledge outside");
        assertThat(p.passages()).extracting(Passage::chunkId).containsExactly(7L, 3L);
    }

    @Test
    void staysWithinTokenBudget() {
        String big = "word ".repeat(600); // ~860 estimated tokens each
        List<Passage> ps = List.of(passage(1, big, 0.9, 1), passage(2, big, 0.8, 1), passage(3, big, 0.7, 1),
                passage(4, big, 0.6, 1));
        GroundedPrompt p = PromptBuilder.build("q?", ps, 2000);
        assertThat(p.passages()).hasSize(2);
        assertThat(TokenEstimator.estimate(p.system()) + TokenEstimator.estimate(p.user())).isLessThanOrEqualTo(2000);
    }

    @Test
    void longLowRelevancePassageCannotDisplaceShortHighRelevanceOne() {
        List<Passage> ps = List.of(passage(1, "Short and relevant.", 0.95, 1),
                passage(2, "filler ".repeat(5000), 0.5, 2), passage(3, "Also short.", 0.4, 3));
        GroundedPrompt p = PromptBuilder.build("q?", ps, 1000);
        assertThat(p.passages()).extracting(Passage::chunkId).containsExactly(1L);
    }

    @Test
    void bestPassageIsTruncatedRatherThanDroppedWhenAloneTooLarge() {
        GroundedPrompt p = PromptBuilder.build("q?", List.of(passage(1, "x ".repeat(10_000), 0.9, 1)), 800);
        assertThat(p.passages()).hasSize(1);
        assertThat(TokenEstimator.estimate(p.system()) + TokenEstimator.estimate(p.user())).isLessThanOrEqualTo(800);
        assertThat(p.user()).contains(" …");
    }
}
