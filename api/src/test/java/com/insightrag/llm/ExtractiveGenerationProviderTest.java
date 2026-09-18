package com.insightrag.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.insightrag.prompt.PromptBuilder;
import com.insightrag.retrieval.Passage;

import org.junit.jupiter.api.Test;

class ExtractiveGenerationProviderTest {

    final ExtractiveGenerationProvider provider = new ExtractiveGenerationProvider();

    @Test
    void quotesTheBestMatchingSentenceWithACitation() {
        var ps = List.of(
                new Passage(1, List.of(1L), UUID.randomUUID(), "hr.md", null, "Notice",
                        "Probation lasts 90 days. Levels L5 and L6 must give 60 days notice. Directors give 90.", 0.9),
                new Passage(2, List.of(2L), UUID.randomUUID(), "t.md", null, "Travel", "Economy class is the default.", 0.5));
        Generation g = provider.generate(PromptBuilder.build("What notice must L5 give?", ps, 3000));
        assertThat(g.text()).contains("Levels L5 and L6 must give 60 days notice [1]");
        assertThat(g.promptTokens()).isZero();
    }

    @Test
    void streamsTheSameTextItReturns() {
        var ps = List.of(new Passage(1, List.of(1L), UUID.randomUUID(), "a.md", 3, null, "The reach is 1,050 millimetres.", 0.9));
        List<String> deltas = new ArrayList<>();
        Generation g = provider.stream(PromptBuilder.build("What is the reach?", ps, 3000), deltas::add);
        assertThat(String.join("", deltas)).isEqualTo(g.text());
    }
}
