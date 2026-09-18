package com.insightrag.query;

import java.util.Locale;
import java.util.regex.Pattern;

/** §5.3 step 13: trim and collapse whitespace; lowercase for hashing only. */
public final class QuestionNormalizer {

    private static final Pattern WS = Pattern.compile("\\s+");

    private QuestionNormalizer() {
    }

    /** Casing preserved: this is what the prompt sees. */
    public static String clean(String question) {
        return WS.matcher(question).replaceAll(" ").strip();
    }

    /** Case-folded: this is what cache keys are derived from. */
    public static String forHashing(String question) {
        return clean(question).toLowerCase(Locale.ROOT);
    }
}
