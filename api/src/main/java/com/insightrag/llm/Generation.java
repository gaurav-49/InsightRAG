package com.insightrag.llm;

/** One completed generation with the usage the provider reported (NFR-04: logged per call). */
public record Generation(String text, long promptTokens, long completionTokens, boolean refused) {
}
