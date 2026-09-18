package com.insightrag.retrieval;

import java.util.List;
import java.util.UUID;

/**
 * Evidence handed to the prompt: one chunk, or several adjacent chunks of the same document
 * merged so their shared overlap is not paid for twice (§5.3 step 19).
 */
public record Passage(
        long chunkId,
        List<Long> chunkIds,
        UUID documentId,
        String filename,
        Integer pageNumber,
        String sectionHeading,
        String text,
        double score) {
}
