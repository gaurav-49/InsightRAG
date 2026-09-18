# Requirements traceability

Every functional and non-functional requirement of the design document, mapped to where it is
implemented and what verifies it.

## Functional requirements

| ID | Requirement | Implementation | Verified by |
|---|---|---|---|
| FR-01 | PDF, DOCX, TXT, Markdown up to a size limit | `UploadValidator` (extension + content sniffing), `BlobStore` (streaming size limit), worker `extract.py` | `UploadValidatorTest`, `test_extract.py`, `ApiFlowIT.unsupportedOrMislabelledFilesAreRejected`, e2e (all four formats) |
| FR-02 | Reject duplicates by content hash before embedding cost | SHA-256 while streaming; `INSERT … ON CONFLICT (content_hash)` | `ApiFlowIT.duplicateUploadShortCircuits…`, `SchemaIT.contentHashIsUnique`, e2e |
| FR-03 | Async ingestion; upload does not wait | `DocumentService.upload` → 202 QUEUED; Redis Streams; outbox sweeper | `ApiFlowIT.uploadReturnsImmediately…`, `DependencyFailureIT.RedisDown` |
| FR-04 | Status QUEUED/PROCESSING/INDEXED/FAILED via API | `GET /api/v1/documents/{id}`; `chk_status` | `ApiFlowIT.statusEndpointReflectsIndexing`, worker integration tests |
| FR-05 | Overlapping chunks, configurable size and overlap | `chunker.py`, `CHUNK_SIZE_TOKENS`, `CHUNK_OVERLAP_TOKENS` | `test_chunker.py` (boundaries, overlap, exact spans) |
| FR-06 | Embedding per chunk, persisted | `RetryingEmbedder` (batched) + `db.write_chunks` (COPY) | `test_pdf_upload_reaches_indexed…` |
| FR-07 | Answer from retrieved passages only | `PromptBuilder.SYSTEM` grounding rules; context holds only retrieved passages | `PromptBuilderTest`, `AnthropicContractTest.sendsAGroundedRequest…` |
| FR-08 | Citations with every answer | `QueryService.cited` (cited subset, or all passages) | `QueryServiceTest`, `ApiFlowIT.answeredQuery…`, e2e |
| FR-09 | Explicit NO_ANSWER below the relevance floor | `RetrievalService.applyFloor` → NO_ANSWER, no LLM call | `QueryServiceTest.noPassageAboveFloor…`, `ApiFlowIT.unanswerable…`, refusal accuracy |
| FR-10 | Serve repeat and near-duplicate questions from cache | `AnswerCache` (L1), `SemanticCache` (L2) | `QueryServiceTest`, `ApiFlowIT` (MISS → L1 → L2), e2e, k6 `cache_served` threshold |
| FR-11 | Per-client limits on query and ingestion | `RateLimiter` (Redis Lua token bucket) | `TokenBucketTest`, `ApiFlowIT.perClientRateLimit…` |
| FR-12 | Delete with chunks and vectors | `ON DELETE CASCADE`, corpus version bump | `ApiFlowIT.deletingADocument…`, `SchemaIT` |
| FR-13 | Filter by source, type, date range | `DocumentFilter` in the same SQL as the vector search | `ApiFlowIT.metadataFiltersApply…`, e2e |
| FR-14 | Evaluation endpoint | `PUT /eval/questions`, `POST /eval/run`, `GET /eval/runs/latest` | `ApiFlowIT.evaluationEndpoint…`, `scripts/eval_gate.py` in CI |
| FR-15 | Stream answer tokens | `POST /api/v1/query/stream` (SSE) via `GenerationProvider.stream` | `ApiFlowIT.streaming…`, `AnthropicContractTest.streams…`, e2e |

## Non-functional requirements

| ID | Requirement | Implementation | Verified by |
|---|---|---|---|
| NFR-01 | p95 < 4 s miss, < 200 ms hit | L1 before any embedding; effort `low`; bounded context; HNSW | k6 `load/query-load.js` thresholds; e2e checks cache-hit latency |
| NFR-02 | Workers scale out without redistributing the keyspace | Consumer group + consistent-hash pinning with virtual nodes | `test_ring.py` (≈¼ of keys move from 3→4 workers vs >70% for modulo), `test_job_is_handed_to_its_ring_owner` |
| NFR-03 | Single transient LLM failure not user-visible | `ResilientLlmClient` retry, backoff, jitter, Retry-After | `AnthropicContractTest`, `ResilientLlmClientTest` |
| NFR-04 | Every LLM call rate-limited, cached, logged with tokens | Aggregate LLM bucket per attempt; L1/L2; `query_log.prompt_tokens/completion_tokens`; token and cost counters | `ResilientLlmClientTest.everyAttemptTakesAnLlmRateLimitToken`, `QueryServiceTest.everyQueryIsLogged…` |
| NFR-05 | Retrieval quality measured automatically in CI | Offline sweep gate (pytest `quality`) and live `eval_gate.py` | CI jobs `worker`, `e2e` |
| NFR-06 | JWT auth; content never logged | Spring Security resource server (HS256, scopes); logs carry ids and counts only | `ApiFlowIT.unauthenticatedAndUnderScoped…` |
| NFR-07 | Structured logs; queue depth, hit rate, spend, scores as metrics | JSON logs (logstash format / worker `JsonFormatter`); Micrometer + worker `prometheus_client` | `ApiFlowIT.healthAndMetrics…`, e2e |
| NFR-08 | Full stack locally via Compose, no cloud | `docker-compose.yml`; offline `hash` + `extractive` providers; optional Ollama | e2e job in CI |
| NFR-09 | Schema from migrations; tests use the same scripts | `db/migration` packaged into the API; the worker tests apply the same files | `SchemaIT`, `test_worker_integration.py` |

## Success criteria (§1.3)

| Dimension | Target | Status |
|---|---|---|
| Retrieval quality | Recall@5 ≥ 0.85, Precision@5 ≥ 0.70 | 0.909 / 0.704 with `hash-v1` on the labelled set (offline sweep, CI-gated) |
| Latency | p95 < 4 s miss, < 200 ms hit | Enforced as k6 thresholds (`make load`); hit latency checked in e2e |
| Ingestion throughput | 100-page document indexed within 3 minutes | Observable as `insightrag_worker_job_duration_seconds`; batch size `WORKER_BATCH_SIZE` |
| Cost control | ≥ 30% of queries served from cache | k6 `cache_served` threshold; `insightrag_queries_total{tier}` |
| Correctness | Citations always; no answer without evidence | FR-08 / FR-09 rows above |
| Reliability | No user-visible error from one transient failure | NFR-03 row above |
