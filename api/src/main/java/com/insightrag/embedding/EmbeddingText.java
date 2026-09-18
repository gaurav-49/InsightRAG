package com.insightrag.embedding;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** Embedding input normalisation, identical to the worker's (contracts/README.md). */
public final class EmbeddingText {

    private static final Pattern WS = Pattern.compile("\\s+");

    private EmbeddingText() {
    }

    public static String normalize(String text) {
        return WS.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC)).replaceAll(" ").strip();
    }

    public static double[] pad(double[] vector) {
        if (vector.length > EmbeddingProvider.DIMENSIONS) {
            throw new IllegalStateException("embedding has " + vector.length + " dimensions; column holds "
                    + EmbeddingProvider.DIMENSIONS);
        }
        double[] out = new double[EmbeddingProvider.DIMENSIONS];
        System.arraycopy(vector, 0, out, 0, vector.length);
        return out;
    }
}
