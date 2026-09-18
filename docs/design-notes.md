# Design notes: implementation against the design document

How each section of the *InsightRAG Software Design Document v1.0* is implemented, and where
(and why) the implementation deliberately goes further or differs.

## §4 Architecture

Two deployables and three stores, as specified: `api` (Java 17, Spring Boot 3.5), `worker`
(Python 3.12), Postgres 16 + pgvector (HNSW), and Redis 7, which carries both the Streams queue
and the caches. The seam between the services is the queue and the database. The only things
both sides must agree on are written down in [contracts/](../contracts/README.md), with test
fixtures that both test suites load.

## §5.1 Chunking — `worker/insightrag_worker/chunker.py`

* Size and overlap are measured in tokens of the embedding model's tokeniser: tiktoken
  `cl100k_base` for OpenAI embeddings, and a sub-word regex tokeniser for `hash-v1` and nomic.
* Cut preference: a paragraph break inside the tolerance window (the top 25% of the target
  size), then the last sentence end in the second half of the window, and only as a last resort a
  token boundary mid-sentence.
* The overlap is carried as the tail tokens of the previous chunk. Every chunk is an exact span
  of the extracted text, so `char_start`/`char_end`, the page and the governing heading are
  exact, not estimated.
* Size and overlap are configuration (`CHUNK_SIZE_TOKENS`, `CHUNK_OVERLAP_TOKENS`) and the sweep
  selects them.

## §5.2 Deduplication and idempotency

* **Upload level.** The API streams the upload to disk while computing SHA-256. `INSERT … ON
  CONFLICT (content_hash) DO NOTHING` decides races between concurrent identical uploads, and
  the loser gets the winner's id with HTTP 200. Blobs are content-addressed.
* **Job level.** The worker claims a document with an `UPDATE … WHERE status = 'QUEUED' OR
  (PROCESSING AND heartbeat stale)` that issues a fresh *fencing token*. An INDEXED, FAILED or
  deleted document is acknowledged and skipped. A document owned by a live attempt stays pending.
* The chunk write is one transaction: delete, `COPY` insert, mark INDEXED *only where the
  fencing token still matches*, bump the corpus version. A superseded attempt therefore rolls
  back instead of racing its successor.
* Long jobs heartbeat: they reset the stream message's idle clock (`XCLAIM` to self) and the row
  timestamp, so the visibility timeout reclaims crashed jobs but not slow ones.

## §5.3 Retrieval — `api/.../retrieval/`

Steps 13–20 map one to one onto `QuestionNormalizer`, `EmbeddingText`, `VectorSearchRepository`
(HNSW cosine, with metadata filters in the same SQL, `hnsw.iterative_scan` so selective filters
still return k rows), `RetrievalService.applyFloor`, the NO_ANSWER short-circuit in
`QueryService`, and `RetrievalService.mergeAdjacent`. Merging joins consecutive chunks of one
document into one passage and includes their shared overlap once.

**Deviation: relative floor.** Besides the absolute `retrieval.floor`, candidates below
`retrieval.relativeFloor × best score` are dropped (0 disables this). With the lexical offline
embedding, no absolute floor alone reached Precision@5 ≥ 0.70 at Recall@5 ≥ 0.85. The relative
cutoff did, and the sweep chooses both values empirically.

**Model guard.** `corpus_state.embedding_model` records which model produced the stored vectors.
The worker refuses to write, and the API refuses to search, under a different model. This turns
the "silent correctness bug" of step 14 into a loud error.

## §5.4 Prompt — `api/.../prompt/PromptBuilder.java`

This is the system prompt of §5.4. Numbered passages carry `(source: file, p.N)`, falling back to
the section heading or chunk id when there are no pages. Passages are added in relevance order
under a conservative token estimate. The best passage is always included (truncated if it alone
exceeds the budget), and a passage that does not fit ends the context.

## §5.5 Caching — `api/.../cache/`

* **L1**: SHA-256 over length-prefixed (normalised question, canonical filters, corpus version,
  configuration fingerprint). The fingerprint covers k, both floors, the embedding model, the LLM
  provider and the context budget, so changing any of them cannot serve an answer produced under
  the old settings.
