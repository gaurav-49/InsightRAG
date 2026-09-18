package com.insightrag.document;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

import com.insightrag.common.ApiException;

import org.springframework.http.HttpStatus;

/** FR-01: accept PDF, DOCX, TXT and Markdown, judged by extension and by content sniffing. */
public final class UploadValidator {

    public record FileType(String extension, String mimeType) {
    }

    static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] ZIP_MAGIC = {'P', 'K', 3, 4};

    private static final Map<String, String> BY_EXTENSION = Map.of(
            ".pdf", "application/pdf",
            ".docx", DOCX,
            ".txt", "text/plain",
            ".md", "text/markdown",
            ".markdown", "text/markdown");

    private UploadValidator() {
    }

    public static FileType typeOf(String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot);
        String mime = BY_EXTENSION.get(ext);
        if (mime == null) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_type",
                    "Only PDF, DOCX, TXT and Markdown files are accepted");
        }
        return new FileType(ext.equals(".markdown") ? ".md" : ext, mime);
    }

    /** Rejects files whose bytes do not match the claimed type, before any processing cost. */
    public static void checkContent(FileType type, byte[] head, long size) {
        if (size == 0) {
            throw ApiException.badRequest("empty_file", "The uploaded file is empty");
        }
        switch (type.mimeType()) {
            case "application/pdf" -> require(startsWith(head, PDF_MAGIC), "File does not look like a PDF");
            case DOCX -> require(startsWith(head, ZIP_MAGIC), "File does not look like a DOCX document");
            default -> require(isUtf8Text(head), "Text files must be UTF-8 encoded text");
        }
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "content_mismatch", message);
        }
    }

    private static boolean startsWith(byte[] head, byte[] magic) {
        if (head.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (head[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    static boolean isUtf8Text(byte[] head) {
        for (byte b : head) {
            if (b == 0) {
                return false;
            }
        }
        // The sniffed prefix may end mid-character; tolerate up to 3 truncated trailing bytes.
        for (int trim = 0; trim <= Math.min(3, head.length); trim++) {
            try {
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(head, 0, head.length - trim));
                return true;
            } catch (CharacterCodingException e) {
                // retry with a shorter prefix
            }
        }
        return false;
    }
}
