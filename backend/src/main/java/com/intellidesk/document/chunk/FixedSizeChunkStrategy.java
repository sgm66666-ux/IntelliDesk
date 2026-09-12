package com.intellidesk.document.chunk;

import com.intellidesk.document.parser.ParsedDocument;
import com.intellidesk.document.parser.ParsedSegment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
public class FixedSizeChunkStrategy implements ChunkStrategy {

    @Override
    public ChunkStrategyType type() {
        return ChunkStrategyType.FIXED_SIZE;
    }

    @Override
    public List<ChunkDraft> split(ParsedDocument document, ChunkConfig config) {
        String fullText = document.getSegments().stream()
                .map(ParsedSegment::getText)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");

        int totalCodePoints = fullText.codePointCount(0, fullText.length());
        if (totalCodePoints == 0) {
            return Collections.emptyList();
        }

        int chunkSize = config.getSize();
        int overlap = config.getOverlap();
        int step = chunkSize - overlap;

        List<ChunkDraft> chunks = new ArrayList<>();
        int start = 0;
        int index = 0;

        while (start < totalCodePoints) {
            int end = Math.min(start + chunkSize, totalCodePoints);
            int startIndex = fullText.offsetByCodePoints(0, start);
            int endIndex = fullText.offsetByCodePoints(0, end);
            String content = fullText.substring(startIndex, endIndex);

            if (content.isBlank()) {
                start = end;
                continue;
            }

            Coverage coverage = computeCoverage(document.getSegments(), start, end);

            Map<String, Object> sourceMetadata = Map.of(
                    "strategy", "FIXED_SIZE",
                    "startOffset", start,
                    "endOffset", end - 1
            );

            chunks.add(ChunkDraft.builder()
                    .chunkIndex(index++)
                    .content(content)
                    .characterCount(content.codePointCount(0, content.length()))
                    .tokenCount(null)
                    .pageStart(coverage.pageStart)
                    .pageEnd(coverage.pageEnd)
                    .sectionPath(coverage.sectionPath)
                    .startOffset(start)
                    .endOffset(end - 1)
                    .sourceMetadata(sourceMetadata)
                    .build());

            start += step;
            if (step <= 0) {
                break;
            }
        }

        return chunks;
    }

    private Coverage computeCoverage(List<ParsedSegment> segments, int chunkStart, int chunkEnd) {
        Integer pageStart = null;
        Integer pageEnd = null;
        String sectionPath = null;

        int offset = 0;
        for (ParsedSegment segment : segments) {
            int segmentLength = segment.getText().codePointCount(0, segment.getText().length());
            int segmentStart = offset;
            int segmentEnd = offset + segmentLength;

            if (segmentStart < chunkEnd && segmentEnd > chunkStart) {
                if (segment.getPageNumber() != null) {
                    if (pageStart == null) {
                        pageStart = segment.getPageNumber();
                    }
                    pageEnd = segment.getPageNumber();
                }
                if (segment.getSectionPath() != null && sectionPath == null) {
                    sectionPath = segment.getSectionPath();
                }
            }

            offset += segmentLength + 1; // +1 for the joining LF
        }

        return new Coverage(pageStart, pageEnd, sectionPath);
    }

    private record Coverage(Integer pageStart, Integer pageEnd, String sectionPath) {
    }
}
