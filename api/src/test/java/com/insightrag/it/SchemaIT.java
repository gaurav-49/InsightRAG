package com.insightrag.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** The deliberate database-level invariants of §6.1. */
class SchemaIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;

    UUID insertDoc(String hash, String status, int chunks) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, filename, content_hash, mime_type, size_bytes, status, chunk_count,"
                + " uploaded_by, storage_key) VALUES (?, 'f.md', ?, 'text/markdown', 10, ?, ?, ?, 'k')",
                id, hash, status, chunks, UUID.randomUUID());
        return id;
    }

    static String hash() {
        return (UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")).substring(0, 64);
    }

    @Test
    void migrationsAppliedWithHnswIndexAndCorpusState() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_chunks_embedding'", String.class))
                .contains("hnsw").contains("vector_cosine_ops");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM corpus_state", Integer.class)).isEqualTo(1);
    }

    @Test
    void indexedWithZeroChunksIsRejected() {
        assertThatThrownBy(() -> insertDoc(hash(), "INDEXED", 0)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_indexed");
    }

    @Test
    void unknownStatusIsRejected() {
        assertThatThrownBy(() -> insertDoc(hash(), "DONE", 0)).hasMessageContaining("chk_status");
    }

    @Test
    void contentHashIsUnique() {
        String h = hash();
        insertDoc(h, "QUEUED", 0);
        assertThatThrownBy(() -> insertDoc(h, "QUEUED", 0)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateOrdinalsAndCascadeDelete() {
        UUID doc = insertDoc(hash(), "QUEUED", 0);
        String vec = "[" + "0,".repeat(1535) + "1]";
        String sql = "INSERT INTO chunks (document_id, ordinal, content, token_count, char_start, char_end, embedding)"
                + " VALUES (?, 0, 'x', 1, 0, 1, CAST(? AS vector))";
        jdbc.update(sql, doc, vec);
        assertThatThrownBy(() -> jdbc.update(sql, doc, vec)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("DELETE FROM documents WHERE id = ?", doc);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chunks WHERE document_id = ?", Integer.class, doc)).isZero();
    }
}
