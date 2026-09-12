package com.intellidesk.document.parser;

import com.intellidesk.document.model.DocumentFormat;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Component
public class PlainTextDocumentParser implements DocumentParser {

    @Override
    public DocumentFormat format() {
        return DocumentFormat.TEXT;
    }

    @Override
    public ParsedDocument parse(InputStream input, ParseContext context) throws DocumentParseException {
        String text;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            text = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
        } catch (IOException e) {
            throw new DocumentParseException("Failed to read plain text", "TEXT_READ_ERROR", false, e);
        }

        if (text.isBlank()) {
            throw new DocumentParseException("Extracted text is empty", "EMPTY_EXTRACTED_TEXT", false);
        }

        long maxChars = context.getMaxExtractedCharacters() != null
                ? context.getMaxExtractedCharacters()
                : 5_000_000L;
        if (text.codePointCount(0, text.length()) > maxChars) {
            throw new DocumentParseException("Extracted text exceeds limit", "EXTRACTED_TEXT_LIMIT_EXCEEDED", false);
        }

        ParsedSegment segment = ParsedSegment.builder()
                .text(text)
                .startOffset(0)
                .endOffset(text.codePointCount(0, text.length()))
                .build();

        Map<String, Object> metadata = Map.of(
                "parserFormat", "TEXT",
                "charset", "UTF-8",
                "characterCount", text.codePointCount(0, text.length())
        );

        return new ParsedDocument(metadata, List.of(segment));
    }
}
