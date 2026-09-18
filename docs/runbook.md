# InsightRAG runbook

For the platform operator persona (§2.1): keep the service healthy and within its cost budget.

## Signals to watch

All metrics are served at `GET /api/v1/metrics` (API) and at `:9100/metrics` on each worker.

| Signal | Metric | Look for |
|---|---|---|
| Ingestion backlog | `insightrag_ingest_queue_lag`, `insightrag_ingest_queue_oldest_age_seconds` (worker) | Lag growing or oldest age above a few minutes: workers are under-provisioned or failing |
| Dead letters | `insightrag_ingest_dead_letters` | Any increase; see [Dead letters](#dead-letters) |
| Cache effectiveness | `insightrag_queries_total{tier}` | `(L1+L2)/all` under 30% means cost-control is off target |
| No-answer rate | `insightrag_queries_total{outcome="NO_ANSWER"}` | A sharp rise means a corpus gap or a retrieval regression; run `make eval` to tell them apart |
| Retrieval drift | `insightrag_retrieval_top_score` histogram | The median moving down means the query population and the corpus are diverging |
| Spend | `insightrag_llm_tokens_total{type}`, `insightrag_llm_cost_usd_total` | Hourly rate against budget |
| Provider health | `insightrag_llm_calls_total{outcome}` | `retry` and `failure` rising means the provider is degraded; `refused` rising means content is triggering refusals |
| Limiter | `insightrag_ratelimit_rejections_total{scope}` | `llm` rejections mean the aggregate provider budget is saturated |
| Cache or Redis errors | `insightrag_cache_errors_total{tier}` | Non-zero means Redis is unhealthy (queries continue uncached) |

`GET /api/v1/health` returns `UP`, `DEGRADED` (Redis down: queries still answered, ingestion
paused) or `DOWN` with HTTP 503 (Postgres down, or embedding model mismatch).

## Scaling

* **Workers**: `docker compose up -d --scale worker=N`. The consumer group spreads jobs. New
  workers join the hash ring within `WORKER_MEMBERSHIP_TTL_S`, and only their arc of documents
  moves to them.
* **API**: stateless. Limiter, caches and corpus version live in Redis and Postgres, so run as
  many replicas as needed behind any load balancer, with no session affinity.
* **Throughput knobs**: `WORKER_BATCH_SIZE` (embeddings per provider call) and
  `RATELIMIT_LLM_PER_MINUTE` (aggregate provider budget).

## Failure handling

| Symptom | Likely cause | Action |
|---|---|---|
| Health `DEGRADED`, cache errors | Redis down | Restore Redis. Uploads are still accepted and stay QUEUED; the API's sweeper re-enqueues them within `insightrag.queue.requeue-after` once Redis is back |
| Health `DOWN`, queries return 503 `store_unavailable` | Postgres down | Restore Postgres. The API fails fast and never calls the LLM without context |
| Queries `DEGRADED` | LLM provider failing after retries | Check `insightrag_llm_calls_total{outcome}` and provider status. Users still get the relevant passages |
| Documents stuck in `PROCESSING` | Worker died mid-job | Nothing to do. After `WORKER_VISIBILITY_TIMEOUT_MS` another worker reclaims the job and the fenced rewrite replaces partial work |
| Documents stuck in `QUEUED` | No workers running, or Redis was down at upload | Check `docker compose ps worker`; the sweeper re-enqueues stale QUEUED documents |
| Document `FAILED` with a reason | Unparseable, encrypted, image-only, or too large | Fix the source file and upload it again (new bytes mean a new document) |

## Dead letters

A job is dead-lettered after `WORKER_MAX_ATTEMPTS` transient failures (provider outage,
repeated crashes) or when its message is malformed. The document is marked `FAILED` with the
reason.

```bash
docker compose exec redis redis-cli XRANGE insightrag:ingest:dlq - + COUNT 20
```

Once the cause is fixed (for example the embedding provider is back), re-queue each document:

```bash
curl -XPOST localhost:8080/api/v1/documents/$DOCUMENT_ID/retry -H "Authorization: Bearer $ADMIN_TOKEN"
```

## Change the embedding model

Vectors from different models are not comparable. The system records the model in
`corpus_state.embedding_model` and refuses to mix models: the worker fails the job and the API
returns 503 `embedding_model_mismatch`. To switch:

1. Stop the workers: `docker compose stop worker`.
2. Export the list of documents (`GET /api/v1/documents`), then delete them. Deleting the last
   chunk clears `corpus_state.embedding_model`.
3. Set `EMBEDDING_PROVIDER` / `EMBEDDING_MODEL` for **both** the API and the worker, then
   restart them.
4. Re-upload the source files.
5. Run `make sweep` against the new model (or `make eval` against the live stack), then set
   `RETRIEVAL_FLOOR` / `RETRIEVAL_RELATIVE_FLOOR` and the chunk parameters from the result.
   Floors are model-specific.

## Quality regression

`make eval` loads `eval/questions.json`, scores the live corpus, prints the result next to the
previous run and writes `eval/reports/latest.json`. The per-question section lists which
answerable questions lost their evidence and which unanswerable ones were not refused. CI fails
the build when Recall@5 < 0.85 or refusal accuracy < 0.80.

To add ground truth, append to `eval/questions.json`. Evidence snippets must be exact substrings
of the corpus; `pytest -m quality` checks this.

## Secrets and configuration

* `JWT_SECRET`: the HS256 signing key (≥ 32 bytes). Rotating it invalidates every issued token.
* `DEV_TOKEN_ENABLED` **must be false** outside local development.
* `ANTHROPIC_API_KEY` is read only by the API; `EMBEDDING_API_KEY` by both services.
* Uploaded document text is never logged. Logs carry ids, sizes, counts and error summaries.

## Load testing

```bash
make load
```

The script runs 50 req/s for 2 minutes, drawing questions Zipf-style from the labelled set with
paraphrased repeats. Thresholds: p95 miss < 4 s, p95 hit < 200 ms, at least 30% of queries
served from cache, errors < 1%. For queue behaviour under an ingestion burst, run
`load/ingest-burst.js` and watch `insightrag_ingest_queue_lag` drain.
