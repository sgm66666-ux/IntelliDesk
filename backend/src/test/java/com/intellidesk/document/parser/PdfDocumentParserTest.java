package com.intellidesk.document.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfDocumentParserTest {

    private static byte[] samplePdfBytes;
    private final PdfDocumentParser parser = new PdfDocumentParser();

    @BeforeAll
    static void setUp() throws IOException {
        samplePdfBytes = createPdfWithText("IntelliDesk PDF Test", "Page one content.");
    }

    @Test
    void shouldParseSamplePdf() throws Exception {
        ParsedDocument result = parser.parse(
                new ByteArrayInputStream(samplePdfBytes),
                ParseContext.builder().build());

        assertThat(result.getSegments()).isNotEmpty();
        assertThat(result.getMetadata()).containsEntry("parserFormat", "PDF");
        assertThat(result.getSegments().get(0).getText()).contains("IntelliDesk");
    }

    @Test
    void shouldParseDeterministicValidPdfFixture() throws Exception {
        Path path = Path.of("src/test/resources/fixtures/document/valid-sample.pdf");
        byte[] bytes = Files.readAllBytes(path);

        ParsedDocument result = parser.parse(
                new ByteArrayInputStream(bytes),
                ParseContext.builder().build());

        assertThat(result.getSegments()).isNotEmpty();
        assertThat(result.getMetadata()).containsEntry("parserFormat", "PDF");
        assertThat(result.getSegments().get(0).getText()).contains("IntelliDesk Wave 2 PDF Fixture");
    }

    @Test
    void shouldRejectInvalidPdf(@TempDir Path tempDir) throws Exception {
        Path path = Path.of("src/test/resources/fixtures/document/invalid.pdf");
        byte[] bytes = Files.readAllBytes(path);

        assertThatThrownBy(() -> parser.parse(
                new ByteArrayInputStream(bytes),
                ParseContext.builder().build()))
                .isInstanceOf(DocumentParseException.class);
    }

    @Test
    void shouldRejectPdfExceedingPageLimit() throws Exception {
        byte[] largePdf = createPdfWithText("Many pages", "content", 10);
        ParseContext context = ParseContext.builder().maxPdfPages(5).build();

        assertThatThrownBy(() -> parser.parse(
                new ByteArrayInputStream(largePdf),
                context))
                .isInstanceOf(DocumentParseException.class)
                .extracting(e -> ((DocumentParseException) e).getErrorCode())
                .isEqualTo("PDF_PAGE_LIMIT_EXCEEDED");
    }

    @Test
    void shouldRejectEmptyExtractedPdf() throws Exception {
        byte[] emptyPdf = createPdfWithText("", "");

        assertThatThrownBy(() -> parser.parse(
                new ByteArrayInputStream(emptyPdf),
                ParseContext.builder().build()))
                .isInstanceOf(DocumentParseException.class)
                .extracting(e -> ((DocumentParseException) e).getErrorCode())
                .isEqualTo("EMPTY_EXTRACTED_TEXT");
    }

    private static byte[] createPdfWithText(String title, String body) throws IOException {
        return createPdfWithText(title, body, 1);
    }

    private static byte[] createPdfWithText(String title, String body, int pageCount) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int i = 0; i < pageCount; i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (title.isBlank() && body.isBlank()) {
                    continue;
                }
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(font, 12);
                    stream.newLineAtOffset(50, 700);
                    stream.showText(title + " - page " + (i + 1));
                    stream.newLineAtOffset(0, -20);
                    if (!body.isEmpty()) {
                        stream.showText(body);
                    }
                    stream.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
