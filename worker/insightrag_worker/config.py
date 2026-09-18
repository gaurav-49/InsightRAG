"""Worker configuration, read once from the environment.

Names mirror the configuration surface in design doc §9.2 (e.g. ``chunk.size.tokens`` becomes
``CHUNK_SIZE_TOKENS``) so one ``.env`` drives both services.
"""
from __future__ import annotations

import os
import socket
from dataclasses import dataclass, field


def _env(name: str, default: str) -> str:
    value = os.environ.get(name)
    return default if value is None or value == "" else value


def _int(name: str, default: int) -> int:
    return int(_env(name, str(default)))


def _float(name: str, default: float) -> float:
    return float(_env(name, str(default)))


@dataclass(frozen=True)
class Settings:
    # stores
    database_url: str = "postgresql://insightrag:insightrag@localhost:5432/insightrag"
    redis_url: str = "redis://localhost:6379/0"
    blob_root: str = "/data/uploads"

    # queue (contracts/README.md)
    stream: str = "insightrag:ingest"
    dlq_stream: str = "insightrag:ingest:dlq"
    group: str = "insightrag-workers"
    consumer: str = field(default_factory=socket.gethostname)
    visibility_timeout_ms: int = 600_000
    max_attempts: int = 5
    block_ms: int = 2_000

    # chunking (§5.1)
    chunk_size_tokens: int = 512
    chunk_overlap_tokens: int = 64
    chunk_boundary_tolerance: float = 0.25

    # embedding
    embedding_provider: str = "hash"
    embedding_model: str = ""
    embedding_base_url: str = ""
    embedding_api_key: str = ""
    batch_size: int = 32
    embed_max_attempts: int = 3
    embed_timeout_s: float = 60.0

    # consistent hashing (§5.8)
    virtual_nodes: int = 64
    membership_ttl_s: int = 30

    metrics_port: int = 9100
    max_document_chars: int = 20_000_000

    @staticmethod
    def from_env() -> "Settings":
        return Settings(
            database_url=_env("DATABASE_URL", Settings.database_url),
            redis_url=_env("REDIS_URL", Settings.redis_url),
            blob_root=_env("BLOB_ROOT", Settings.blob_root),
            stream=_env("INGEST_STREAM", Settings.stream),
            dlq_stream=_env("INGEST_DLQ_STREAM", Settings.dlq_stream),
            group=_env("INGEST_GROUP", Settings.group),
            consumer=_env("WORKER_ID", socket.gethostname()),
            visibility_timeout_ms=_int("WORKER_VISIBILITY_TIMEOUT_MS", Settings.visibility_timeout_ms),
            max_attempts=_int("WORKER_MAX_ATTEMPTS", Settings.max_attempts),
            block_ms=_int("WORKER_BLOCK_MS", Settings.block_ms),
            chunk_size_tokens=_int("CHUNK_SIZE_TOKENS", Settings.chunk_size_tokens),
            chunk_overlap_tokens=_int("CHUNK_OVERLAP_TOKENS", Settings.chunk_overlap_tokens),
            chunk_boundary_tolerance=_float("CHUNK_BOUNDARY_TOLERANCE", Settings.chunk_boundary_tolerance),
            embedding_provider=_env("EMBEDDING_PROVIDER", Settings.embedding_provider),
            embedding_model=_env("EMBEDDING_MODEL", Settings.embedding_model),
            embedding_base_url=_env("EMBEDDING_BASE_URL", Settings.embedding_base_url),
            embedding_api_key=_env("EMBEDDING_API_KEY", Settings.embedding_api_key),
            batch_size=_int("WORKER_BATCH_SIZE", Settings.batch_size),
            embed_max_attempts=_int("EMBEDDING_MAX_ATTEMPTS", Settings.embed_max_attempts),
            embed_timeout_s=_float("EMBEDDING_TIMEOUT_S", Settings.embed_timeout_s),
            virtual_nodes=_int("WORKER_VIRTUAL_NODES", Settings.virtual_nodes),
            membership_ttl_s=_int("WORKER_MEMBERSHIP_TTL_S", Settings.membership_ttl_s),
            metrics_port=_int("WORKER_METRICS_PORT", Settings.metrics_port),
        )
