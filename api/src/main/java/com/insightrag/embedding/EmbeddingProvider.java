package com.insightrag.embedding;

/**
 * Query-side embedding. Must be the same model, with the same input normalisation, as the
 * worker used at ingestion (§5.3 step 14); {@link #modelId()} is compared with
 * corpus_state.embedding_model before every search.
 */
public interface EmbeddingProvider {

    int DIMENSIONS = 1536;

    String modelId();

    /** Returns a 1536-wide vector for already-normalised text. */
    double[] embed(String normalizedText);

    /** False when the text carries no signal for this model (e.g. only stop words). */
    default boolean hasSignal(String normalizedText) {
        return !normalizedText.isBlank();
    }
}
