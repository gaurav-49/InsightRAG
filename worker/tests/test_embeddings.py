import httpx
import pytest

from insightrag_worker.embeddings import (
    OllamaEmbeddingProvider, OpenAiEmbeddingProvider, RetryingEmbedder, TransientEmbeddingError,
    chunk_embedding_input, normalize_for_embedding, pad,
)


class Flaky:
    model_id = "flaky"

    def __init__(self, failures):
        self.failures = failures
        self.seen = []

    def embed(self, texts):
        self.seen.append(list(texts))
        if self.failures:
            self.failures -= 1
            raise TransientEmbeddingError("503")
        return [[float(len(t))] for t in texts]


def test_batches_by_configured_size():
    p = Flaky(0)
    e = RetryingEmbedder(p, batch_size=3, max_attempts=1)
    out = e.embed_all(["a", "bb", "ccc", "dddd", "e"])
    assert [len(b) for b in p.seen] == [3, 2]
    assert out == [[1.0], [2.0], [3.0], [4.0], [1.0]]


def test_retries_transient_then_succeeds_with_backoff():
    sleeps = []
    p = Flaky(2)
    e = RetryingEmbedder(p, batch_size=10, max_attempts=3, base_delay_s=1, sleep=sleeps.append)
    assert e.embed_all(["x"]) == [[1.0]]
    assert len(sleeps) == 2
    assert 0 <= sleeps[0] <= 1 and 0 <= sleeps[1] <= 2  # full jitter under an exponential cap


def test_gives_up_after_max_attempts():
    e = RetryingEmbedder(Flaky(5), batch_size=10, max_attempts=3, sleep=lambda s: None)
    with pytest.raises(TransientEmbeddingError):
        e.embed_all(["x"])


def test_normalisation_and_heading_prefix():
    assert normalize_for_embedding("  a\t b\n\nc ") == "a b c"
    assert chunk_embedding_input("body  text", "Notice") == "Notice\nbody text"
    assert chunk_embedding_input("body", None) == "body"


def test_padding_preserves_cosine():
    a, b = [0.6, 0.8], [0.8, 0.6]
    pa, pb = pad(a), pad(b)
    assert len(pa) == 1536
    assert sum(x * y for x, y in zip(pa, pb)) == pytest.approx(sum(x * y for x, y in zip(a, b)))
    with pytest.raises(ValueError):
        pad([0.0] * 2000)


def _mock(handler):
    return httpx.Client(transport=httpx.MockTransport(handler), base_url="http://test")


def test_ollama_provider_pads_and_classifies_errors():
    p = OllamaEmbeddingProvider("http://test", "nomic-embed-text", 5)
    p._client = _mock(lambda req: httpx.Response(200, json={"embeddings": [[0.1] * 768]}))
    assert len(p.embed(["hi"])[0]) == 1536
    p._client = _mock(lambda req: httpx.Response(503))
    with pytest.raises(TransientEmbeddingError):
        p.embed(["hi"])
    p._client = _mock(lambda req: httpx.Response(400))
    with pytest.raises(RuntimeError):
        p.embed(["hi"])


def test_openai_provider_orders_by_index():
    p = OpenAiEmbeddingProvider("http://test", "text-embedding-3-small", 5, "k")
    body = {"data": [{"index": 1, "embedding": [2.0] * 1536}, {"index": 0, "embedding": [1.0] * 1536}]}
    p._client = _mock(lambda req: httpx.Response(200, json=body))
    out = p.embed(["a", "b"])
    assert out[0][0] == 1.0 and out[1][0] == 2.0
