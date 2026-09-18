"""Worker entry point: consume the ingestion stream until SIGTERM."""
from __future__ import annotations

import logging
import signal
import time

import redis
from prometheus_client import start_http_server

from . import db, logging_setup, metrics, tokenizer
from .config import Settings
from .embeddings import RetryingEmbedder, build_provider
from .processor import BlobStore, Processor
from .queue import StreamQueue

log = logging.getLogger("insightrag_worker")


class Worker:
    HEARTBEAT_EVERY_S = 10.0
    RECLAIM_EVERY_S = 30.0
    METRICS_EVERY_S = 5.0

    def __init__(self, settings: Settings, processor: Processor, queue: StreamQueue):
        self.s = settings
        self.processor = processor
        self.queue = queue
        self.running = True
        self._last = {"heartbeat": 0.0, "reclaim": 0.0, "metrics": 0.0}

    def stop(self, *_):
        log.info("shutdown requested; finishing current job")
        self.running = False

    def _due(self, name: str, every: float) -> bool:
        now = time.monotonic()
        if now - self._last[name] >= every:
            self._last[name] = now
            return True
        return False

    def tick(self) -> int:
        """One loop iteration. Returns the number of messages acted on."""
        if self._due("heartbeat", self.HEARTBEAT_EVERY_S):
            self.queue.heartbeat()
        if self._due("metrics", self.METRICS_EVERY_S):
            self._publish_queue_metrics()
        if self._due("reclaim", self.RECLAIM_EVERY_S):
            for msg in self.queue.reclaim_stale(self.s.visibility_timeout_ms):
                log.info("reclaimed stale job", extra={"message_id": msg.id, "document_id": msg.document_id})
                # A reclaimed message is routed like a new one: its ring owner may be alive.
                self.processor.dispatch(msg)

        # Own pending list first: handed-off, reclaimed or pre-restart work.
        backlog = [m for m in self.queue.read_own_pending(count=100) if not self.processor.is_deferred(m)]
        handled = 0
        for msg in backlog:
            if not self.running:
                break
            if not msg.fields:  # trimmed from the stream while pending
                self.queue.ack(msg)
                continue
            # Already routed once (it is in our pending list), so process here.
            self.processor.dispatch(msg, allow_handoff=False)
            handled += 1
        if handled:
            return handled

        for msg in self.queue.read_new(self.s.block_ms):
            self.processor.dispatch(msg)
            handled += 1
        return handled

    def _publish_queue_metrics(self) -> None:
        try:
            lag, pending, age = self.queue.depth()
            metrics.QUEUE_LAG.set(lag)
            metrics.QUEUE_PENDING.set(pending)
            metrics.QUEUE_OLDEST_AGE.set(age or 0)
            metrics.LIVE_WORKERS.set(len(self.queue.live_members()))
        except redis.RedisError:
            pass

    def run(self) -> None:
        self.queue.ensure_group()
        self.queue.heartbeat()
        log.info("worker started", extra={"consumer": self.s.consumer, "stream": self.s.stream,
                                           "embedding_model": self.processor.embedder.model_id,
                                           "tokenizer": self.processor.tokenizer.name,
                                           "chunk_size": self.s.chunk_size_tokens,
                                           "chunk_overlap": self.s.chunk_overlap_tokens})
        backoff = 1.0
        while self.running:
            try:
                self.tick()
                backoff = 1.0
            except redis.RedisError as exc:
                log.warning("redis unavailable; backing off", extra={"error": str(exc), "sleep_s": backoff})
                time.sleep(backoff)
                backoff = min(backoff * 2, 30.0)
        try:
            self.queue.leave()
        except redis.RedisError:
            pass
        log.info("worker stopped")


def main() -> None:
    settings = Settings.from_env()
    logging_setup.configure()
    start_http_server(settings.metrics_port)
    client = redis.Redis.from_url(settings.redis_url, socket_timeout=max(5.0, settings.block_ms / 1000 + 5))
    queue = StreamQueue(client, settings.stream, settings.group, settings.consumer, settings.dlq_stream,
                        settings.virtual_nodes, settings.membership_ttl_s)
    embedder = RetryingEmbedder(build_provider(settings), settings.batch_size, settings.embed_max_attempts)
    processor = Processor(settings, queue, lambda: db.connect(settings.database_url), BlobStore(settings.blob_root),
                          embedder, tokenizer.for_provider(settings.embedding_provider))
    worker = Worker(settings, processor, queue)
    signal.signal(signal.SIGTERM, worker.stop)
    signal.signal(signal.SIGINT, worker.stop)
    worker.run()


if __name__ == "__main__":
    main()
