package com.insightrag.query;

import java.util.List;
import java.util.UUID;

/** FR-08: document name plus page or section/chunk reference, and the retrieval score. */
public record Citation(
        int ref,
        long chunkId,
        List<Long> chunkIds,
        UUID documentId,
        String document,
        Integer page,
        String section,
        double score) {
}
