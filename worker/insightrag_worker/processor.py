"""One ingestion job, end to end (design doc §4.3 worker half, §5.2, §5.7)."""
from __future__ import annotations

import logging
import os
import random
import threading
import time
import uuid
from dataclasses import dataclass
from typing import Callable, Dict, Optional

import psycopg
import redis

from . import db, metrics
from .chunker import ChunkingConfig, chunk_document
from .config import Settings
from .embeddings import RetryingEmbedder, TransientEmbeddingError, chunk_embedding_input
from .extract import ExtractionError, extract
from .queue import Message, StreamQueue
from .tokenizer import Tokenizer

log = logging.getLogger(__name__)


class BlobStore:
    """Reads uploads from the volume shared with the API (storage keys are relative paths)."""

    def __init__(self, root: str):
        self.root = os.path.realpath(root)

    def read(self, key: str) -> bytes:
        path = os.path.realpath(os.path.join(self.root, key))
        if not path.startswith(self.root + os.sep):
            raise ExtractionError("Stored upload path is invalid.")
        try:
            with open(path, "rb") as fh:
                return fh.read()
        except FileNotFoundError as exc:
            raise ExtractionError("The uploaded file is missing from storage; upload it again.") from exc


@dataclass(frozen=True)
class Outcome:
    status: str  # indexed | failed | skipped | deferred | dead_lettered | handed_off | lost_ownership
    detail: str = ""


class _Heartbeat:
    """Keeps a long job visibly alive: resets the message idle clock and the row heartbeat."""

    def __init__(self, interval_s: float, beat: Callable[[], None]):
        self._interval = interval_s
        self._beat = beat
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)

    def __enter__(self):
        self._thread.start()
        return self

    def __exit__(self, *exc):
        self._stop.set()
        self._thread.join(timeout=5)

    def _run(self):
        while not self._stop.wait(self._interval):
            try:
                self._beat()
            except Exception as exc:  # a missed beat only risks a duplicate attempt, which is fenced
                log.warning("heartbeat failed", extra={"error": str(exc)})


