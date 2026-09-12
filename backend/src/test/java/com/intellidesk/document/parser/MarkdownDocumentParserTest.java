package com.intellidesk.document.parser;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarkdownDocumentParserTest {

    private final MarkdownDocumentParser parser = new MarkdownDocumentParser();

    @Test
    void shouldParseSampleMarkdown() throws Exception {
        Path path = Path.of("src/test/resources/fixtures/document/sample.md");
        byte[] bytes = Files.readAllBytes(path);

        ParsedDocument result = parser.parse(
                new ByteArrayInputStream(bytes),
                ParseContext.builder().build());

        List<ParsedSegment> segments = result.getSegments();
        assertThat(segments).isNotEmpty();
        assertThat(result.getMetadata()).containsEntry("parserFormat", "MARKDOWN");

        boolean foundHeadingSection = segments.stream()
                .anyMatch(s -> "IntelliDesk 文档 / 特性".equals(s.getSectionPath()));
        assertThat(foundHeadingSection).isTrue();
    }

    @Test
    void shouldRejectEmptyMarkdown() {
        byte[] bytes = "   \n\n   ".getBytes();
        assertThatThrownBy(() -> parser.parse(
                new ByteArrayInputStream(bytes),
                ParseContext.builder().build()))
                .isInstanceOf(DocumentParseException.class)
                .extracting(e -> ((DocumentParseException) e).getErrorCode())
                .isEqualTo("EMPTY_EXTRACTED_TEXT");
    }
}
