package com.insightrag.prompt;

import java.util.List;

import com.insightrag.retrieval.Passage;

/** System instructions, the user turn (numbered context + question), and what was included. */
public record GroundedPrompt(String system, String user, List<Passage> passages, int estimatedTokens) {
}
