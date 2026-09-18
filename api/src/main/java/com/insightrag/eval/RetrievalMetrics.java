package com.insightrag.eval;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * §7.2 metric definitions — identical to worker/insightrag_worker/evaluation/metrics.py so the
 * live endpoint and the offline sweep report comparable numbers.
 *
 * <p>Ground truth per question is a list of evidence groups (the chunk ids containing each
 * evidence snippet). "Retrieved" means top-k after the relevance floor, i.e. exactly what
 * reaches the prompt.
 */
public final class RetrievalMetrics {

    private RetrievalMetrics() {
    }

    public record Scored(String key, String category, boolean answerable, List<Long> retrieved, List<Double> scores,
                         Double topScore, Double recall, Double precision, Double reciprocalRank, boolean refused,
                         int unresolvedEvidence) {
    }

    public static Scored score(String key, String category, boolean answerable, List<Set<Long>> groups,
                               List<Long> retrieved, List<Double> scores, Double topScore) {
        boolean refused = retrieved.isEmpty();
        if (!answerable) {
            return new Scored(key, category, false, retrieved, scores, topScore, null, null, null, refused, 0);
        }
        List<Set<Long>> resolvable = groups.stream().filter(g -> !g.isEmpty()).toList();
        Set<Long> relevant = new HashSet<>();
        resolvable.forEach(relevant::addAll);
        Set<Long> got = new HashSet<>(retrieved);
        long hitGroups = resolvable.stream().filter(g -> g.stream().anyMatch(got::contains)).count();
        double recall = resolvable.isEmpty() ? 0.0 : (double) hitGroups / resolvable.size();
        double precision = retrieved.isEmpty() ? 0.0
                : (double) retrieved.stream().filter(relevant::contains).count() / retrieved.size();
        double rr = 0.0;
        for (int i = 0; i < retrieved.size(); i++) {
            if (relevant.contains(retrieved.get(i))) {
                rr = 1.0 / (i + 1);
                break;
            }
        }
        return new Scored(key, category, true, retrieved, scores, topScore, recall, precision, rr, refused,
                groups.size() - resolvable.size());
    }

    public static Map<String, Object> aggregate(List<Scored> results, int k) {
        List<Scored> ans = results.stream().filter(Scored::answerable).toList();
        List<Scored> neg = results.stream().filter(r -> !r.answerable()).toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("k", k);
        m.put("questions", results.size());
        m.put("answerable", ans.size());
        m.put("unanswerable", neg.size());
        m.put("recallAt" + k, round(mean(ans.stream().map(Scored::recall).toList())));
        m.put("precisionAt" + k, round(mean(ans.stream().map(Scored::precision).toList())));
        m.put("mrr", round(mean(ans.stream().map(Scored::reciprocalRank).toList())));
        m.put("refusalAccuracy", round(mean(neg.stream().map(r -> r.refused() ? 1.0 : 0.0).toList())));
        m.put("falseRefusalRate", round(mean(ans.stream().map(r -> r.refused() ? 1.0 : 0.0).toList())));
        m.put("unresolvedEvidence", ans.stream().mapToInt(Scored::unresolvedEvidence).sum());
        return m;
    }

    static double mean(List<Double> xs) {
        List<Double> vals = new ArrayList<>(xs);
        return vals.isEmpty() ? 0.0 : vals.stream().mapToDouble(Double::doubleValue).sum() / vals.size();
    }

    static double round(double v) {
        return Math.round(v * 10_000) / 10_000.0;
    }
}
