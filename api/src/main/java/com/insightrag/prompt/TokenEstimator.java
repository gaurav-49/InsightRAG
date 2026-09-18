package com.insightrag.prompt;

/**
 * Conservative token estimate for budgeting the context window. English prose averages about
 * four characters per token for Claude-family tokenizers; dividing by 3.5 over-estimates, so a
 * prompt assembled under the budget stays under it. Exact counts come back in the provider's
 * usage report and are what the query log records.
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        return text == null || text.isEmpty() ? 0 : (int) Math.ceil(text.length() / 3.5);
    }
}
