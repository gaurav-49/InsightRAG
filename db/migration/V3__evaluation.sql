-- Evaluation harness (design doc §7).
--
-- Chunk ids are surrogate keys that change whenever a document is re-chunked (which the
-- parameter sweep does on purpose), so ground truth is authored as evidence snippets and
-- resolved to expected_chunk_ids against the live corpus at the start of every run.

ALTER TABLE eval_questions
    ADD COLUMN external_key      TEXT    NULL,
    ADD COLUMN category          TEXT    NOT NULL DEFAULT 'factual',   -- factual|synthesis|unanswerable
    ADD COLUMN answerable        BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN expected_document TEXT[]  NOT NULL DEFAULT '{}',
    ADD COLUMN evidence          TEXT[]  NOT NULL DEFAULT '{}';

CREATE UNIQUE INDEX uq_eval_questions_key ON eval_questions (external_key);

CREATE TABLE eval_runs (
    id           BIGSERIAL   PRIMARY KEY,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    config       JSONB       NOT NULL,
    metrics      JSONB       NOT NULL,
    per_question JSONB       NOT NULL
);
