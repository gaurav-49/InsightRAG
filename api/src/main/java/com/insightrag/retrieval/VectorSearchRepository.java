package com.insightrag.retrieval;

import java.util.List;
import java.util.UUID;

import com.insightrag.config.InsightRagProperties;
import com.insightrag.document.DocumentFilter;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cosine nearest-neighbour search on the HNSW index with metadata filters applied in the same
 * query (§5.3 step 15, FR-13). Relational metadata and vectors in one store is what makes a
 * filtered search a single consistent query (§4.5).
 */
@Repository
public class VectorSearchRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final int efSearch;

    public VectorSearchRepository(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, InsightRagProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.efSearch = props.retrieval().hnswEfSearch();
    }

    public static String vectorLiteral(double[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append((float) v[i]);
        }
        return sb.append(']').toString();
    }

    public List<RetrievedChunk> search(double[] query, int k, DocumentFilter filter) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("q", vectorLiteral(query))
                .addValue("k", k);
        appendFilter(filter, where, p);
        String sql = """
                SELECT c.id, c.document_id, d.filename, c.ordinal, c.content, c.page_number, c.section_heading,
                       c.char_start, c.char_end, 1 - (c.embedding <=> CAST(:q AS vector)) AS score
                  FROM chunks c
                  JOIN documents d ON d.id = c.document_id
                """ + where + """
                 ORDER BY c.embedding <=> CAST(:q AS vector)
                 LIMIT :k
                """;
        return tx.execute(status -> {
            // SET LOCAL scopes the tuning to this transaction. Iterative scans keep filtered
            // searches from returning fewer than k rows when the filter is selective.
            jdbc.getJdbcTemplate().execute("SET LOCAL hnsw.ef_search = " + efSearch);
            jdbc.getJdbcTemplate().execute("SET LOCAL hnsw.iterative_scan = relaxed_order");
            return jdbc.query(sql, p, (rs, i) -> new RetrievedChunk(
                    rs.getLong("id"),
                    rs.getObject("document_id", UUID.class),
                    rs.getString("filename"),
                    rs.getInt("ordinal"),
                    rs.getString("content"),
                    (Integer) rs.getObject("page_number"),
                    rs.getString("section_heading"),
                    rs.getInt("char_start"),
                    rs.getInt("char_end"),
                    rs.getDouble("score")));
        });
    }

    private static void appendFilter(DocumentFilter f, StringBuilder where, MapSqlParameterSource p) {
        if (f == null || f.isEmpty()) {
            return;
        }
        if (f.source() != null) {
            where.append(" AND d.source = :f_source");
            p.addValue("f_source", f.source());
        }
        if (f.mimeType() != null) {
            where.append(" AND d.mime_type = :f_mime");
            p.addValue("f_mime", f.mimeType());
        }
        if (f.createdFrom() != null) {
            where.append(" AND d.created_at >= :f_from");
            p.addValue("f_from", f.createdFrom());
        }
        if (f.createdTo() != null) {
            where.append(" AND d.created_at < :f_to");
            p.addValue("f_to", f.createdTo());
        }
    }
}
