package com.insightrag.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * Query vectors (Java) and chunk vectors (Python) must come from the same function, or
 * similarity scores become meaningless without looking wrong (§5.3 step 14). Both test suites
 * assert against contracts/hash-embedding-fixtures.json.
 */
class HashEmbeddingParityTest {

    private final HashEmbeddingProvider provider = new HashEmbeddingProvider();

    @Test
    void fnv1aMatchesPublishedVectors() {
        assertThat(HashEmbeddingProvider.fnv1a32(new byte[0])).isEqualTo(0x811C9DC5L);
        assertThat(HashEmbeddingProvider.fnv1a32("a".getBytes(StandardCharsets.UTF_8))).isEqualTo(0xE40C292CL);
        assertThat(HashEmbeddingProvider.fnv1a32("foobar".getBytes(StandardCharsets.UTF_8))).isEqualTo(0xBF9CF968L);
    }

    @Test
    void matchesCrossLanguageFixtures() throws Exception {
        JsonNode root;
        try (InputStream in = getClass().getResourceAsStream("/contracts/hash-embedding-fixtures.json")) {
            assertThat(in).as("fixture file on the test classpath").isNotNull();
            root = new ObjectMapper().readTree(in);
        }
        assertThat(root.get("model").asText()).isEqualTo(HashEmbeddingProvider.MODEL_ID);
        for (JsonNode c : root.get("cases")) {
            String normalized = EmbeddingText.normalize(c.get("input").asText());
            assertThat(normalized).isEqualTo(c.get("normalized").asText());
            List<String> terms = new ArrayList<>();
            c.get("terms").forEach(t -> terms.add(t.asText()));
            assertThat(HashEmbeddingProvider.terms(normalized)).isEqualTo(terms);

            double[] v = provider.embed(normalized);
            Set<Integer> nonZero = new HashSet<>();
            for (int i = 0; i < v.length; i++) {
                if (v[i] != 0.0) {
                    nonZero.add(i);
                }
            }
            Set<Integer> expectedIdx = new HashSet<>();
            Iterator<Map.Entry<String, JsonNode>> it = c.get("vector").fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                int idx = Integer.parseInt(e.getKey());
                expectedIdx.add(idx);
                assertThat(v[idx]).as("dimension %d of %s", idx, c.get("input").asText())
                        .isCloseTo(e.getValue().asDouble(), within(1e-9));
            }
            assertThat(nonZero).isEqualTo(expectedIdx);
        }
    }

    @Test
    void stopWordOnlyQuestionsHaveNoSignal() {
        assertThat(provider.hasSignal("what is the")).isFalse();
        assertThat(provider.hasSignal("notice period")).isTrue();
    }
}
