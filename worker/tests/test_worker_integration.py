"""Worker against real Postgres + pgvector and Redis (design doc §8, integration row).

The schema comes from the repository's db/migration scripts — the same files Flyway applies —
so these tests exercise the one schema definition (NFR-09).
"""
import dataclasses
import hashlib
import time
import uuid
from pathlib import Path

import pytest

pytest.importorskip("testcontainers")

from testcontainers.core.container import DockerContainer  # noqa: E402
from testcontainers.core.waiting_utils import wait_for_logs  # noqa: E402
from testcontainers.postgres import PostgresContainer  # noqa: E402

import psycopg  # noqa: E402
import redis as redis_lib  # noqa: E402

from insightrag_worker import db  # noqa: E402
from insightrag_worker.chunker import ChunkingConfig, chunk_document  # noqa: E402
from insightrag_worker.config import Settings  # noqa: E402
from insightrag_worker.embeddings import HashEmbeddingProvider, RetryingEmbedder, TransientEmbeddingError  # noqa: E402
from insightrag_worker.extract import extract  # noqa: E402
from insightrag_worker.main import Worker  # noqa: E402
from insightrag_worker.processor import BlobStore, Processor  # noqa: E402
from insightrag_worker.queue import StreamQueue  # noqa: E402
from insightrag_worker.tokenizer import RegexTokenizer  # noqa: E402

from .test_extract import make_pdf  # noqa: E402

pytestmark = pytest.mark.integration

REPO = Path(__file__).resolve().parents[2]
MIGRATIONS = sorted((REPO / "db" / "migration").glob("V*.sql"), key=lambda p: int(p.name[1:].split("__")[0]))


@pytest.fixture(scope="module")
def pg():
    with PostgresContainer("pgvector/pgvector:pg16", username="insightrag", password="insightrag",
                           dbname="insightrag", driver=None) as c:
        url = c.get_connection_url()
        with psycopg.connect(url, autocommit=True) as conn:
            for m in MIGRATIONS:
                conn.execute(m.read_text())
        yield url


@pytest.fixture(scope="module")
def redis_url():
    c = DockerContainer("redis:7-alpine").with_exposed_ports(6379)
    c.start()
    try:
        wait_for_logs(c, "Ready to accept connections", timeout=30)
        yield f"redis://{c.get_container_host_ip()}:{c.get_exposed_port(6379)}/0"
    finally:
        c.stop()


@pytest.fixture
def env(pg, redis_url, tmp_path):
    r = redis_lib.Redis.from_url(redis_url)
    r.flushall()
    with psycopg.connect(pg, autocommit=True) as conn:
        conn.execute("TRUNCATE documents CASCADE")
        conn.execute("UPDATE corpus_state SET version = 1, embedding_model = NULL")
    settings = dataclasses.replace(
        Settings(), database_url=pg, redis_url=redis_url, blob_root=str(tmp_path), consumer="w1",
        chunk_size_tokens=128, chunk_overlap_tokens=16, visibility_timeout_ms=60_000, max_attempts=3, block_ms=100)
    return settings, r


def make_worker(settings, r, consumer="w1", provider=None):
    s = dataclasses.replace(settings, consumer=consumer)
    q = StreamQueue(r, s.stream, s.group, consumer, s.dlq_stream, virtual_nodes=16)
    q.ensure_group()
    q.heartbeat()
    embedder = RetryingEmbedder(provider or HashEmbeddingProvider(), s.batch_size, 1, sleep=lambda _: None)
    proc = Processor(s, q, lambda: db.connect(s.database_url), BlobStore(s.blob_root), embedder, RegexTokenizer())
    w = Worker(s, proc, q)
    w.RECLAIM_EVERY_S = 3600  # reclaim is triggered explicitly in tests
    return w


def upload(settings, r, data: bytes, filename: str, mime: str, enqueue=True) -> uuid.UUID:
    """What the API does: content-addressed blob, QUEUED row, stream message."""
    sha = hashlib.sha256(data).hexdigest()
    key = f"{sha[:2]}/{sha}{Path(filename).suffix}"
    path = Path(settings.blob_root) / key
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    doc_id = uuid.uuid4()
    with psycopg.connect(settings.database_url, autocommit=True) as conn:
        conn.execute(
            "INSERT INTO documents (id, filename, content_hash, mime_type, size_bytes, status, uploaded_by, storage_key)"
            " VALUES (%s, %s, %s, %s, %s, 'QUEUED', %s, %s)",
            (doc_id, filename, sha, mime, len(data), uuid.uuid4(), key))
    if enqueue:
        r.xadd(settings.stream, {"documentId": str(doc_id), "storageKey": key})
    return doc_id


