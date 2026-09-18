package com.insightrag.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import com.insightrag.document.DocumentFilter;
import com.insightrag.query.QuestionNormalizer;

import org.junit.jupiter.api.Test;

class CacheKeysTest {

    static String key(String q, DocumentFilter f, long version, String fp) {
        return CacheKeys.l1(QuestionNormalizer.forHashing(q), f.canonical(), version, fp);
    }

    @Test
    void normalisationMakesTrivialVariantsEqual() {
        assertThat(key("  What is the NOTICE   period? ", DocumentFilter.NONE, 1, "fp"))
                .isEqualTo(key("what is the notice period?", DocumentFilter.NONE, 1, "fp"));
    }

    @Test
    void corpusVersionFiltersAndConfigAllChangeTheKey() {
        String base = key("q", DocumentFilter.NONE, 1, "fp");
        assertThat(key("q", DocumentFilter.NONE, 2, "fp")).isNotEqualTo(base);
        assertThat(key("q", new DocumentFilter("hr", null, null, null), 1, "fp")).isNotEqualTo(base);
        assertThat(key("q", DocumentFilter.NONE, 1, "fp2")).isNotEqualTo(base);
    }

    @Test
    void equivalentTimestampsProduceTheSameFilterKey() {
        DocumentFilter a = new DocumentFilter(null, null, OffsetDateTime.parse("2026-01-01T01:00:00+01:00"), null);
        DocumentFilter b = new DocumentFilter(null, null, OffsetDateTime.parse("2026-01-01T00:00:00Z"), null);
        assertThat(a.canonical()).isEqualTo(b.canonical());
    }

    @Test
    void lengthPrefixingPreventsBoundaryCollisions() {
        assertThat(CacheKeys.join("ab", "c")).isNotEqualTo(CacheKeys.join("a", "bc"));
    }

    @Test
    void semanticScopeTracksVersion() {
        assertThat(CacheKeys.l2Scope("f", 1, "fp")).isNotEqualTo(CacheKeys.l2Scope("f", 2, "fp"));
        assertThat(CacheKeys.l2Scope("f", 1, "fp")).startsWith("v1:");
    }

    @Test
    void vectorsRoundTripThroughTheWireEncoding() {
        float[] v = SemanticCache.unit(new double[]{3, 4, 0});
        assertThat(SemanticCache.decode(SemanticCache.encode(v))).containsExactly(0.6f, 0.8f, 0f);
        assertThat(SemanticCache.dot(v, v)).isCloseTo(1.0, org.assertj.core.api.Assertions.within(1e-6));
    }
}
