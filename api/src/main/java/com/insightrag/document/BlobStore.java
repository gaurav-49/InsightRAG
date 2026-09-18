package com.insightrag.document;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

import com.insightrag.common.ApiException;
import com.insightrag.common.Hashing;
import com.insightrag.config.InsightRagProperties;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Content-addressed upload storage on the volume shared with the workers. Keys are
 * {@code <hash[0:2]>/<hash>.<ext>} relative to the root; the queue message carries the key.
 */
@Component
public class BlobStore {

    private static final int HEAD_BYTES = 64 * 1024;

    private final Path root;
    private final long maxBytes;

    public BlobStore(InsightRagProperties props) throws IOException {
        this.root = Path.of(props.upload().blobRoot()).toAbsolutePath().normalize();
        this.maxBytes = props.upload().maxSize().toBytes();
        Files.createDirectories(root.resolve("tmp"));
    }

    public record Staged(Path tempFile, String sha256, long size, byte[] head) {
    }

    /** Streams the upload to a temp file, hashing as it goes and enforcing the size limit. */
    public Staged stage(InputStream in) throws IOException {
        Path tmp = root.resolve("tmp").resolve(UUID.randomUUID() + ".part");
        MessageDigest digest = Hashing.sha256();
        byte[] head = new byte[HEAD_BYTES];
        int headLen = 0;
        long total = 0;
        try (OutputStream out = new DigestOutputStream(Files.newOutputStream(tmp), digest)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > maxBytes) {
                    throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large",
                            "Upload exceeds the " + (maxBytes / (1024 * 1024)) + " MB limit");
                }
                int take = Math.min(n, HEAD_BYTES - headLen);
                if (take > 0) {
                    System.arraycopy(buf, 0, head, headLen, take);
                    headLen += take;
                }
                out.write(buf, 0, n);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        return new Staged(tmp, HexFormat.of().formatHex(digest.digest()), total, Arrays.copyOf(head, headLen));
    }

    public String keyFor(String sha256, String extension) {
        return sha256.substring(0, 2) + "/" + sha256 + extension;
    }

    /** Moves a staged file to its content address; identical content already stored is kept. */
    public void commit(Staged staged, String key) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        try {
            Files.move(staged.tempFile(), target, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException e) {
            Files.deleteIfExists(staged.tempFile());
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(staged.tempFile(), target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void discard(Staged staged) {
        try {
            Files.deleteIfExists(staged.tempFile());
        } catch (IOException ignored) {
            // best effort; tmp/ is safe to clean out of band
        }
    }

    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    private Path resolve(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("storage key escapes blob root");
        }
        return p;
    }
}