* **L2**: recent query embeddings are kept per scope (filters + corpus version + fingerprint) in
  Redis. Each API replica mirrors the vectors it has already seen and fetches only new ones, so a
  lookup is one `LRANGE` plus in-memory dot products. The threshold is 0.97. Only `ANSWERED`
  responses go into L2, so a NO_ANSWER cannot shadow a paraphrase that might succeed.
* **Invalidation**: the corpus version lives in Postgres and is bumped in the same transaction as
  the change. Redis runs with `volatile-lru`: every cache key has a TTL and the ingestion stream
  has none, so memory pressure can evict cache entries but never queued jobs.
* Every Redis failure counts as a cache miss (`insightrag_cache_errors_total`).

## §5.6 Rate limiting — `api/.../ratelimit/`

A token bucket in a Redis Lua script that reads Redis' own clock, so replicas with skewed clocks
agree. There are buckets per client for query and for ingest, plus one aggregate bucket that
every outbound LLM attempt draws from, retries included. Exhaustion returns 429 with
`Retry-After`. If Redis is down, each instance falls back to local buckets, which keeps the limit
per replica rather than failing open or closed.

## §5.7 Resilience

| Failure | Implementation | Test |
|---|---|---|
| LLM timeout / 5xx | `ResilientLlmClient`: `llm.retry.maxAttempts` total attempts, exponential backoff with full jitter, SDK retries disabled | `AnthropicContractTest`, `ResilientLlmClientTest` |
| LLM 429 | Honours `Retry-After` up to a cap; beyond the cap it degrades at once. The aggregate bucket sheds load first | same |
| All attempts fail | `DEGRADED` response with citations, not cached, HTTP 200 | `QueryServiceTest` |
| Worker crash mid-job | `XAUTOCLAIM` after the visibility timeout; fenced transactional rewrite | `test_reclaimed_stale_processing…` |
| Poison message | Per-document attempt counter → DLQ stream + FAILED with reason | `test_poison_message…`, e2e |
| Vector store down | 503 `store_unavailable` before any paid call; health `DOWN` | `DependencyFailureIT.PostgresDown` |
| Cache down | Direct path, health `DEGRADED`, and uploads still accepted (an outbox sweeper enqueues them later) | `DependencyFailureIT.RedisDown` |
| Unparseable document | FAILED with a readable `failureReason` | worker tests, e2e |

## §5.8 Consistent hashing — `worker/insightrag_worker/ring.py`, `queue.py`

The ring has 64 virtual nodes per worker and is built from a Redis membership set that workers
heartbeat into. A worker that receives a job whose document hashes to another live worker
transfers it with `XCLAIM` (ownership moves inside the consumer group; nothing is copied). The
owner drains its own pending list first. A message moves at most one hop, so two workers with
momentarily different membership views cannot bounce it back and forth. The tests check that
adding a worker moves only its arc (≈¼ of keys when going from 3 to 4 workers), where modulo
partitioning would move more than 70%.

## §6 Data model

The §6.1 tables are unchanged. The additions are explicit and commented in the migrations:
operational columns on `documents` (`storage_key`, `source`, fencing token, heartbeat, and
more), chunk character offsets, `query_log.outcome`/`client_id`, `corpus_state`, and the
evaluation extensions in V3.

## §7 Evaluation

Ground truth is written as **evidence snippets**, not chunk ids, because chunk ids change every
time the sweep re-chunks the corpus. Each run resolves the snippets to `expected_chunk_ids`.
Metric definitions are identical in Python (the offline sweep, run in CI without Docker) and Java
(the live `/eval/run` endpoint). Recall counts evidence groups, because with overlap one snippet
can live in two chunks. The gates: Recall@5 ≥ 0.85 and refusal accuracy ≥ 0.80 fail the build;
Precision@5 is reported against its 0.70 target.

## LLM provider

Claude through the official Anthropic Java SDK (`com.anthropic:anthropic-java`), model
`claude-opus-5`, adaptive thinking at effort `low`. Grounded Q&A over a handful of passages is a
latency-sensitive route that does not need deep reasoning. Server-side refusal fallbacks are
enabled, and a refusal that still happens surfaces as `DEGRADED`. The SDK's base URL is
configurable, which is how the contract tests point it at WireMock. Ollama and the offline
extractive provider sit behind the same `GenerationProvider` interface (§11, "Provider API
change").
