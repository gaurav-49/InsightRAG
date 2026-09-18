package com.insightrag.llm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import com.insightrag.embedding.HashEmbeddingProvider;
import com.insightrag.prompt.GroundedPrompt;
import com.insightrag.retrieval.Passage;

/**
 * Offline, deterministic stand-in for a language model: quotes the sentences from the
 * retrieved passages that best overlap the question, with citations. It exercises every code
 * path (grounding, citations, caching, streaming) with no network and no token spend, which is
 * what local development and CI need (NFR-08). It is not a substitute for real synthesis.
 */
public class ExtractiveGenerationProvider implements GenerationProvider {

    private static final Pattern SENTENCE = Pattern.compile("(?<=[.!?])\\s+|\\n\\s*\\n|\\n(?=[-*#] )");

    @Override
    public String name() {
        return "extractive";
    }

    record Candidate(int passage, String sentence, double score, int order) {
    }

    @Override
    public Generation generate(GroundedPrompt prompt) {
        String question = prompt.user().substring(prompt.user().lastIndexOf("QUESTION:") + 9).strip();
        Set<String> q = new HashSet<>(HashEmbeddingProvider.terms(question));
        List<Candidate> candidates = new ArrayList<>();
        int order = 0;
        for (int i = 0; i < prompt.passages().size(); i++) {
            Passage p = prompt.passages().get(i);
            for (String s : SENTENCE.split(p.text())) {
                String sentence = s.replaceAll("^[#*\\-\\s]+", "").strip();
                if (sentence.length() < 3) {
                    continue;
                }
                List<String> terms = HashEmbeddingProvider.terms(sentence);
                long overlap = terms.stream().distinct().filter(q::contains).count();
                if (overlap > 0) {
                    // Overlap dominates; passage rank breaks ties towards more relevant evidence.
                    candidates.add(new Candidate(i + 1, sentence, overlap - i * 0.01, order++));
                }
            }
        }
        if (candidates.isEmpty()) {
            return new Generation("The provided documents do not contain a clear answer to this question.", 0, 0, false);
        }
        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed().thenComparingInt(Candidate::order));
        List<Candidate> chosen = candidates.subList(0, Math.min(2, candidates.size()));
        StringBuilder answer = new StringBuilder();
        for (Candidate c : chosen) {
            if (!answer.isEmpty()) {
                answer.append(' ');
            }
            String s = c.sentence();
            answer.append(s.endsWith(".") ? s.substring(0, s.length() - 1) : s).append(" [").append(c.passage()).append("].");
        }
        return new Generation(answer.toString(), 0, 0, false);
    }

    @Override
    public Generation stream(GroundedPrompt prompt, Consumer<String> onDelta) {
        Generation g = generate(prompt);
        for (String piece : g.text().split("(?<= )")) {
            onDelta.accept(piece);
        }
        return g;
    }
}
