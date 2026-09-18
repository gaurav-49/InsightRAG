package com.insightrag.document;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;

import com.insightrag.common.ApiException;
import com.insightrag.config.InsightRagProperties;
import com.insightrag.corpus.CorpusStateRepository;
import com.insightrag.metrics.InsightMetrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Journey A of §2.2 and FR-01..FR-04, FR-12. */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentRepository documents;
    private final CorpusStateRepository corpus;
    private final BlobStore blobs;
    private final IngestionQueue queue;
    private final TransactionTemplate tx;
    private final InsightMetrics metrics;
    private final Duration requeueAfter;

    public DocumentService(DocumentRepository documents, CorpusStateRepository corpus, BlobStore blobs,
                           IngestionQueue queue, TransactionTemplate tx, InsightMetrics metrics,
                           InsightRagProperties props) {
        this.documents = documents;
        this.corpus = corpus;
        this.blobs = blobs;
        this.queue = queue;
        this.tx = tx;
        this.metrics = metrics;
        this.requeueAfter = props.queue().requeueAfter();
    }

    public record UploadResult(Document document, boolean duplicate) {
    }

    /**
     * Validate, hash, dedupe, persist as QUEUED, enqueue, return — never waiting for
     * extraction or embedding (FR-03). A duplicate short-circuits before any embedding cost
     * (FR-02).
     */
    public UploadResult upload(String filename, String source, InputStream content, UUID uploadedBy) throws IOException {
        UploadValidator.FileType type = UploadValidator.typeOf(filename);
        BlobStore.Staged staged = blobs.stage(content);
        try {
            UploadValidator.checkContent(type, staged.head(), staged.size());
            var existing = documents.findByHash(staged.sha256());
            if (existing.isPresent()) {
                blobs.discard(staged);
                metrics.upload("duplicate");
                return new UploadResult(existing.get(), true);
            }
            String key = blobs.keyFor(staged.sha256(), type.extension());
            blobs.commit(staged, key);
            Document doc = new Document(UUID.randomUUID(), sanitize(filename), staged.sha256(), type.mimeType(),
                    staged.size(), DocumentStatus.QUEUED, null, 0, uploadedBy, blankToNull(source), key, null, null);
            if (!documents.insertIfAbsent(doc)) {
                // Lost a race with a concurrent upload of identical bytes.
                metrics.upload("duplicate");
                return new UploadResult(documents.findByHash(staged.sha256()).orElseThrow(), true);
            }
            enqueueBestEffort(doc);
            metrics.upload("accepted");
            log.info("document accepted: id={} type={} bytes={}", doc.id(), doc.mimeType(), doc.sizeBytes());
            return new UploadResult(documents.findById(doc.id()).orElse(doc), false);
        } catch (ApiException e) {
            blobs.discard(staged);
            metrics.upload("rejected");
            throw e;
        }
    }

    /**
     * The row is the durable record; the stream message is a notification. If Redis is down at
     * upload time the document stays QUEUED and the sweeper enqueues it later, so a Redis
     * outage delays ingestion but never loses an accepted upload.
     */
    private void enqueueBestEffort(Document doc) {
        try {
            queue.enqueue(doc.id(), doc.storageKey());
        } catch (RuntimeException e) {
            log.warn("enqueue failed for document {}; sweeper will retry: {}", doc.id(), e.getMessage());
        }
    }

    public Document get(UUID id) {
        return documents.findById(id).orElseThrow(() -> ApiException.notFound("Document"));
    }

    public DocumentRepository.Page list(DocumentFilter filter, DocumentStatus status, int page, int size) {
        return documents.list(filter, status, page, size);
    }

    /** FR-12: chunks and vectors go with the row (ON DELETE CASCADE); caches are invalidated. */
    public void delete(UUID id) {
        Document doc = get(id);
        tx.executeWithoutResult(s -> {
            if (!documents.delete(id)) {
                throw ApiException.notFound("Document");
            }
            corpus.bump();
            corpus.releaseEmbeddingModelIfEmpty();
        });
        try {
            blobs.delete(doc.storageKey());
        } catch (IOException | RuntimeException e) {
            log.warn("could not delete blob for document {}: {}", id, e.getMessage());
        }
        log.info("document deleted: id={}", id);
    }

    public Document retry(UUID id) {
        Document doc = get(id);
        if (doc.status() != DocumentStatus.FAILED) {
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "not_failed",
                    "Only FAILED documents can be retried (current status " + doc.status() + ")");
        }
        if (documents.requeueFailed(id)) {
            enqueueBestEffort(doc);
        }
        return get(id);
    }

    @Scheduled(fixedDelayString = "${insightrag.queue.sweep-interval:60s}", initialDelayString = "30s")
    public void sweepQueued() {
        try {
            for (Document d : documents.findStaleQueued(requeueAfter, 100)) {
                queue.enqueue(d.id(), d.storageKey());
                documents.markEnqueued(d.id());
                log.info("re-enqueued stale QUEUED document {}", d.id());
            }
        } catch (DataAccessException | IllegalStateException e) {
            log.debug("queue sweep skipped: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.warn("queue sweep failed: {}", e.getMessage());
        }
    }

    static String sanitize(String filename) {
        String name = filename == null ? "upload" : filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").strip();
        if (name.isEmpty()) {
            name = "upload";
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
