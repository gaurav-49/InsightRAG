# InsightRAG

Distributed document Q&A: source-cited answers to natural-language questions over a private
corpus, built to the *InsightRAG Software Design Document v1.0*.

The AI part is small on purpose. Most of the code is the machinery around it: asynchronous
ingestion, deduplication, a two-tier cache, token-bucket rate limiting, retries with backoff,
an explicit "not in the corpus" answer, and a retrieval evaluation harness that gates CI.

```
            ┌───────────── POST /api/v1/documents ──────────────┐
 client ───►│ API (Java 17, Spring Boot)                        │──XADD──► Redis Stream ──► worker × N (Python)
            │  JWT · validate · SHA-256 dedup · 202 QUEUED      │                           extract → chunk → embed
            │                                                   │                           → COPY chunks + INDEXED
            │  POST /api/v1/query                               │                                 │
            │  rate limit → L1 exact → embed → L2 semantic      │◄──── Postgres + pgvector ◄──────┘
            │  → HNSW top-k → relevance floor → NO_ANSWER |     │      (documents, chunks, HNSW,
            │    grounded prompt → LLM (retry) → cite → cache   │       query_log, eval, corpus_state)
            └───────────────────────────────────────────────────┘
```

| Component | Where | Responsibility |
|---|---|---|
| API service | [api/](api/) | Auth, validation, orchestration, caching, rate limiting, answer synthesis, eval endpoint |
| Embedding worker | [worker/](worker/) | Extraction (PDF/DOCX/TXT/MD), chunking, embedding, transactional vector writes |
| Schema | [db/migration/](db/migration/) | The only schema definition. Flyway applies it, and both test suites run against it |
| Contracts | [contracts/](contracts/) | Queue message format and embedding normalisation, with cross-language fixtures |
| Evaluation set | [eval/](eval/) | 9-document corpus and 147 labelled questions (20 deliberately unanswerable) |
| Load tests | [load/](load/) | k6: sustained queries with a Zipf repeat distribution; ingestion burst |

## Quick start (no cloud account, no API key)

```bash
docker compose up -d --build
```

```bash
make seed
```

```bash
make e2e
```

```bash
make eval
```

Then open http://localhost:8080 for the console, or call the API:

```bash
TOKEN=$(curl -s -XPOST localhost:8080/api/v1/auth/dev-token -H 'Content-Type: application/json' -d '{"scopes":["query","admin"]}' | jq -r .accessToken)
```

```bash
curl -s localhost:8080/api/v1/query -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"question":"What notice must a level L5 engineer give?"}' | jq
```

By default the stack runs fully offline:

* **Embeddings: `hash`**. `hash-v1` is a deterministic lexical feature-hashing embedding that
  the Java and Python sides implement identically ([contracts/README.md](contracts/README.md)).
* **Generation: `extractive`**. It quotes the best-matching evidence sentences with citations.
  Every code path (grounding, citations, caching, streaming, NO_ANSWER) runs without a model.

For real answers, switch providers in `.env` (copy it from [.env.example](.env.example)):

| Setting | Options |
|---|---|
| `LLM_PROVIDER` | `extractive` (default) · `anthropic` (Claude via the official Java SDK; default model `claude-opus-5`; set `ANTHROPIC_API_KEY`) · `ollama` (`docker compose --profile ollama up`) |
| `EMBEDDING_PROVIDER` | `hash` (default) · `openai` (`text-embedding-3-small`, 1536-d) · `ollama` (`nomic-embed-text`, zero-padded to 1536) |

