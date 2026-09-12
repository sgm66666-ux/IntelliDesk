package com.intellidesk.document.parser;

import com.intellidesk.document.model.DocumentFormat;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MarkdownDocumentParser implements DocumentParser {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");

    @Override
    public DocumentFormat format() {
        return DocumentFormat.MARKDOWN;
    }

    @Override
    public ParsedDocument parse(InputStream input, ParseContext context) throws DocumentParseException {
        String raw;
        try {
            raw = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DocumentParseException("Failed to read markdown", "MARKDOWN_READ_ERROR", false, e);
        }

        raw = raw.replace("\r\n", "\n").replace("\r", "\n");

        if (raw.isBlank()) {
            throw new DocumentParseException("Extracted text is empty", "EMPTY_EXTRACTED_TEXT", false);
        }

        long maxChars = context.getMaxExtractedCharacters() != null
                ? context.getMaxExtractedCharacters()
                : 5_000_000L;
        if (raw.codePointCount(0, raw.length()) > maxChars) {
            throw new DocumentParseException("Extracted text exceeds limit", "EXTRACTED_TEXT_LIMIT_EXCEEDED", false);
        }

        List<String> headings = new ArrayList<>();
        List<ParsedSegment> segments = new ArrayList<>();
        StringBuilder currentText = new StringBuilder();
        String currentSectionPath = "";
        int currentStartOffset = 0;

        String[] lines = raw.split("\n", -1);
        for (String line : lines) {
            Matcher matcher = HEADING_PATTERN.matcher(line);
            if (matcher.matches()) {
                flushSegment(segments, currentText, currentSectionPath, currentStartOffset);

                int level = matcher.group(1).length();
                String title = matcher.group(2).trim();
                while (headings.size() >= level) {
                    headings.remove(headings.size() - 1);
                }
                headings.add(title);
                currentSectionPath = String.join(" / ", headings);
                currentStartOffset = segments.isEmpty()
                        ? 0
                        : segments.get(segments.size() - 1).getEndOffset();
            } else {
                if (!currentText.isEmpty()) {
                    currentText.append("\n");
                }
                currentText.append(line);
            }
        }
        flushSegment(segments, currentText, currentSectionPath, currentStartOffset);

        if (segments.isEmpty()) {
            throw new DocumentParseException("Extracted text is empty", "EMPTY_EXTRACTED_TEXT", false);
        }

        Map<String, Object> metadata = Map.of(
                "parserFormat", "MARKDOWN",
                "charset", "UTF-8",
                "characterCount", raw.codePointCount(0, raw.length()),
                "segmentCount", segments.size()
        );

        return new ParsedDocument(metadata, segments);
    }

    private void flushSegment(List<ParsedSegment> segments, StringBuilder currentText,
                              String sectionPath, int startOffset) {
        String text = currentText.toString();
        if (text.isBlank()) {
            currentText.setLength(0);
            return;
        }
        int endOffset = startOffset + text.codePointCount(0, text.length());
        segments.add(ParsedSegment.builder()
                .text(text)
                .sectionPath(sectionPath.isEmpty() ? null : sectionPath)
                .startOffset(startOffset)
                .endOffset(endOffset)
                .build());
        currentText.setLength(0);
    }
}
