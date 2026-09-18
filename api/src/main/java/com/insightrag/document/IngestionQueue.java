package com.insightrag.document;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.insightrag.config.InsightRagProperties;

import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Producer side of the ingestion stream (contracts/README.md). */
@Component
public class IngestionQueue {

    private final StringRedisTemplate redis;
    private final InsightRagProperties.Queue cfg;

    public IngestionQueue(StringRedisTemplate redis, InsightRagProperties props) {
        this.redis = redis;
        this.cfg = props.queue();
    }

    public String enqueue(UUID documentId, String storageKey) {
        Map<String, String> fields = new HashMap<>();
        fields.put("documentId", documentId.toString());
        fields.put("storageKey", storageKey);
        fields.put("enqueuedAt", Long.toString(System.currentTimeMillis()));
        MapRecord<String, String, String> record = StreamRecords.newRecord().in(cfg.stream()).ofMap(fields);
        RecordId id = redis.opsForStream().add(record, XAddOptions.maxlen(cfg.maxLength()).approximateTrimming(true));
        return id == null ? null : id.getValue();
    }

    public record Depth(long lag, long pending, long length, long deadLetters) {
    }

    /** XINFO GROUPS parsed by hand: Spring Data does not expose the Redis 7 {@code lag} field. */
    public Depth depth() {
        return redis.execute((RedisCallback<Depth>) conn -> {
            long length = orZero(conn.streamCommands().xLen(bytes(cfg.stream())));
            long dlq = orZero(conn.streamCommands().xLen(bytes(cfg.deadLetterStream())));
            long lag = 0;
            long pending = 0;
            Object raw;
            try {
                raw = conn.execute("XINFO", bytes("GROUPS"), bytes(cfg.stream()));
            } catch (RuntimeException e) {
                return new Depth(0, 0, length, dlq); // stream or group not created yet
            }
            if (raw instanceof List<?> groups) {
                for (Object g : groups) {
                    Map<String, Object> info = pairs(g);
                    if (cfg.group().equals(str(info.get("name")))) {
                        pending = num(info.get("pending"));
                        lag = num(info.get("lag"));
                    }
                }
            }
            return new Depth(lag, pending, length, dlq);
        });
    }

    private static Map<String, Object> pairs(Object o) {
        Map<String, Object> m = new HashMap<>();
        if (o instanceof List<?> l) {
            for (int i = 0; i + 1 < l.size(); i += 2) {
                m.put(str(l.get(i)), l.get(i + 1));
            }
        }
        return m;
    }

    private static String str(Object o) {
        return o instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : String.valueOf(o);
    }

    private static long num(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return o == null ? 0 : Long.parseLong(str(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long orZero(Long v) {
        return v == null ? 0 : v;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
