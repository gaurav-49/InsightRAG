package com.insightrag.document;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentRepository {

    private static final String COLUMNS = "id, filename, content_hash, mime_type, size_bytes, status, failure_reason, "
            + "chunk_count, uploaded_by, source, storage_key, created_at, indexed_at";

    private static final RowMapper<Document> MAPPER = DocumentRepository::map;

    private final NamedParameterJdbcTemplate jdbc;

    public DocumentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Document map(ResultSet rs, int i) throws SQLException {
        return new Document(
                rs.getObject("id", UUID.class),
                rs.getString("filename"),
                rs.getString("content_hash"),
                rs.getString("mime_type"),
                rs.getLong("size_bytes"),
                DocumentStatus.valueOf(rs.getString("status")),
                rs.getString("failure_reason"),
                rs.getInt("chunk_count"),
                rs.getObject("uploaded_by", UUID.class),
                rs.getString("source"),
                rs.getString("storage_key"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("indexed_at", OffsetDateTime.class));
    }

    /**
     * Insert unless the content hash already exists. The unique constraint, not a prior
     * SELECT, decides the race between two concurrent uploads of the same bytes (FR-02).
     */
    public boolean insertIfAbsent(Document d) {
        int rows = jdbc.update("""
                INSERT INTO documents (id, filename, content_hash, mime_type, size_bytes, status, uploaded_by,
                                       source, storage_key, last_enqueued_at)
                VALUES (:id, :filename, :hash, :mime, :size, 'QUEUED', :uploadedBy, :source, :storageKey, now())
                ON CONFLICT (content_hash) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("id", d.id())
                .addValue("filename", d.filename())
                .addValue("hash", d.contentHash())
                .addValue("mime", d.mimeType())
                .addValue("size", d.sizeBytes())
                .addValue("uploadedBy", d.uploadedBy())
                .addValue("source", d.source())
                .addValue("storageKey", d.storageKey()));
        return rows == 1;
    }

    public Optional<Document> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM documents WHERE id = :id", new MapSqlParameterSource("id", id), MAPPER)
                .stream().findFirst();
    }

    public Optional<Document> findByHash(String hash) {
        return jdbc.query("SELECT " + COLUMNS + " FROM documents WHERE content_hash = :h", new MapSqlParameterSource("h", hash), MAPPER)
                .stream().findFirst();
    }

    public record Page(List<Document> items, long total) {
    }

    public Page list(DocumentFilter filter, DocumentStatus status, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource p = new MapSqlParameterSource();
        appendFilter(filter, "", where, p);
        if (status != null) {
            where.append(" AND status = :status");
            p.addValue("status", status.name());
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM documents" + where, p, Long.class);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        List<Document> items = jdbc.query("SELECT " + COLUMNS + " FROM documents" + where
                + " ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset", p, MAPPER);
        return new Page(items, total == null ? 0 : total);
    }

    /** Adds FR-13 predicates on a documents alias ("" or "d."). Parameters are always bound. */
    static void appendFilter(DocumentFilter f, String alias, StringBuilder where, MapSqlParameterSource p) {
        if (f == null) {
            return;
        }
        if (f.source() != null) {
            where.append(" AND ").append(alias).append("source = :f_source");
            p.addValue("f_source", f.source());
        }
        if (f.mimeType() != null) {
            where.append(" AND ").append(alias).append("mime_type = :f_mime");
            p.addValue("f_mime", f.mimeType());
        }
        if (f.createdFrom() != null) {
            where.append(" AND ").append(alias).append("created_at >= :f_from");
            p.addValue("f_from", f.createdFrom());
        }
        if (f.createdTo() != null) {
            where.append(" AND ").append(alias).append("created_at < :f_to");
            p.addValue("f_to", f.createdTo());
        }
    }

    public boolean delete(UUID id) {
        return jdbc.update("DELETE FROM documents WHERE id = :id", new MapSqlParameterSource("id", id)) == 1;
    }

    /** Re-queue a FAILED document for another attempt. */
    public boolean requeueFailed(UUID id) {
        return jdbc.update("""
                UPDATE documents SET status = 'QUEUED', failure_reason = NULL, last_enqueued_at = now(), updated_at = now()
                 WHERE id = :id AND status = 'FAILED'
                """, new MapSqlParameterSource("id", id)) == 1;
    }

    public void markEnqueued(UUID id) {
        jdbc.update("UPDATE documents SET last_enqueued_at = now() WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    /** Outbox sweep: QUEUED documents whose job may never have reached the stream. */
    public List<Document> findStaleQueued(Duration olderThan, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM documents WHERE status = 'QUEUED'"
                        + " AND coalesce(last_enqueued_at, created_at) < now() - make_interval(secs => :secs)"
                        + " ORDER BY created_at LIMIT :limit",
                new MapSqlParameterSource().addValue("secs", (double) olderThan.toSeconds()).addValue("limit", limit), MAPPER);
    }

    public List<Object[]> countByStatus() {
        List<Object[]> out = new ArrayList<>();
        jdbc.query("SELECT status, count(*) FROM documents GROUP BY status",
                rs -> {
                    out.add(new Object[]{rs.getString(1), rs.getLong(2)});
                });
        return out;
    }
}