def row(settings, doc_id):
    with psycopg.connect(settings.database_url) as conn:
        return conn.execute("SELECT status, chunk_count, failure_reason FROM documents WHERE id = %s", (doc_id,)).fetchone()


def chunk_ordinals(settings, doc_id):
    with psycopg.connect(settings.database_url) as conn:
        return [r[0] for r in conn.execute("SELECT ordinal FROM chunks WHERE document_id = %s ORDER BY ordinal", (doc_id,))]


def drain(worker, rounds=10):
    for _ in range(rounds):
        worker.tick()


def pending(settings, r):
    return r.xpending(settings.stream, settings.group)["pending"]


CORPUS_DOC = (REPO / "eval" / "corpus" / "hr-leave-and-notice-policy.md").read_text()


def test_pdf_upload_reaches_indexed_with_correct_chunk_count(env):
    settings, r = env
    lines = [l for l in CORPUS_DOC.splitlines() if l.strip()]
    half = len(lines) // 2
    pages = [[l[:95] for l in lines[:half]], [l[:95] for l in lines[half:]]]
    pdf = make_pdf(pages)
    doc_id = upload(settings, r, pdf, "policy.pdf", "application/pdf")

    drain(make_worker(settings, r))

    expected = chunk_document(extract(pdf, "application/pdf"), RegexTokenizer(),
                              ChunkingConfig(settings.chunk_size_tokens, settings.chunk_overlap_tokens))
    status, count, reason = row(settings, doc_id)
    assert (status, reason) == ("INDEXED", None)
    assert count == len(expected) > 1
    assert chunk_ordinals(settings, doc_id) == list(range(count))
    assert pending(settings, r) == 0
    with psycopg.connect(settings.database_url) as conn:
        version, model = conn.execute("SELECT version, embedding_model FROM corpus_state").fetchone()
        pages_seen = conn.execute("SELECT count(DISTINCT page_number) FROM chunks WHERE document_id = %s", (doc_id,)).fetchone()[0]
    assert version == 2 and model == "hash-v1"
    assert pages_seen == len(pages)


def test_redelivered_job_for_indexed_document_is_acked_and_skipped(env):
    settings, r = env
    doc_id = upload(settings, r, CORPUS_DOC.encode(), "policy.md", "text/markdown")
    w = make_worker(settings, r)
    drain(w)
    before = chunk_ordinals(settings, doc_id)
    msg = r.xrange(settings.stream)[0][1]
    r.xadd(settings.stream, msg)  # at-least-once: the same job again
    drain(w)
    assert chunk_ordinals(settings, doc_id) == before
    assert pending(settings, r) == 0


def test_reclaimed_stale_processing_is_reprocessed_without_duplicate_vectors(env):
    settings, r = env
    doc_id = upload(settings, r, CORPUS_DOC.encode(), "policy.md", "text/markdown")
    drain(make_worker(settings, r))
    n = row(settings, doc_id)[1]
    # Simulate a worker that crashed mid-job after a previous success: row left PROCESSING
    # with an expired heartbeat, and the job still pending on a dead consumer.
    with psycopg.connect(settings.database_url, autocommit=True) as conn:
        conn.execute("UPDATE documents SET status='PROCESSING', processing_token=gen_random_uuid(),"
                     " processing_started_at = now() - interval '1 hour' WHERE id = %s", (doc_id,))
    msg_id = r.xadd(settings.stream, {"documentId": str(doc_id), "storageKey": r.xrange(settings.stream)[0][1][b"storageKey"]})
    r.xreadgroup(settings.group, "dead-worker", {settings.stream: ">"})
    w = make_worker(settings, r)
    reclaimed = w.queue.reclaim_stale(min_idle_ms=0)
    assert [m.id for m in reclaimed] == [msg_id.decode()]
    w.processor.dispatch(reclaimed[0], allow_handoff=False)
    status, count, _ = row(settings, doc_id)
    assert status == "INDEXED" and count == n
    assert chunk_ordinals(settings, doc_id) == list(range(n)), "delete-then-insert: no duplicates"


def test_unparseable_document_fails_with_readable_reason_and_is_acked(env):
    settings, r = env
    doc_id = upload(settings, r, b"%PDF-1.4 this is not really a pdf", "broken.pdf", "application/pdf")
    drain(make_worker(settings, r))
    status, count, reason = row(settings, doc_id)
    assert status == "FAILED" and count == 0
    assert "could not be parsed" in reason or "No extractable text" in reason
    assert pending(settings, r) == 0
    assert r.xlen(settings.dlq_stream) == 0, "permanent failures are not poison messages"


