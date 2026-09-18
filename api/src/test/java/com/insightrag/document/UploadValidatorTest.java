package com.insightrag.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import com.insightrag.common.ApiException;

import org.junit.jupiter.api.Test;

class UploadValidatorTest {

    @Test
    void acceptsTheFourFormatsByExtension() {
        assertThat(UploadValidator.typeOf("Policy.PDF").mimeType()).isEqualTo("application/pdf");
        assertThat(UploadValidator.typeOf("a.docx").mimeType()).isEqualTo(UploadValidator.DOCX);
        assertThat(UploadValidator.typeOf("a.txt").mimeType()).isEqualTo("text/plain");
        assertThat(UploadValidator.typeOf("a.markdown").extension()).isEqualTo(".md");
        assertThatThrownBy(() -> UploadValidator.typeOf("a.exe")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> UploadValidator.typeOf("noext")).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsContentThatContradictsTheExtension() {
        var pdf = UploadValidator.typeOf("x.pdf");
        UploadValidator.checkContent(pdf, "%PDF-1.7 ...".getBytes(StandardCharsets.US_ASCII), 12);
        assertThatThrownBy(() -> UploadValidator.checkContent(pdf, "hello".getBytes(StandardCharsets.US_ASCII), 5))
                .isInstanceOf(ApiException.class);
        var docx = UploadValidator.typeOf("x.docx");
        UploadValidator.checkContent(docx, new byte[]{'P', 'K', 3, 4, 0}, 5);
        var txt = UploadValidator.typeOf("x.txt");
        UploadValidator.checkContent(txt, "héllo".getBytes(StandardCharsets.UTF_8), 6);
        assertThatThrownBy(() -> UploadValidator.checkContent(txt, new byte[]{(byte) 0xff, (byte) 0xfe, 0}, 3))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> UploadValidator.checkContent(txt, new byte[0], 0)).isInstanceOf(ApiException.class);
    }

    @Test
    void toleratesAMultibyteCharacterCutAtTheSniffBoundary() {
        byte[] euro = "€".getBytes(StandardCharsets.UTF_8);
        byte[] cut = new byte[]{'a', euro[0], euro[1]};
        assertThat(UploadValidator.isUtf8Text(cut)).isTrue();
    }

    @Test
    void filenamesAreSanitised() {
        assertThat(DocumentService.sanitize("../../etc/passwd")).isEqualTo("passwd");
        assertThat(DocumentService.sanitize("C:\\docs\\Policy.pdf")).isEqualTo("Policy.pdf");
        assertThat(DocumentService.sanitize("   ")).isEqualTo("upload");
    }
}