class Processor:
    def __init__(self, settings: Settings, queue: StreamQueue, conn_factory: Callable[[], psycopg.Connection],
                 blobs: BlobStore, embedder: RetryingEmbedder, tokenizer: Tokenizer):
        self.s = settings
        self.queue = queue
        self.conn_factory = conn_factory
        self.conn = conn_factory()
        self.blobs = blobs
        self.embedder = embedder
        self.tokenizer = tokenizer
        self.chunking = ChunkingConfig(settings.chunk_size_tokens, settings.chunk_overlap_tokens,
                                       settings.chunk_boundary_tolerance)
        self.deferred_until: Dict[str, float] = {}

    # -- routing ----------------------------------------------------------------------------

    def is_deferred(self, msg: Message) -> bool:
        until = self.deferred_until.get(msg.id)
        if until is None:
            return False
        if until <= time.time():
            del self.deferred_until[msg.id]
            return False
        return True

    def dispatch(self, msg: Message, allow_handoff: bool = True) -> Outcome:
        if allow_handoff:
            owner = self.queue.try_handoff(msg)
            if owner:
                metrics.JOBS.labels("handed_off").inc()
                return Outcome("handed_off", owner)
        return self.handle(msg)

    # -- the job ----------------------------------------------------------------------------

    def handle(self, msg: Message) -> Outcome:
        if not msg.document_id or not msg.storage_key:
            self.queue.dead_letter(msg, "malformed job message (missing documentId/storageKey)", 0)
            metrics.JOBS.labels("dead_lettered").inc()
            return Outcome("dead_lettered", "malformed")
        try:
            document_id = uuid.UUID(msg.document_id)
        except ValueError:
            self.queue.dead_letter(msg, "malformed documentId", 0)
            metrics.JOBS.labels("dead_lettered").inc()
            return Outcome("dead_lettered", "malformed")

        attempts = self.queue.record_attempt(msg)
        if attempts > self.s.max_attempts:
            reason = (f"Ingestion abandoned after {attempts - 1} failed attempts; the job was moved to the "
                      "dead-letter queue for inspection.")
            self._ensure_conn()
            db.mark_failed(self.conn, document_id, reason)
            self.queue.dead_letter(msg, reason, attempts - 1)
            metrics.JOBS.labels("dead_lettered").inc()
            log.error("job dead-lettered", extra={"document_id": str(document_id), "attempts": attempts - 1})
            return Outcome("dead_lettered", reason)

        started = time.monotonic()
        token: Optional[uuid.UUID] = None
        try:
            self._ensure_conn()
            claim = db.claim(self.conn, document_id, self.s.consumer, self.s.visibility_timeout_ms)
            if claim.outcome in ("missing", "indexed", "failed"):
                # Deleted, or a redelivery of finished work: idempotent no-op.
                self.queue.ack(msg)
                metrics.JOBS.labels("skipped").inc()
                return Outcome("skipped", claim.outcome)
            if claim.outcome == "busy":
                # A live attempt elsewhere owns it. Leave the message pending: if that attempt
                # dies, this message is reclaimed later and finds a stale row.
                self.queue.forget_attempt(msg)
                self.deferred_until[msg.id] = time.time() + self.s.visibility_timeout_ms / 1000
                metrics.JOBS.labels("deferred").inc()
                return Outcome("deferred", "busy")

            token = claim.token
            doc = claim.document
            interval = max(1.0, self.s.visibility_timeout_ms / 3000)
            hb_conn = self.conn_factory()
            try:
                with _Heartbeat(interval, lambda: (self.queue.refresh(msg), db.heartbeat(hb_conn, document_id, token))):
                    written = self._index(doc, token)
            finally:
                hb_conn.close()
            if not written:
                # Our attempt was declared stale and superseded; the successor owns the result.
                self.queue.ack(msg)
                metrics.JOBS.labels("lost_ownership").inc()
                return Outcome("lost_ownership")
            self.queue.ack(msg)
            metrics.JOBS.labels("indexed").inc()
            metrics.DURATION.observe(time.monotonic() - started)
            log.info("document indexed", extra={"document_id": str(document_id), "attempt": attempts,
                                                "duration_s": round(time.monotonic() - started, 3)})
            return Outcome("indexed")

        except (ExtractionError, db.EmbeddingModelMismatch) as exc:
            # Permanent: the same bytes / configuration will fail the same way. Fail, don't retry.
            db.mark_failed(self.conn, document_id, str(exc), token)
            self.queue.ack(msg)
            metrics.JOBS.labels("failed").inc()
            log.warning("document failed", extra={"document_id": str(document_id), "reason": str(exc)})
            return Outcome("failed", str(exc))

        except (TransientEmbeddingError, psycopg.OperationalError, redis.RedisError, Exception) as exc:
            # Transient or unexpected: release the row and retry the message later with backoff.
            # Repeated failure of the same document is exactly what the attempt counter catches.
            if token is not None:
                try:
                    self._ensure_conn()
                    db.release(self.conn, document_id, token)
                except Exception:
                    log.warning("could not release document after failure", exc_info=True)
            delay = min(300.0, random.uniform(0.5, 1.0) * 5 * 2 ** (attempts - 1))
            self.deferred_until[msg.id] = time.time() + delay
            metrics.JOBS.labels("retry").inc()
            log.warning("job attempt failed; will retry",
                        extra={"document_id": str(document_id), "attempt": attempts, "retry_in_s": round(delay, 1),
                               "error_type": type(exc).__name__, "error": str(exc)[:500]})
            return Outcome("deferred", type(exc).__name__)

    def _index(self, doc: db.DocumentRow, token: uuid.UUID) -> bool:
        data = self.blobs.read(doc.storage_key)
        extracted = extract(data, doc.mime_type)
        if len(extracted.text) > self.s.max_document_chars:
            raise ExtractionError(
                f"Document text is {len(extracted.text):,} characters; the limit is {self.s.max_document_chars:,}.")
        chunks = chunk_document(extracted, self.tokenizer, self.chunking)
        if not chunks:
            raise ExtractionError("Document produced no chunks after extraction.")
        inputs = [chunk_embedding_input(c.content, c.section_heading) for c in chunks]
        calls_before = self.embedder.calls
        vectors = self.embedder.embed_all(inputs)
        metrics.EMBED_CALLS.inc(self.embedder.calls - calls_before)
        metrics.CHUNKS.inc(len(chunks))
        return db.write_chunks(self.conn, doc.id, token, chunks, vectors, self.embedder.model_id)

    def _ensure_conn(self) -> None:
        if self.conn.closed or self.conn.broken:
            self.conn = self.conn_factory()