class AlwaysDown(HashEmbeddingProvider):
    def embed(self, texts):
        raise TransientEmbeddingError("provider 503")


def test_poison_message_moves_to_dead_letter_stream(env):
    settings, r = env
    doc_id = upload(settings, r, CORPUS_DOC.encode(), "policy.md", "text/markdown")
    w = make_worker(settings, r, provider=AlwaysDown())
    for _ in range(settings.max_attempts + 2):
        drain(w, 2)
        w.processor.deferred_until.clear()  # skip the retry backoff
    status, _, reason = row(settings, doc_id)
    assert status == "FAILED"
    assert "dead-letter" in reason
    dlq = r.xrange(settings.dlq_stream)
    assert len(dlq) == 1 and dlq[0][1][b"documentId"] == str(doc_id).encode()
    assert int(dlq[0][1][b"attempts"]) == settings.max_attempts
    assert pending(settings, r) == 0, "the queue is not blocked"


def test_job_is_handed_to_its_ring_owner(env):
    settings, r = env
    w1, w2 = make_worker(settings, r, "w1"), make_worker(settings, r, "w2")
    w1.queue.heartbeat()
    w2.queue.heartbeat()
    # pick bytes whose (random) document id lands on w2
    for i in range(50):
        doc_id = upload(settings, r, f"Document number {i} about gearbox suppliers.".encode(), f"d{i}.txt", "text/plain",
                        enqueue=False)
        if w1.queue.ring(max_age_s=0).owner(str(doc_id)) == "w2":
            break
    key = hashlib.sha256(f"Document number {i} about gearbox suppliers.".encode()).hexdigest()
    r.xadd(settings.stream, {"documentId": str(doc_id), "storageKey": f"{key[:2]}/{key}.txt"})

    msg = w1.queue.read_new(block_ms=100)[0]
    outcome = w1.processor.dispatch(msg)
    assert outcome.status == "handed_off" and outcome.detail == "w2"
    assert row(settings, doc_id)[0] == "QUEUED"
    assert [m.id for m in w2.queue.read_own_pending()] == [msg.id]
    w2.tick()
    assert row(settings, doc_id)[0] == "INDEXED"


def test_deleted_document_job_is_skipped(env):
    settings, r = env
    doc_id = upload(settings, r, b"short lived", "gone.txt", "text/plain")
    with psycopg.connect(settings.database_url, autocommit=True) as conn:
        conn.execute("DELETE FROM documents WHERE id = %s", (doc_id,))
    drain(make_worker(settings, r))
    assert pending(settings, r) == 0


def test_embedding_model_mismatch_fails_loudly(env):
    settings, r = env
    with psycopg.connect(settings.database_url, autocommit=True) as conn:
        conn.execute("UPDATE corpus_state SET embedding_model = 'openai:text-embedding-3-small'")
    doc_id = upload(settings, r, CORPUS_DOC.encode(), "policy.md", "text/markdown")
    drain(make_worker(settings, r))
    status, count, reason = row(settings, doc_id)
    assert status == "FAILED" and count == 0
    assert "re-embed the corpus" in reason
    assert chunk_ordinals(settings, doc_id) == [], "the rolled-back transaction left no vectors"


def test_superseded_attempt_cannot_write(env):
    settings, r = env
    doc_id = upload(settings, r, b"fenced text", "f.txt", "text/plain", enqueue=False)
    with db.connect(settings.database_url) as conn:
        claim = db.claim(conn, doc_id, "w1", 60_000)
        assert claim.outcome == "claimed"
        conn.execute("UPDATE documents SET processing_token = gen_random_uuid() WHERE id = %s", (doc_id,))
        chunks = chunk_document(extract(b"fenced text", "text/plain"), RegexTokenizer(), ChunkingConfig(64, 8))
        vecs = HashEmbeddingProvider().embed([c.content for c in chunks])
        assert db.write_chunks(conn, doc_id, claim.token, chunks, vecs, "hash-v1") is False
    assert chunk_ordinals(settings, doc_id) == []


def test_queue_depth_reports_lag_and_age(env):
    settings, r = env
    w = make_worker(settings, r)
    upload(settings, r, b"one", "1.txt", "text/plain")
    upload(settings, r, b"two", "2.txt", "text/plain")
    time.sleep(0.05)
    lag, pend, age = w.queue.depth()
    assert lag == 2 and pend == 0 and age is not None and age > 0
    drain(w)
    lag, pend, age = w.queue.depth()
    assert (lag, pend, age) == (0, 0, None)
