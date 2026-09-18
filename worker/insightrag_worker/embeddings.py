"""Embedding providers behind one interface (design doc §11, "Provider API change").

All providers return 1536-d vectors so the fixed-width ``VECTOR(1536)`` column holds any of
them; narrower models are zero-padded, which leaves cosine similarity unchanged.
"""
from __future__ import annotations

import logging
import random
import re
import time
import unicodedata
from typing import List, Optional, Sequence

import httpx

from . import hash_embedding
from .config import Settings

log = logging.getLogger(__name__)

DIMENSIONS = 1536
_WS = re.compile(r"\s+")


def normalize_for_embedding(text: str) -> str:
    """Identical on both sides of the queue (contracts/README.md)."""
    return _WS.sub(" ", unicodedata.normalize("NFKC", text)).strip()


def chunk_embedding_input(content: str, heading: Optional[str]) -> str:
    body = normalize_for_embedding(content)
    if heading:
        return normalize_for_embedding(heading) + "\n" + body
    return body


def pad(vec: Sequence[float]) -> List[float]:
    if len(vec) > DIMENSIONS:
        raise ValueError(f"embedding has {len(vec)} dimensions; column holds {DIMENSIONS}")
    return list(vec) + [0.0] * (DIMENSIONS - len(vec))


class TransientEmbeddingError(RuntimeError):
    """Worth retrying: timeouts, 429, 5xx, connection failures."""


class EmbeddingProvider:
    model_id: str = ""

    def embed(self, texts: Sequence[str]) -> List[List[float]]:  # pragma: no cover - interface
        raise NotImplementedError


class HashEmbeddingProvider(EmbeddingProvider):
    model_id = hash_embedding.MODEL_ID

    def embed(self, texts: Sequence[str]) -> List[List[float]]:
        return [hash_embedding.embed(t) for t in texts]


class _HttpProvider(EmbeddingProvider):
    def __init__(self, base_url: str, model: str, timeout_s: float, api_key: str = ""):
        headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
        self._client = httpx.Client(base_url=base_url, timeout=timeout_s, headers=headers)
        self._model = model

    def _post(self, path: str, body: dict) -> dict:
        try:
            resp = self._client.post(path, json=body)
        except (httpx.TimeoutException, httpx.TransportError) as exc:
            raise TransientEmbeddingError(f"{type(exc).__name__} calling embedding provider") from exc
        if resp.status_code == 429 or resp.status_code >= 500:
            raise TransientEmbeddingError(f"embedding provider returned HTTP {resp.status_code}")
        if resp.status_code >= 400:
            raise RuntimeError(f"embedding provider rejected request: HTTP {resp.status_code}")
        return resp.json()


class OllamaEmbeddingProvider(_HttpProvider):
    def __init__(self, base_url: str, model: str, timeout_s: float):
        super().__init__(base_url or "http://ollama:11434", model or "nomic-embed-text", timeout_s)
        self.model_id = f"ollama:{self._model}"

    def embed(self, texts: Sequence[str]) -> List[List[float]]:
        data = self._post("/api/embed", {"model": self._model, "input": list(texts)})
        return [pad(v) for v in data["embeddings"]]


class OpenAiEmbeddingProvider(_HttpProvider):
    def __init__(self, base_url: str, model: str, timeout_s: float, api_key: str):
        super().__init__(base_url or "https://api.openai.com", model or "text-embedding-3-small", timeout_s, api_key)
        self.model_id = f"openai:{self._model}"

    def embed(self, texts: Sequence[str]) -> List[List[float]]:
        data = self._post("/v1/embeddings", {"model": self._model, "input": list(texts)})
        ordered = sorted(data["data"], key=lambda d: d["index"])
        return [pad(d["embedding"]) for d in ordered]


class RetryingEmbedder:
    """Batches texts and retries transient failures with exponential backoff and full jitter."""

    def __init__(self, provider: EmbeddingProvider, batch_size: int, max_attempts: int,
                 base_delay_s: float = 0.5, max_delay_s: float = 8.0, sleep=time.sleep):
        self.provider = provider
        self.batch_size = max(1, batch_size)
        self.max_attempts = max(1, max_attempts)
        self.base_delay_s = base_delay_s
        self.max_delay_s = max_delay_s
        self._sleep = sleep
        self.calls = 0

    @property
    def model_id(self) -> str:
        return self.provider.model_id

    def embed_all(self, texts: Sequence[str]) -> List[List[float]]:
        out: List[List[float]] = []
        for i in range(0, len(texts), self.batch_size):
            out.extend(self._embed_batch(texts[i:i + self.batch_size]))
        return out

    def _embed_batch(self, batch: Sequence[str]) -> List[List[float]]:
        attempt = 0
        while True:
            attempt += 1
            try:
                self.calls += 1
                vectors = self.provider.embed(batch)
                if len(vectors) != len(batch):
                    raise RuntimeError(f"provider returned {len(vectors)} vectors for {len(batch)} inputs")
                return vectors
            except TransientEmbeddingError as exc:
                if attempt >= self.max_attempts:
                    raise
                delay = random.uniform(0, min(self.max_delay_s, self.base_delay_s * 2 ** (attempt - 1)))
                log.warning("embedding attempt failed; retrying",
                            extra={"attempt": attempt, "delay_s": round(delay, 3), "error": str(exc)})
                self._sleep(delay)


def build_provider(settings: Settings) -> EmbeddingProvider:
    name = settings.embedding_provider.lower()
    if name == "hash":
        return HashEmbeddingProvider()
    if name == "ollama":
        return OllamaEmbeddingProvider(settings.embedding_base_url, settings.embedding_model, settings.embed_timeout_s)
    if name == "openai":
        if not settings.embedding_api_key:
            raise ValueError("EMBEDDING_API_KEY is required for the openai embedding provider")
        return OpenAiEmbeddingProvider(settings.embedding_base_url, settings.embedding_model,
                                       settings.embed_timeout_s, settings.embedding_api_key)
    raise ValueError(f"unknown EMBEDDING_PROVIDER {settings.embedding_provider!r}")
