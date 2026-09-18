package com.insightrag.cache;

import com.insightrag.common.Hashing;

/**
 * §5.5 cache-key derivation. Every input that can change the answer is part of the key: the
 * normalised question, the active filters, the corpus version (any add/update/delete makes
 * every previous key unreachable), and a fingerprint of the retrieval and generation
 * configuration (changing k, the floor, or the model must not serve answers produced under the
 * old settings). Parts are length-prefixed so no choice of question text can collide with a
 * different split of the same characters.
 */
public final class CacheKeys {

    public static final String L1_PREFIX = "insightrag:l1:";
    public static final String L2_PREFIX = "insightrag:l2:";

    private CacheKeys() {
    }

    static String join(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object p : parts) {
            String s = String.valueOf(p);
            sb.append(s.length()).append(':').append(s);
        }
        return sb.toString();
    }

    public static String l1(String normalizedQuestion, String canonicalFilters, long corpusVersion, String fingerprint) {
        return L1_PREFIX + Hashing.sha256Hex(join(normalizedQuestion, canonicalFilters, corpusVersion, fingerprint));
    }

    /** L2 scope: questions are only comparable under the same filters, corpus and config. */
    public static String l2Scope(String canonicalFilters, long corpusVersion, String fingerprint) {
        return "v" + corpusVersion + ":" + Hashing.sha256Hex(join(canonicalFilters, fingerprint)).substring(0, 16);
    }
}
