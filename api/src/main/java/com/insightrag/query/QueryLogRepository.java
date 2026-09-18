package com.insightrag.query;

import java.sql.Array;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * query_log (§6.1): one row per query with the cache tier that served it (audit trail for the
 * "cache served a different question" risk of §11) and token counts (NFR-04).
 */
@Repository
public class QueryLogRepository {

    private static final Logger log = LoggerFactory.getLogger(QueryLogRepository.class);

    private final JdbcTemplate jdbc;

    public QueryLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Entry(String question, String cacheTier, List<Long> retrievedIds, Double topScore,
                        Long promptTokens, Long completionTokens, long latencyMs, String outcome, String clientId) {
    }

    /** Logging must never fail a query. */
    public void record(Entry e) {
        try {
            jdbc.update(con -> {
                var ps = con.prepareStatement("""
                        INSERT INTO query_log (question, cache_tier, retrieved_ids, top_score, prompt_tokens,
                                               completion_tokens, latency_ms, outcome, client_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """);
                Array ids = e.retrievedIds() == null ? null : con.createArrayOf("bigint", e.retrievedIds().toArray());
                ps.setString(1, e.question());
                ps.setString(2, e.cacheTier());
                ps.setArray(3, ids);
                ps.setObject(4, e.topScore() == null ? null : e.topScore().floatValue());
                ps.setObject(5, e.promptTokens() == null ? null : e.promptTokens().intValue());
                ps.setObject(6, e.completionTokens() == null ? null : e.completionTokens().intValue());
                ps.setInt(7, (int) Math.min(Integer.MAX_VALUE, e.latencyMs()));
                ps.setString(8, e.outcome());
                ps.setString(9, e.clientId());
                return ps;
            });
        } catch (RuntimeException ex) {
            log.warn("query_log write failed: {}", ex.getMessage());
        }
    }

    /** Mean tokens per answered, generated query over the window (§7.2 "cost per query"). */
    public Double meanTokensPerAnsweredQuery(int days) {
        return jdbc.queryForObject("""
                SELECT avg(coalesce(prompt_tokens, 0) + coalesce(completion_tokens, 0))
                  FROM query_log
                 WHERE cache_tier = 'MISS' AND outcome = 'ANSWERED' AND created_at > now() - make_interval(days => ?)
                """, Double.class, days);
    }
}
