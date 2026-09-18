package com.insightrag.document;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.insightrag.common.ClientContext;
import com.insightrag.ratelimit.RateLimiter;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Document endpoints of §6.2. */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentService service;
    private final RateLimiter rateLimiter;

    public DocumentController(DocumentService service, RateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    public record UploadResponse(UUID documentId, DocumentStatus status, boolean duplicate, String filename) {
    }

    public record DocumentView(UUID id, String filename, String mimeType, long sizeBytes, String source,
                               DocumentStatus status, int chunkCount, String failureReason,
                               OffsetDateTime createdAt, OffsetDateTime indexedAt) {
        static DocumentView of(Document d) {
            return new DocumentView(d.id(), d.filename(), d.mimeType(), d.sizeBytes(), d.source(), d.status(),
                    d.chunkCount(), d.failureReason(), d.createdAt(), d.indexedAt());
        }
    }

    public record PageView(List<DocumentView> items, int page, int size, long total) {
    }

    /** 202 with the new id, or 200 with the existing id when the bytes were already uploaded. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> upload(@RequestPart("file") MultipartFile file,
                                                 @RequestParam(value = "source", required = false) String source,
                                                 Authentication auth) throws IOException {
        ClientContext client = ClientContext.from(auth);
        rateLimiter.checkIngest(client.clientId());
        DocumentService.UploadResult result;
        try (InputStream in = file.getInputStream()) {
            result = service.upload(file.getOriginalFilename(), source, in, client.userId());
        }
        Document d = result.document();
        UploadResponse body = new UploadResponse(d.id(), d.status(), result.duplicate(), d.filename());
        return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(body);
    }

    @GetMapping("/{id}")
    public DocumentView get(@PathVariable UUID id) {
        return DocumentView.of(service.get(id));
    }

    @GetMapping
    public PageView list(@RequestParam(defaultValue = "0") int page,
                         @RequestParam(defaultValue = "20") int size,
                         @RequestParam(required = false) DocumentStatus status,
                         @RequestParam(required = false) String source,
                         @RequestParam(required = false) String mimeType,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime createdFrom,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime createdTo) {
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), 200);
        DocumentRepository.Page result = service.list(new DocumentFilter(source, mimeType, createdFrom, createdTo), status, p, s);
        return new PageView(result.items().stream().map(DocumentView::of).toList(), p, s, result.total());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Re-queue a FAILED document (e.g. after fixing a provider outage that dead-lettered it). */
    @PostMapping("/{id}/retry")
    public ResponseEntity<DocumentView> retry(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(DocumentView.of(service.retry(id)));
    }
}
