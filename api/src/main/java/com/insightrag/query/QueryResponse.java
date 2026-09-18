package com.insightrag.query;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** §6.2 representative responses. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueryResponse(
        // Always serialised: a NO_ANSWER carries an explicit "answer": null (§6.2).
        @JsonInclude(JsonInclude.Include.ALWAYS) String answer,
        QueryStatus status,
        String reason,
        List<Citation> citations,
        boolean cached,
        String cacheTier,
        long latencyMs) {

    public QueryResponse withCache(String tier, long latency) {
        return new QueryResponse(answer, status, reason, citations, !"MISS".equals(tier), tier, latency);
    }
}
