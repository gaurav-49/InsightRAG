package com.insightrag.document;

import java.time.OffsetDateTime;

/** FR-13 metadata filters, shared by corpus listing and retrieval. */
public record DocumentFilter(String source, String mimeType, OffsetDateTime createdFrom, OffsetDateTime createdTo) {

    public static final DocumentFilter NONE = new DocumentFilter(null, null, null, null);

    public boolean isEmpty() {
        return source == null && mimeType == null && createdFrom == null && createdTo == null;
    }

    /** Canonical form for cache keys: equal filters must produce equal keys. */
    public String canonical() {
        return "source=" + nz(source) + "|type=" + nz(mimeType)
                + "|from=" + (createdFrom == null ? "" : createdFrom.toInstant())
                + "|to=" + (createdTo == null ? "" : createdTo.toInstant());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
