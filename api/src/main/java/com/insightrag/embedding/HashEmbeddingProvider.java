package com.insightrag.embedding;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code hash-v1}: deterministic feature-hashing embedding for offline development and CI.
 * Must stay bit-compatible with worker/insightrag_worker/hash_embedding.py; both are tested
 * against contracts/hash-embedding-fixtures.json.
 */
public final class HashEmbeddingProvider implements EmbeddingProvider {

    public static final String MODEL_ID = "hash-v1";

    static final Set<String> STOP_WORDS = Set.of((
            "a about above after again against all am an and any are as at be because been before being "
            + "below between both but by can could did do does doing down during each few for from further "
            + "had has have having he her here hers herself him himself his how i if in into is it its itself "
            + "just me more most my myself no nor not of off on once only or other our ours ourselves out "
            + "over own same she should so some such than that the their theirs them themselves then there "
            + "these they this those through to too under until up very was we were what when where which "
            + "while who whom why will with would you your yours yourself yourselves").split(" "));

    private static final Pattern TOKEN = Pattern.compile("[a-z0-9]+");
    private static final int FNV_OFFSET = 0x811C9DC5;
    private static final int FNV_PRIME = 0x01000193;

    @Override
    public String modelId() {
        return MODEL_ID;
    }

    @Override
    public boolean hasSignal(String normalizedText) {
        return !terms(normalizedText).isEmpty();
    }

    static long fnv1a32(byte[] data) {
        int h = FNV_OFFSET;
        for (byte b : data) {
            h ^= (b & 0xff);
            h *= FNV_PRIME;
        }
        return h & 0xffffffffL;
    }

    static String stem(String t) {
        int n = t.length();
        if (n > 4 && t.endsWith("ies")) {
            return t.substring(0, n - 3) + "y";
        }
        if (n > 5 && t.endsWith("ing")) {
            return t.substring(0, n - 3);
        }
        if (n > 4 && t.endsWith("ed")) {
            return t.substring(0, n - 2);
        }
        if (n > 3 && t.endsWith("s") && !t.endsWith("ss")) {
            return t.substring(0, n - 1);
        }
        return t;
    }

    public static List<String> terms(String text) {
        String lowered = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(lowered);
        while (m.find()) {
            String t = m.group();
            if (!STOP_WORDS.contains(t)) {
                out.add(stem(t));
            }
        }
        return out;
    }

    /** Feature -> value, sorted by feature so accumulation order matches the Python side. */
    static TreeMap<String, Double> features(String text) {
        List<String> ts = terms(text);
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, Double> weights = new TreeMap<>();
        for (String t : ts) {
            counts.merge(t, 1, Integer::sum);
            weights.put(t, 1.0);
        }
        for (int i = 0; i + 1 < ts.size(); i++) {
            String f = ts.get(i) + "_" + ts.get(i + 1);
            counts.merge(f, 1, Integer::sum);
            weights.put(f, 0.5);
        }
        TreeMap<String, Double> out = new TreeMap<>();
        counts.forEach((f, c) -> out.put(f, weights.get(f) * (1.0 + Math.log(c))));
        return out;
    }

    @Override
    public double[] embed(String text) {
        double[] vec = new double[DIMENSIONS];
        TreeMap<String, Double> feats = features(text);
        if (feats.isEmpty()) {
            vec[0] = 1.0;
            return vec;
        }
        for (Map.Entry<String, Double> e : feats.entrySet()) {
            long h = fnv1a32(e.getKey().getBytes(StandardCharsets.UTF_8));
            double sign = ((h >> 20) & 1) == 1 ? -1.0 : 1.0;
            vec[(int) (h % DIMENSIONS)] += sign * e.getValue();
        }
        double sum = 0;
        for (double v : vec) {
            sum += v * v;
        }
        double norm = Math.sqrt(sum);
        if (norm == 0.0) {
            vec = new double[DIMENSIONS];
            vec[0] = 1.0;
            return vec;
        }
        for (int i = 0; i < vec.length; i++) {
            vec[i] /= norm;
        }
        return vec;
    }
}
