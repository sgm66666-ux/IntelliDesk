package com.intellidesk.document.parser;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlainTextDocumentParserTest {

    private final PlainTextDocumentParser parser = new PlainTextDocumentParser();

    @Test
    void shouldParseSampleText() throws Exception {
        Path path = Path.of("src/test/resources/fixtures/document/sample.txt");
        byte[] bytes = Files.readAllBytes(path);

        ParsedDocument result = parser.parse(
                new ByteArrayInputStream(bytes),
                ParseContext.builder().build());

        assertThat(result.getSegments()).hasSize(1);
        assertThat(result.getSegments().get(0).getText()).contains("IntelliDesk").contains("emoji 🚀✅");
        assertThat(result.getMetadata()).containsEntry("parserFormat", "TEXT");
    }

    @Test
    void shouldRejectEmptyText() {
        byte[] bytes = "".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> parser.parse(
                new ByteArrayInputStream(bytes),
                ParseContext.builder().build()))
                .isInstanceOf(DocumentParseException.class)
                .extracting(e -> ((DocumentParseException) e).getErrorCode())
                .isEqualTo("EMPTY_EXTRACTED_TEXT");
    }

    @Test
    void shouldRejectBlankText() {
        byte[] bytes = "   \n\n   ".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> parser.parse(
                new ByteArrayInputStream(bytes),
                ParseContext.builder().build()))
                .isInstanceOf(DocumentParseException.class)
                .extracting(e -> ((DocumentParseException) e).getErrorCode())
                .isEqualTo("EMPTY_EXTRACTED_TEXT");
    }
}
