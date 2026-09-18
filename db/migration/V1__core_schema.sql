-- InsightRAG core schema (design doc §6.1).
-- This directory is the single definition of the schema: the API applies it with Flyway at
-- startup, the integration tests (Java and Python) apply the very same files to a throwaway
-- Testcontainers Postgres, and nothing generates schema at runtime.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE documents (
    id                    UUID        PRIMARY KEY,
    filename              TEXT        NOT NULL,
    content_hash          CHAR(64)    NOT NULL UNIQUE,          -- SHA-256, upload-level dedup key
    mime_type             TEXT        NOT NULL,
    size_bytes            BIGINT      NOT NULL,
    status                TEXT        NOT NULL,                 -- QUEUED|PROCESSING|INDEXED|FAILED
    failure_reason        TEXT        NULL,
    chunk_count           INT         NOT NULL DEFAULT 0,
    uploaded_by           UUID        NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    indexed_at            TIMESTAMPTZ NULL,

    -- Operational columns (not in the §6.1 sketch, required by §5.2 / FR-13):
    storage_key           TEXT        NOT NULL,                 -- where the worker finds the bytes
    source                TEXT        NULL,                     -- free-form origin label, filterable (FR-13)
    processing_token      UUID        NULL,                     -- fencing token of the attempt that owns PROCESSING
    processing_started_at TIMESTAMPTZ NULL,                     -- heartbeat; stale => reclaimable (§5.2)
    worker_id             TEXT        NULL,
    last_enqueued_at      TIMESTAMPTZ NULL,                     -- outbox sweeper bookkeeping
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_status  CHECK (status IN ('QUEUED','PROCESSING','INDEXED','FAILED')),
    CONSTRAINT chk_indexed CHECK (status <> 'INDEXED' OR chunk_count > 0)
);

CREATE INDEX idx_documents_status_created ON documents (status, created_at);
CREATE INDEX idx_documents_created        ON documents (created_at);

CREATE TABLE chunks (
    id              BIGSERIAL   PRIMARY KEY,
    document_id     UUID        NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    ordinal         INT         NOT NULL,
    content         TEXT        NOT NULL,
    token_count     INT         NOT NULL,
    page_number     INT         NULL,
    section_heading TEXT        NULL,
    char_start      INT         NOT NULL,                       -- character offsets into the extracted text (§5.1)
    char_end        INT         NOT NULL,
    embedding       VECTOR(1536) NOT NULL,
    UNIQUE (document_id, ordinal),
    CONSTRAINT chk_offsets CHECK (char_end > char_start AND char_start >= 0)
);

-- similarity index (cosine, matches the <=> operator used by retrieval)
CREATE INDEX idx_chunks_embedding ON chunks
    USING hnsw (embedding vector_cosine_ops);

CREATE TABLE query_log (
    id                BIGSERIAL   PRIMARY KEY,
    question          TEXT        NOT NULL,
    cache_tier        TEXT        NOT NULL,                     -- L1|L2|MISS
    retrieved_ids     BIGINT[]    NULL,
    top_score         REAL        NULL,
    prompt_tokens     INT         NULL,
    completion_tokens INT         NULL,
    latency_ms        INT         NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- operational additions
    outcome           TEXT        NULL,                         -- ANSWERED|NO_ANSWER|DEGRADED
    client_id         TEXT        NULL,
    CONSTRAINT chk_cache_tier CHECK (cache_tier IN ('L1','L2','MISS'))
);

CREATE INDEX idx_query_log_created ON query_log (created_at);

CREATE TABLE eval_questions (
    id                 BIGSERIAL PRIMARY KEY,
    question           TEXT      NOT NULL,
    expected_chunk_ids BIGINT[]  NOT NULL,                      -- ground truth for scoring
    notes              TEXT      NULL
);
