package com.insightrag.corpus;

/** corpus_state row: the cache-invalidation counter and the model that produced the vectors. */
public record CorpusState(long version, String embeddingModel) {
}
