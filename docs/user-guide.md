# InsightRAG user guide

## What InsightRAG is for

InsightRAG answers questions about **your own documents**. You upload policies, contracts,
manuals, specifications, meeting minutes, résumés and so on. Then you ask questions in plain
English and get a short answer **with citations**: which document, and which page or section
it came from.

It is not a general chatbot. It answers only from what is in the uploaded documents. When the
documents don't contain the answer, it says so instead of guessing. That is the `NO_ANSWER`
result.

### Typical uses

| Who | Example question | Why it helps |
|---|---|---|
| Employee | "How many days notice must a level L5 engineer give?" | Get the answer and the policy section instead of reading a 20-page HR policy |
| Finance or procurement | "What is the liability cap in the Stratus agreement?" | Find the clause in a contract immediately, with the source to verify it |
| Engineer | "Can we deploy to production on a Friday?" | Search the engineering handbook and runbooks |
| Sales or support | "What is the maximum payload of the ARM-X2?" | Look up product specifications |
| Recruiter | "What Java and Spring Boot experience does the candidate have?" | Screen uploaded résumés |
| Manager | "What revenue is planned for 2026?" | Look up board or meeting minutes |

Every answer lists its sources, so you can open the document and check.

## How it works (in one minute)

1. **Upload.** You upload a file. It is stored and queued straight away; the upload doesn't
   wait for processing.
2. **Indexing.** A background worker extracts the text, splits it into small overlapping
   passages ("chunks"), turns each passage into an embedding (a numeric vector) and stores it.
   The document's status goes `QUEUED → PROCESSING → INDEXED`.
3. **Ask.** Your question is turned into a vector, and the most similar passages are found.
4. **Answer or refuse.** If no passage is similar enough (it must clear the *relevance
   threshold*), you get `NO_ANSWER`. Otherwise an answer is written **only from those
   passages**, with citations such as `[1]` and `[2]`.
5. **Cache.** Repeated or near-identical questions are answered from cache in milliseconds
   (`L1` = same question, `L2` = near-identical wording).

## Using the web console

Open **http://localhost:8080** (start the stack first; see [Starting and stopping](#starting-and-stopping)).

### 1. Get access

Click **Get dev token** at the top right. A token appears in the **Access token** box at the
bottom. It lets you both ask questions and upload documents. It lasts one hour; click the
button again when it expires.

### 2. Upload documents

