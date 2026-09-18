package com.insightrag.query;

import java.time.OffsetDateTime;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.insightrag.document.DocumentFilter;

public record QueryRequest(
        @NotBlank @Size(max = 2000) String question,
        @Valid Filters filters) {

    /** FR-13 metadata filters. */
    public record Filters(String source, String mimeType, OffsetDateTime createdFrom, OffsetDateTime createdTo) {
    }

    public DocumentFilter toFilter() {
        if (filters == null) {
            return DocumentFilter.NONE;
        }
        return new DocumentFilter(blank(filters.source()), blank(filters.mimeType()), filters.createdFrom(), filters.createdTo());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
