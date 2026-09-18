# Cross-service contracts

The API (Java) and the embedding worker (Python) meet only at the queue and the database
(design doc §4.5, "Worker language"). This directory pins down the few things both sides must
agree on byte-for-byte. Tests in both code bases load these files.

## Ingestion job message (Redis Stream `insightrag:ingest`)

| Field        | Type   | Notes                                        |
|--------------|--------|----------------------------------------------|
| `documentId` | UUID   | Row in `documents`                           |
| `storageKey` | string | Path relative to the shared blob root        |
| `enqueuedAt` | epoch ms | Diagnostics only                           |

Consumer group `insightrag-workers`. Dead letters go to `insightrag:ingest:dlq` with
`documentId`, `reason`, `attempts`.

## Embedding normalisation (§5.3 step 14)

Both sides feed the embedding model `normalize_for_embedding(text)`: Unicode NFKC, collapse
all whitespace runs to one space, trim. Chunks are embedded as
`"{section_heading}\n{content}"` when a heading is present; queries are embedded as the
question alone.

## `hash` embedding provider (`hash-v1`)

A deterministic, offline feature-hashing embedding used for development, CI and the
evaluation sweep. It is lexical, not semantic — its purpose is to let the whole stack run with
no network and no token spend (NFR-08) while still exercising every code path.

1. `NFKC` normalise, lowercase (ASCII corpus assumed; v1 is English-only).
2. Tokens = regex `[a-z0-9]+`.
3. Drop stop words (list below — identical in both implementations).
4. Light stemming, first matching rule wins, applied once:
   - length > 4 and ends with `ies` → replace with `y`
   - length > 5 and ends with `ing` → strip `ing`
   - length > 4 and ends with `ed`  → strip `ed`
   - length > 3 and ends with `s` but not `ss` → strip `s`
5. Features: every term with weight 1.0, and every adjacent term pair `a_b` with weight 0.5.
6. Per feature, `tf` = summed weight; value = `w * (1 + ln(count))` where `w` is the feature
   weight and `count` its occurrence count.
7. `h = FNV-1a-32(utf8(feature))`; index = `h mod 1536`; sign = `-1` if bit 20 of `h` is set
   else `+1`. Values are accumulated (collisions add).
8. L2-normalise. An input with no features maps to the unit vector `e0`.

`hash-embedding-fixtures.json` holds reference vectors (sparse form) that both test suites
assert against, so drift between implementations fails CI instead of silently making query and
chunk vectors incomparable.

Stop words:

```
a about above after again against all am an and any are as at be because been before being
below between both but by can could did do does doing down during each few for from further
had has have having he her here hers herself him himself his how i if in into is it its itself
just me more most my myself no nor not of off on once only or other our ours ourselves out
over own same she should so some such than that the their theirs them themselves then there
these they this those through to too under until up very was we were what when where which
while who whom why will with would you your yours yourself yourselves
```

## Other providers

* `ollama` — `nomic-embed-text` (768-d) zero-padded to 1536. Zero padding preserves cosine
  similarity exactly, so the fixed-width `VECTOR(1536)` column works for every provider.
* `openai` — `text-embedding-3-small`, native 1536-d.

Switching provider requires re-embedding the corpus: vectors from different models are not
comparable (§5.3). `corpus_state.embedding_model` records the model that produced the stored
vectors; the worker refuses to write and the API refuses to search under a different model, so
the mix fails loudly. The runbook's "change embedding provider" procedure resets and re-ingests.
