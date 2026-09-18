-- Corpus version counter (design doc §5.5 "Invalidation").
-- Lives in Postgres, not Redis, so that it is bumped in the same transaction as the corpus
-- change it describes and can never be evicted: both cache tiers key on it.

CREATE TABLE corpus_state (
    id         SMALLINT    PRIMARY KEY DEFAULT 1,
    version    BIGINT      NOT NULL DEFAULT 1,
    -- Embedding model that produced every vector in `chunks`. Vectors from different models are
    -- not comparable (§5.3 step 14), so the worker refuses to write, and the API refuses to
    -- search, when the configured model differs from this value.
    embedding_model TEXT   NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_singleton CHECK (id = 1)
);

INSERT INTO corpus_state (id, version) VALUES (1, 1);
