package com.intellidesk.document.parser;

import com.intellidesk.document.model.DocumentFormat;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentFormatDetectorTest {

    private final DocumentFormatDetector detector = new DocumentFormatDetector();

    @Test
    void shouldDetectPdfFromSignatureAndExtension() throws IOException {
        byte[] bytes = ("%PDF-1.4\n1 0 obj\n<<\n/Type /Catalog\n>>\nendobj\n").getBytes(StandardCharsets.UTF_8);
        DocumentFormat format = detector.detect("report.pdf", "application/pdf", bytes);
        assertThat(format).isEqualTo(DocumentFormat.PDF);
    }

    @Test
    void shouldRejectPdfWithWrongSignature() throws IOException {
        Path path = Path.of("src/test/resources/fixtures/document/invalid.pdf");
        byte[] bytes = Files.readAllBytes(path);
        DocumentFormat format = detector.detect("report.pdf", "application/pdf", bytes);
        assertThat(format).isNull();
    }

    @Test
    void shouldDetectMarkdownFromExtension() throws IOException {
        Path path = Path.of("src/test/resources/fixtures/document/sample.md");
        byte[] bytes = Files.readAllBytes(path);
        DocumentFormat format = detector.detect("notes.md", "text/markdown", bytes);
        assertThat(format).isEqualTo(DocumentFormat.MARKDOWN);
    }

    @Test
    void shouldDetectTextFromExtension() throws IOException {
        Path path = Path.of("src/test/resources/fixtures/document/sample.txt");
        byte[] bytes = Files.readAllBytes(path);
        DocumentFormat format = detector.detect("notes.txt", "text/plain", bytes);
        assertThat(format).isEqualTo(DocumentFormat.TEXT);
    }

    @Test
    void shouldRejectUnsupportedExtension() {
        byte[] bytes = "executable content".getBytes(StandardCharsets.UTF_8);
        DocumentFormat format = detector.detect("setup.exe", "application/x-msdownload", bytes);
        assertThat(format).isNull();
    }

    @Test
    void shouldRejectTextWithNulByte() {
        byte[] bytes = new byte[]{'h', 'e', 'l', 'l', 'o', 0x00, 'w', 'o', 'r', 'l', 'd'};
        DocumentFormat format = detector.detect("notes.txt", "text/plain", bytes);
        assertThat(format).isNull();
    }

    @Test
    void shouldRejectTextWithInvalidUtf8() {
        byte[] bytes = new byte[]{(byte) 0xFF, (byte) 0xFE};
        DocumentFormat format = detector.detect("notes.txt", "text/plain", bytes);
        assertThat(format).isNull();
    }
}
