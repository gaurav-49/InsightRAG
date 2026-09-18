package com.insightrag.eval;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EvalRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public EvalRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record Question(long id, String key, String question, String category, boolean answerable,
                           List<String> documents, List<String> evidence) {
    }

    public int upsert(List<EvalController.LabelledQuestion> questions) {
        int n = 0;
        for (EvalController.LabelledQuestion q : questions) {
            boolean answerable = !"unanswerable".equals(q.category());
            n += jdbc.update(con -> {
                var ps = con.prepareStatement("""
                        INSERT INTO eval_questions (external_key, question, category, answerable, expected_document,
                                                    evidence, expected_chunk_ids, notes)
                        VALUES (?, ?, ?, ?, ?, ?, '{}', ?)
                        ON CONFLICT (external_key) DO UPDATE SET question = EXCLUDED.question,
                            category = EXCLUDED.category, answerable = EXCLUDED.answerable,
                            expected_document = EXCLUDED.expected_document, evidence = EXCLUDED.evidence,
                            notes = EXCLUDED.notes
                        """);
                ps.setString(1, q.key());
                ps.setString(2, q.question());
                ps.setString(3, q.category() == null ? "factual" : q.category());
                ps.setBoolean(4, answerable);
                ps.setArray(5, con.createArrayOf("text", (q.documents() == null ? List.of() : q.documents()).toArray()));
                ps.setArray(6, con.createArrayOf("text", (q.evidence() == null ? List.of() : q.evidence()).toArray()));
                ps.setString(7, q.notes());
                return ps;
            });
        }
        return n;
    }

    public List<Question> questions() {
        return jdbc.query("SELECT id, external_key, question, category, answerable, expected_document, evidence "
                + "FROM eval_questions ORDER BY id", (rs, i) -> new Question(rs.getLong(1), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getBoolean(5), strings(rs, 6), strings(rs, 7)));
    }

    /** Chunk ids whose whitespace-normalised, lower-cased text contains the snippet. */
    public List<Long> chunksContaining(String evidence) {
        String needle = evidence.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return jdbc.queryForList("SELECT id FROM chunks WHERE strpos(regexp_replace(lower(content), '\\s+', ' ', 'g'), ?) > 0",
                Long.class, needle);
    }

    public void setExpected(long questionId, List<Long> chunkIds) {
        jdbc.update(con -> {
            var ps = con.prepareStatement("UPDATE eval_questions SET expected_chunk_ids = ? WHERE id = ?");
            ps.setArray(1, con.createArrayOf("bigint", chunkIds.toArray()));
            ps.setLong(2, questionId);
            return ps;
        });
    }

    public Optional<Map<String, Object>> latestMetrics() {
        return jdbc.query("SELECT metrics::text FROM eval_runs ORDER BY id DESC LIMIT 1", (rs, i) -> rs.getString(1))
                .stream().findFirst().map(this::readMap);
    }

    public Optional<Map<String, Object>> latestRun() {
        return jdbc.query("SELECT id, created_at, config::text, metrics::text, per_question::text FROM eval_runs "
                + "ORDER BY id DESC LIMIT 1", (rs, i) -> Map.<String, Object>of(
                "id", rs.getLong(1), "createdAt", rs.getObject(2, java.time.OffsetDateTime.class).toString(),
                "config", readMap(rs.getString(3)), "metrics", readMap(rs.getString(4)),
                "perQuestion", readList(rs.getString(5)))).stream().findFirst();
    }

    public long save(Map<String, Object> config, Map<String, Object> metrics, List<?> perQuestion) {
        try {
            return jdbc.queryForObject("INSERT INTO eval_runs (config, metrics, per_question) VALUES "
                            + "(?::jsonb, ?::jsonb, ?::jsonb) RETURNING id", Long.class,
                    json.writeValueAsString(config), json.writeValueAsString(metrics), json.writeValueAsString(perQuestion));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> readMap(String s) {
        try {
            return json.readValue(s, new TypeReference<>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Object> readList(String s) {
        try {
            return json.readValue(s, new TypeReference<>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> strings(ResultSet rs, int col) throws SQLException {
        Array a = rs.getArray(col);
        return a == null ? List.of() : new ArrayList<>(Arrays.asList((String[]) a.getArray()));
    }
}
