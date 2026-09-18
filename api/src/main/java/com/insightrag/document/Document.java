package com.insightrag.document;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Document(
        UUID id,
        String filename,
        String contentHash,
        String mimeType,
        long sizeBytes,
        DocumentStatus status,
        String failureReason,
        int chunkCount,
        UUID uploadedBy,
        String source,
        String storageKey,
        OffsetDateTime createdAt,
        OffsetDateTime indexedAt) {
}
