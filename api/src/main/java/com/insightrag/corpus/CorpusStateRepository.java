package com.insightrag.corpus;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CorpusStateRepository {

    private final JdbcTemplate jdbc;

    public CorpusStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public CorpusState current() {
        return jdbc.queryForObject("SELECT version, embedding_model FROM corpus_state WHERE id = 1",
                (rs, i) -> new CorpusState(rs.getLong(1), rs.getString(2)));
    }

    /** Bumped in the same transaction as the corpus change (§5.5 "Invalidation"). */
    public void bump() {
        jdbc.update("UPDATE corpus_state SET version = version + 1, updated_at = now() WHERE id = 1");
    }

    /** Once the last chunk is gone, any embedding model may start a fresh corpus. */
    public void releaseEmbeddingModelIfEmpty() {
        jdbc.update("UPDATE corpus_state SET embedding_model = NULL WHERE id = 1 AND NOT EXISTS (SELECT 1 FROM chunks)");
    }
}
