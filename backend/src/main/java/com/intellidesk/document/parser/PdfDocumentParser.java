package com.intellidesk.document.parser;

import com.intellidesk.document.model.DocumentFormat;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class PdfDocumentParser implements DocumentParser {

    private static final int MAX_METADATA_LENGTH = 512;

    @Override
    public DocumentFormat format() {
        return DocumentFormat.PDF;
    }

    @Override
    public ParsedDocument parse(InputStream input, ParseContext context) throws DocumentParseException {
        byte[] bytes;
        try {
            bytes = input.readAllBytes();
        } catch (IOException e) {
            throw new DocumentParseException("Failed to read PDF bytes", "PDF_READ_ERROR", false, e);
        }

        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) {
                throw new DocumentParseException("PDF is encrypted and cannot be read",
                        "PDF_ENCRYPTED", false);
            }

            int pageCount = document.getNumberOfPages();
            int maxPdfPages = context.getMaxPdfPages() != null
                    ? context.getMaxPdfPages()
                    : 500;
            if (pageCount > maxPdfPages) {
                throw new DocumentParseException("PDF page count exceeds limit",
                        "PDF_PAGE_LIMIT_EXCEEDED", false);
            }

            long maxExtractedCharacters = context.getMaxExtractedCharacters() != null
                    ? context.getMaxExtractedCharacters()
                    : 5_000_000L;

            PDFTextStripper stripper = new PDFTextStripper();
            List<ParsedSegment> segments = new ArrayList<>();
            long totalCodePoints = 0;

            for (int pageNumber = 1; pageNumber <= pageCount; pageNumber++) {
                stripper.setStartPage(pageNumber);
                stripper.setEndPage(pageNumber);
                String pageText = stripper.getText(document).trim();
                long pageCodePoints = pageText.codePointCount(0, pageText.length());
                totalCodePoints += pageCodePoints;

                if (totalCodePoints > maxExtractedCharacters) {
                    throw new DocumentParseException("Extracted text exceeds limit",
                            "EXTRACTED_TEXT_LIMIT_EXCEEDED", false);
                }

                if (!pageText.isBlank()) {
                    int startOffset = segments.isEmpty()
                            ? 0
                            : segments.get(segments.size() - 1).getEndOffset();
                    segments.add(ParsedSegment.builder()
                            .text(pageText)
                            .pageNumber(pageNumber)
                            .startOffset(startOffset)
                            .endOffset(startOffset + (int) pageCodePoints)
                            .build());
                }
            }

            if (segments.isEmpty()) {
                throw new DocumentParseException("Extracted text is empty",
                        "EMPTY_EXTRACTED_TEXT", false);
            }

            Map<String, Object> metadata = extractMetadata(document.getDocumentInformation(), pageCount);
            return new ParsedDocument(metadata, segments);

        } catch (DocumentParseException e) {
            throw e;
        } catch (IOException e) {
            throw new DocumentParseException("PDF parsing failed", "PDF_PARSE_ERROR", false, e);
        } catch (Exception e) {
            throw new DocumentParseException("PDF parsing failed", "PDF_PARSE_ERROR", false, e);
        }
    }

    private Map<String, Object> extractMetadata(PDDocumentInformation info, int pageCount) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("parserFormat", "PDF");
        metadata.put("pageCount", pageCount);
        putIfPresent(metadata, "title", info.getTitle());
        putIfPresent(metadata, "author", info.getAuthor());
        putIfPresent(metadata, "subject", info.getSubject());
        putIfPresent(metadata, "creator", info.getCreator());
        putIfPresent(metadata, "producer", info.getProducer());
        return metadata;
    }

    private void putIfPresent(Map<String, Object> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            String trimmed = value.length() > MAX_METADATA_LENGTH
                    ? value.substring(0, MAX_METADATA_LENGTH)
                    : value;
            metadata.put(key, trimmed);
        }
    }
}
