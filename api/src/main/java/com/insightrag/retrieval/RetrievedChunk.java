package com.insightrag.retrieval;

import java.util.UUID;

public record RetrievedChunk(
        long id,
        UUID documentId,
        String filename,
        int ordinal,
        String content,
        Integer pageNumber,
        String sectionHeading,
        int charStart,
        int charEnd,
        double score) {
}