Changing the embedding provider means re-embedding the corpus. The system refuses to mix
models (see the [runbook](docs/runbook.md#change-the-embedding-model)). After a change, re-run
`make sweep` and tune the retrieval floor for the new model.

## API

| Method & path | Scope | Purpose |
|---|---|---|
| `POST /api/v1/documents` (multipart `file`, optional `source`) | admin | Upload. Returns **202** `{documentId, status: QUEUED}`, or **200** with the existing id for identical bytes |
| `GET /api/v1/documents/{id}` | query | Status: `QUEUED` / `PROCESSING` / `INDEXED` / `FAILED` (+ `failureReason`) |
| `GET /api/v1/documents?status=&source=&mimeType=&createdFrom=&createdTo=&page=&size=` | query | Corpus listing |
| `DELETE /api/v1/documents/{id}` | admin | Removes the document, its chunks and vectors; bumps the corpus version |
| `POST /api/v1/documents/{id}/retry` | admin | Re-queue a `FAILED` document |
| `POST /api/v1/query` `{question, filters?}` | query | `{answer, status, citations[], cached, cacheTier, latencyMs}` |
| `POST /api/v1/query/stream` | query | Server-Sent Events: `meta` → `token`… → `done` |
| `PUT /api/v1/eval/questions` · `POST /api/v1/eval/run` · `GET /api/v1/eval/runs/latest` | admin | Evaluation harness |
| `GET /api/v1/health` | open | `UP` / `DEGRADED` (Redis down) / `DOWN` (Postgres down, 503) |
| `GET /api/v1/metrics` | open | Prometheus exposition |
| `POST /api/v1/auth/dev-token` | open, dev only | Mints a token when `DEV_TOKEN_ENABLED=true` |

`status` is `ANSWERED`, `NO_ANSWER` (nothing cleared the relevance floor, so the LLM was not
called), or `DEGRADED` (evidence was found but generation failed after retries, so the
passages come back as citations).

## Tests

| Layer | Command | What it covers |
|---|---|---|
| Unit | `make test` | Chunk boundaries, token-bucket refill maths, consistent-hash ring, prompt budget, cache keys, retry/backoff, metrics definitions, Java↔Python embedding parity |
| Contract | `make test-api` | The real Anthropic SDK client against WireMock: 5xx, 429 + Retry-After, timeout, malformed body, 401, refusal, streaming |
| Integration | `make test-api-it test-worker-it` | Testcontainers Postgres+pgvector and Redis, using the same migration scripts: upload → dedup → queue → worker → search → L1/L2 → delete; worker redelivery, crash reclaim, dead-letter, ring handoff, fencing; Redis-down and Postgres-down behaviour |
| Quality | `make test-worker` (offline) · `make eval` (live) | Recall@5, Precision@5, MRR, refusal accuracy, gated in CI |
| End-to-end | `make e2e` | Compose stack: every format, NO_ANSWER, streaming, dead-letter, invalidation |
| Load | `make load` | k6: p95 latency by cache tier, share of cache-served queries |

## Measured retrieval quality (hash-v1, offline sweep)

`make sweep` runs the grid of §7.3 over chunk size, overlap, absolute floor and relative floor,
then selects by measured Recall@5. The selected configuration is the Compose default:

| Chunk / overlap | Floor / relative | Recall@5 | Precision@5 | MRR | Refusal accuracy |
|---|---|---|---|---|---|
| 128 / 0 | 0.15 / 0.7 | **0.909** | **0.704** | 0.848 | **0.80** |

These numbers are for the offline lexical `hash-v1` embedding and are reproducible anywhere.
With a semantic embedding model, run the sweep again. The full grid is in
[eval/reports/sweep-hash-v1.json](eval/reports/sweep-hash-v1.json).

## Documentation

* [docs/user-guide.md](docs/user-guide.md): what it's for and how to use it, with examples
* [docs/design-notes.md](docs/design-notes.md): how each design-doc decision is implemented, and the deliberate deviations
* [docs/traceability.md](docs/traceability.md): every FR/NFR mapped to code and tests
* [docs/runbook.md](docs/runbook.md): operating, scaling, failure handling, dead letters, model changes
* [contracts/README.md](contracts/README.md): what the Java and Python sides must agree on