1. In **Corpus**, click **Choose file** and pick a PDF, DOCX, TXT or Markdown file (up to 25 MB).
2. Optionally type a **source label**, for example `hr`, `contracts` or `resumes`. Labels let you
   restrict searches to one group of documents later (see [Filters](#filters-api)).
3. Click **Upload**, then **Refresh** after a few seconds.

The document appears in the list with its status:

| Status | Meaning |
|---|---|
| `QUEUED` | Accepted and waiting for a worker |
| `PROCESSING` | Text is being extracted, chunked and embedded |
| `INDEXED` | Ready: questions can now find it. **Chunks** shows how many passages it was split into |
| `FAILED` | Could not be processed; the reason is shown under the file name (for example a scanned or image-only PDF, which has no text to extract) |

Uploading the same file twice does nothing new. You get the existing document back
("Already uploaded").

### 3. Ask questions

Type a question and click **Ask**. Tick **stream** to see the answer appear word by word.

Next to the Ask button you see two labels:

* **Status**: `ANSWERED`, `NO_ANSWER` or `DEGRADED` (explained [below](#what-the-results-mean)).
* **Where the answer came from and how long it took**: `MISS · 16 ms` means it was computed
  fresh; `L1 · 3 ms` or `L2 · 5 ms` means it came from cache.

Citations are listed under the answer, for example:
`1. hr-leave-and-notice-policy.md, "2. Notice Periods" — score 0.41`.
The score is how similar the passage is to your question.

### Worked example (sample corpus)

After `make seed` (which uploads the sample documents), try these:

| Question | Expected result |
|---|---|
| What notice must a level L5 engineer give? | `ANSWERED`: 60 days, cited from *hr-leave-and-notice-policy.md* |
| What is the maximum payload of the ARM-X2? | `ANSWERED`: 7 kilograms, cited from the product spec |
| Can we deploy to production on a Friday? | `ANSWERED`: no Friday deploys, cited from the engineering handbook |
| What is the liability cap in the vendor agreement? | `ANSWERED`: 150 percent of fees, cited from the contract |
| WHAT NOTICE MUST A LEVEL L5 ENGINEER GIVE? | Same answer, from cache (`L1`) |
| What is the recipe for sourdough bread? | `NO_ANSWER`: nothing in the documents covers it |

## What the results mean

| Result | Meaning | What to do |
|---|---|---|
| `ANSWERED` | An answer was written from the cited passages | Check the citations if it matters |
| `NO_ANSWER` | No passage was similar enough to your question. The message shows the best score and the threshold, e.g. `best 0.09 < 0.15` | Rephrase with the words the document itself uses, or accept that the documents don't contain it |
| `DEGRADED` | Relevant passages were found, but the language model failed after retries | The passages are still listed as citations; ask again shortly |

## Why "summarize the resume" returned NO_ANSWER

This is expected with the default setup, for two reasons.

**1. It answers questions; it doesn't summarise whole documents.** Each answer is built from the
few passages most similar to the question (at most 5). "Summarize the resume" isn't a question
*about* any passage, and a real summary would need the whole document. Summarisation is outside
what the system is designed to do.

**2. The default setup matches words, not meaning.** Out of the box, InsightRAG runs fully offline:

* **Embeddings: `hash-v1`**. This matches **words**, not meaning. A résumé usually never
  contains the words "summarize" or "resume", so the best score was only 0.09, below the 0.15
  threshold, and the system correctly refused.
* **Answers: `extractive`**. This quotes the best-matching sentences instead of writing an answer.

Ask specific questions using words that appear in the document. With the offline setup, these
kinds of questions worked against an uploaded résumé:

| Question | Result |
|---|---|
| summarize the resume | `NO_ANSWER`: not a question about a passage; the words don't appear in the résumé |
| technical skills | `ANSWERED` from the résumé |
| What Java and Spring Boot experience does he have? | `ANSWERED` from the résumé |
| education degree university | `ANSWERED` from the résumé |
| Which projects has he built? | `ANSWERED` from the résumé |
| work experience | Matched the *engineering handbook* instead, because the word "work" appears there more |

The last row shows the weakness of word matching. To limit a question to résumés, use a source
label or a file-type filter (see [Filters](#filters-api)).

### Getting much better answers

Switch to a semantic embedding model and a real language model in `.env`:

```
EMBEDDING_PROVIDER=openai
EMBEDDING_API_KEY=sk-...
LLM_PROVIDER=anthropic
ANTHROPIC_API_KEY=sk-ant-...
```

Semantic embeddings match meaning, so "How much notice must I give?" finds "notice period".
Claude then writes a real answer from the passages instead of quoting sentences. After switching
embeddings you must re-upload the documents (vectors from different models can't be mixed), then
run `make sweep` or `make eval` to re-tune `RETRIEVAL_FLOOR`. The steps are in
[runbook.md](runbook.md#change-the-embedding-model).

### Tips for good questions

* Ask one specific thing: "What is the hotel limit in London?", not "tell me about travel".
* Use the document's vocabulary, especially with the offline `hash` embedding.
* Name the thing you mean: "ARM-X2 warranty", not "the warranty".
* Use source labels to keep unrelated documents from competing (a résumé and an HR policy both
  mention "work").

## Using the API (for scripts and integrations)

Everything the console does is available over HTTP. Get a token first:

```bash
TOKEN=$(curl -s -XPOST localhost:8080/api/v1/auth/dev-token -H 'Content-Type: application/json' -d '{"scopes":["query","admin"]}' | jq -r .accessToken)
```

Upload with a source label:

```bash
curl -s -XPOST localhost:8080/api/v1/documents -H "Authorization: Bearer $TOKEN" -F "file=@/path/to/Resume.pdf" -F "source=resumes" | jq
```

The response is `202` with `{"documentId": "...", "status": "QUEUED"}`, or `200` with
`"duplicate": true` if the file was already uploaded.

Check the status:

```bash
curl -s localhost:8080/api/v1/documents/DOCUMENT_ID -H "Authorization: Bearer $TOKEN" | jq
```

Ask a question:

```bash
curl -s localhost:8080/api/v1/query -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"question":"What notice must a level L5 engineer give?"}' | jq
```

A successful answer looks like this:

```json
{
  "answer": "Senior individual contributors and managers at levels L5 and L6 must give 60 days notice [1].",
  "status": "ANSWERED",
  "citations": [
    { "ref": 1, "document": "hr-leave-and-notice-policy.md", "section": "2. Notice Periods", "score": 0.41 }
  ],
  "cached": false,
  "cacheTier": "MISS",
  "latencyMs": 18
}
```

### Filters (API)

Restrict a question to documents with a given source label, file type or upload date:

```bash
curl -s localhost:8080/api/v1/query -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"question":"work experience","filters":{"source":"resumes"}}' | jq
```

Available filters: `source`, `mimeType` (e.g. `application/pdf`), `createdFrom` and `createdTo`
(ISO timestamps, e.g. `2026-09-01T00:00:00Z`).

### Other operations

| Task | Command |
|---|---|
| List documents | `curl -s "localhost:8080/api/v1/documents?status=INDEXED&source=resumes" -H "Authorization: Bearer $TOKEN" \| jq` |
| Delete a document | `curl -XDELETE localhost:8080/api/v1/documents/DOCUMENT_ID -H "Authorization: Bearer $TOKEN"` |
| Retry a failed document | `curl -XPOST localhost:8080/api/v1/documents/DOCUMENT_ID/retry -H "Authorization: Bearer $TOKEN"` |
| Stream an answer | `curl -N localhost:8080/api/v1/query/stream -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"question":"..."}'` |
| Health | `curl -s localhost:8080/api/v1/health \| jq` |

Limits: 60 questions per minute per user (bursts of 20) and 20 uploads per minute (bursts of 10).
Beyond that the API returns `429` with a `Retry-After` header saying how many seconds to wait.

## Starting and stopping

From the project folder:

```bash
docker compose up -d --build --wait
```

```bash
make seed
```

```bash
docker compose down
```

`make seed` loads the sample documents; skip it if you only want your own files.
`docker compose down` stops the stack and keeps your documents. To delete everything, run
`make clean`.

## Checking answer quality

For administrators: `make eval` runs 147 labelled questions against the current documents and
reports how often the right passage was found (Recall@5), how many retrieved passages were
relevant (Precision@5), and how often unanswerable questions were correctly refused. Run it after
changing any setting. The [runbook](runbook.md) covers operations in depth.
