package com.insightrag.prompt;

import java.util.ArrayList;
import java.util.List;

import com.insightrag.retrieval.Passage;

/**
 * §5.4. The prompt is a structural safeguard: it confines the model to the numbered evidence,
 * requires citations, and tells it to say so when the evidence does not contain the answer.
 */
public final class PromptBuilder {

    public static final String SYSTEM = """
            You answer questions about a private document collection.
            Answer strictly from the numbered context passages in the user message.
            Cite the passage number(s) supporting each claim in square brackets, for example [1] or [2][3].
            If the context does not contain the answer, say plainly that the documents do not contain it.
            Do not use knowledge outside the provided context, and do not guess.
            Keep the answer concise and factual.""";

    private PromptBuilder() {
    }

    /**
     * Passages are taken in relevance order until the token budget is exhausted, so a long
     * low-relevance passage can never displace a short high-relevance one. The best passage is
     * always included, truncated if it alone exceeds the budget.
     */
    public static GroundedPrompt build(String question, List<Passage> passagesByRelevance, int budgetTokens) {
        int fixed = TokenEstimator.estimate(SYSTEM) + TokenEstimator.estimate(question) + 16;
        int remaining = budgetTokens - fixed;
        List<Passage> included = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        for (Passage p : passagesByRelevance) {
            String entry = format(included.size() + 1, p, p.text());
            int cost = TokenEstimator.estimate(entry);
            if (cost > remaining) {
                if (included.isEmpty() && remaining > 50) {
                    int maxChars = (int) ((remaining - TokenEstimator.estimate(format(1, p, ""))) * 3.5);
                    String truncated = p.text().substring(0, Math.max(0, Math.min(p.text().length(), maxChars)));
                    entry = format(1, p, truncated + " …");
                    context.append(entry).append("\n\n");
                    included.add(p);
                    remaining -= TokenEstimator.estimate(entry);
                }
                break;
            }
            context.append(entry).append("\n\n");
            included.add(p);
            remaining -= cost;
        }
        String user = "CONTEXT:\n" + context + "QUESTION: " + question;
        return new GroundedPrompt(SYSTEM, user, List.copyOf(included), budgetTokens - remaining);
    }

    static String format(int n, Passage p, String text) {
        return "[" + n + "] " + text.strip() + "\n(source: " + p.filename() + ", " + location(p) + ")";
    }

    public static String location(Passage p) {
        if (p.pageNumber() != null) {
            return "p." + p.pageNumber();
        }
        if (p.sectionHeading() != null && !p.sectionHeading().isBlank()) {
            return "section \"" + p.sectionHeading() + "\"";
        }
        return "chunk " + p.chunkId();
    }
}
