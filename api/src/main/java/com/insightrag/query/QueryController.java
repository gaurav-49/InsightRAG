package com.insightrag.query;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;

import com.insightrag.common.ApiException;
import com.insightrag.common.ClientContext;
import com.insightrag.ratelimit.RateLimiter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/query")
public class QueryController {

    private static final Logger log = LoggerFactory.getLogger(QueryController.class);

    private final QueryService service;
    private final RateLimiter rateLimiter;
    // Bounded: each open stream holds a thread while the provider generates.
    private final ExecutorService streams = new ThreadPoolExecutor(4, 128, 60, TimeUnit.SECONDS, new SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, "query-stream");
                t.setDaemon(true);
                return t;
            });

    public QueryController(QueryService service, RateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public QueryResponse query(@Valid @RequestBody QueryRequest request, Authentication auth) {
        String client = ClientContext.from(auth).clientId();
        rateLimiter.checkQuery(client);
        return service.answer(request, client);
    }

    /**
     * FR-15: Server-Sent Events. {@code meta} (citations, cache tier) first, then {@code token}
     * deltas, then {@code done} with the final status. Rate limiting and validation happen
     * before the stream opens, so they surface as ordinary HTTP errors.
     */
    @PostMapping(value = "/stream", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody QueryRequest request, Authentication auth) {
        String client = ClientContext.from(auth).clientId();
        rateLimiter.checkQuery(client);
        SseEmitter emitter = new SseEmitter(120_000L);
        try {
            streams.submit(() -> runStream(request, client, emitter));
        } catch (RejectedExecutionException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "too_many_streams", "Too many concurrent streams; retry shortly");
        }
        return emitter;
    }

    private void runStream(QueryRequest request, String client, SseEmitter emitter) {
        try {
            QueryResponse done = service.stream(request, client, new QueryService.StreamSink() {
                @Override
                public void meta(List<Citation> citations, String cacheTier) {
                    send(emitter, "meta", Map.of("citations", citations, "cacheTier", cacheTier));
                }

                @Override
                public void token(String text) {
                    send(emitter, "token", Map.of("text", text));
                }
            });
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("status", done.status());
            summary.put("cached", done.cached());
            summary.put("cacheTier", done.cacheTier());
            summary.put("latencyMs", done.latencyMs());
            if (done.reason() != null) {
                summary.put("reason", done.reason());
            }
            if (done.status() == QueryStatus.DEGRADED) {
                summary.put("citations", done.citations());
            }
            send(emitter, "done", summary);
        } catch (ClientGone e) {
            // client disconnected; nothing left to tell it
        } catch (ApiException e) {
            trySend(emitter, "error", Map.of("code", e.code(), "message", e.getMessage()));
        } catch (RuntimeException e) {
            log.error("stream failed", e);
            trySend(emitter, "error", Map.of("code", "internal_error", "message", "Unexpected error"));
        }
        emitter.complete();
    }

    private static void trySend(SseEmitter emitter, String event, Object data) {
        try {
            send(emitter, event, data);
        } catch (ClientGone ignored) {
            // client already gone
        }
    }

    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            throw new ClientGone(e);
        }
    }

    /** The client disconnected; stop generating. */
    static final class ClientGone extends RuntimeException {
        ClientGone(Throwable cause) {
            super(cause);
        }
    }

    @PreDestroy
    void shutdown() {
        streams.shutdown();
    }
}
