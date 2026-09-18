"""Postgres access for the worker: status transitions and transactional chunk writes (§5.2)."""
from __future__ import annotations

import uuid
from dataclasses import dataclass
from typing import List, Optional, Sequence

import psycopg

from .chunker import Chunk


class EmbeddingModelMismatch(RuntimeError):
    """The corpus was embedded with a different model; mixing vectors would corrupt retrieval."""


@dataclass(frozen=True)
class DocumentRow:
    id: uuid.UUID
    filename: str
    mime_type: str
    status: str
    storage_key: str


@dataclass(frozen=True)
class Claim:
    outcome: str  # claimed | missing | indexed | failed | busy
    document: Optional[DocumentRow] = None
    token: Optional[uuid.UUID] = None


def connect(url: str) -> psycopg.Connection:
    # autocommit + explicit conn.transaction() blocks: every unit of work commits or rolls back
    # exactly where the code says so, with no implicit transaction left open between jobs.
    return psycopg.connect(url, autocommit=True)


def vector_literal(vec: Sequence[float]) -> str:
    return "[" + ",".join(repr(float(v)) for v in vec) + "]"


def claim(conn: psycopg.Connection, document_id: uuid.UUID, worker_id: str, stale_after_ms: int) -> Claim:
    """Move QUEUED (or stale PROCESSING) to PROCESSING under a fresh fencing token.

    Job-level idempotency: an INDEXED or FAILED document is reported back so the caller can
    acknowledge and skip; a PROCESSING document whose heartbeat is fresh belongs to a live
    attempt elsewhere and is reported ``busy``.
    """
    token = uuid.uuid4()
    with conn.transaction():
        row = conn.execute(
            """
            UPDATE documents
               SET status = 'PROCESSING', processing_token = %s, processing_started_at = now(),
                   worker_id = %s, failure_reason = NULL, updated_at = now()
             WHERE id = %s
               AND (status = 'QUEUED'
                    OR (status = 'PROCESSING'
                        AND processing_started_at < now() - make_interval(secs => %s::double precision / 1000)))
         RETURNING id, filename, mime_type, status, storage_key
            """,
            (token, worker_id, document_id, stale_after_ms),
        ).fetchone()
        if row:
            return Claim("claimed", DocumentRow(*row), token)
        current = conn.execute("SELECT status FROM documents WHERE id = %s", (document_id,)).fetchone()
    if current is None:
        return Claim("missing")
    return Claim({"INDEXED": "indexed", "FAILED": "failed"}.get(current[0], "busy"))


def heartbeat(conn: psycopg.Connection, document_id: uuid.UUID, token: uuid.UUID) -> bool:
    with conn.transaction():
        cur = conn.execute(
            "UPDATE documents SET processing_started_at = now() WHERE id = %s AND processing_token = %s",
            (document_id, token),
        )
        return cur.rowcount == 1


def write_chunks(conn: psycopg.Connection, document_id: uuid.UUID, token: uuid.UUID,
                 chunks: List[Chunk], vectors: List[List[float]], model_id: str) -> bool:
    """Replace the document's chunks and mark it INDEXED, atomically.

    Delete-then-insert inside one transaction means a reprocessed document can never
    accumulate duplicate vectors. The final UPDATE is fenced on the processing token: if this
    attempt was declared stale and another worker took over, it affects no rows and the whole
    transaction rolls back. Returns False in that case.
    """
    if len(chunks) != len(vectors):
        raise ValueError("chunk/vector count mismatch")
    with conn.transaction():
        owned = conn.execute(
            "SELECT 1 FROM documents WHERE id = %s AND processing_token = %s FOR UPDATE",
            (document_id, token),
        ).fetchone()
        if not owned:
            return False
        conn.execute("DELETE FROM chunks WHERE document_id = %s", (document_id,))
        with conn.cursor().copy(
            "COPY chunks (document_id, ordinal, content, token_count, page_number, section_heading,"
            " char_start, char_end, embedding) FROM STDIN"
        ) as copy:
            for c, v in zip(chunks, vectors):
                copy.write_row((document_id, c.ordinal, c.content, c.token_count, c.page_number,
                                c.section_heading, c.char_start, c.char_end, vector_literal(v)))
        conn.execute(
            """
            UPDATE documents
               SET status = 'INDEXED', chunk_count = %s, indexed_at = now(), processing_token = NULL,
                   processing_started_at = NULL, failure_reason = NULL, updated_at = now()
             WHERE id = %s AND processing_token = %s
            """,
            (len(chunks), document_id, token),
        )
        stored_model = conn.execute(
            """
            UPDATE corpus_state
               SET version = version + 1, updated_at = now(),
                   embedding_model = COALESCE(embedding_model, %s)
             WHERE id = 1
         RETURNING embedding_model
            """,
            (model_id,),
        ).fetchone()[0]
        if stored_model != model_id:
            raise EmbeddingModelMismatch(
                f"Corpus is embedded with {stored_model!r} but this worker uses {model_id!r}; "
                "re-embed the corpus before switching models."
            )
    return True


def mark_failed(conn: psycopg.Connection, document_id: uuid.UUID, reason: str,
                token: Optional[uuid.UUID] = None) -> bool:
    """Record a human-readable failure. With a token, only if this attempt still owns the row."""
    reason = reason[:2000]
    with conn.transaction():
        if token is None:
            cur = conn.execute(
                """UPDATE documents SET status = 'FAILED', failure_reason = %s, processing_token = NULL,
                          processing_started_at = NULL, updated_at = now()
                    WHERE id = %s AND status <> 'INDEXED'""",
                (reason, document_id),
            )
        else:
            cur = conn.execute(
                """UPDATE documents SET status = 'FAILED', failure_reason = %s, processing_token = NULL,
                          processing_started_at = NULL, updated_at = now()
                    WHERE id = %s AND processing_token = %s""",
                (reason, document_id, token),
            )
        return cur.rowcount == 1


def release(conn: psycopg.Connection, document_id: uuid.UUID, token: uuid.UUID) -> None:
    """Return a claimed document to QUEUED after a transient failure so a retry can claim it."""
    with conn.transaction():
        conn.execute(
            """UPDATE documents SET status = 'QUEUED', processing_token = NULL, processing_started_at = NULL,
                      updated_at = now()
                WHERE id = %s AND processing_token = %s""",
            (document_id, token),
        )
