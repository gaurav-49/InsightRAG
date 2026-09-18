package com.insightrag.query;

public enum QueryStatus {
    /** Grounded, cited answer. */
    ANSWERED,
    /** Nothing cleared the relevance floor (FR-09); no LLM call was made. */
    NO_ANSWER,
    /** Evidence was found but generation was unavailable after retries; passages are returned. */
    DEGRADED
}
