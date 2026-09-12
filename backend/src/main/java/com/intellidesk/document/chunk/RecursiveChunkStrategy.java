package com.intellidesk.document.chunk;

import com.intellidesk.document.parser.ParsedDocument;
import com.intellidesk.document.parser.ParsedSegment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class RecursiveChunkStrategy implements ChunkStrategy {

    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("([。．.？?！!])");

    @Override
    public ChunkStrategyType type() {
        return ChunkStrategyType.RECURSIVE;
    }

    @Override
    public List<ChunkDraft> split(ParsedDocument document, ChunkConfig config) {
        String fullText = document.getSegments().stream()
                .map(ParsedSegment::getText)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");

        if (fullText.isBlank()) {
            return Collections.emptyList();
        }

        List<String> pieces = recursiveSplit(fullText);
        List<ChunkDraft> chunks = mergeAndOverlap(pieces, config, document.getSegments());

        for (int i = 0; i < chunks.size(); i++) {
            ChunkDraft draft = chunks.get(i);
            if (draft.getChunkIndex() != i) {
                throw new IllegalStateException("Chunk index must be continuous starting from 0");
            }
            if (draft.getCharacterCount() > config.getSize()) {
                throw new IllegalStateException("Chunk content exceeds configured chunkSize");
            }
        }

        return chunks;
    }

    private List<String> recursiveSplit(String text) {
        List<String> result = new ArrayList<>();
        for (String paragraph : text.split("\n\n", -1)) {
            if (paragraph.isBlank()) {
                continue;
            }
            for (String line : paragraph.split("\n", -1)) {
                if (line.isBlank()) {
                    continue;
                }
                for (String sentence : splitBySentence(line)) {
                    if (sentence.isBlank()) {
                        continue;
                    }
                    for (String word : sentence.split("\\s+", -1)) {
                        if (!word.isBlank()) {
                            result.add(word);
                        }
                    }
                }
            }
        }
        return result.isEmpty() ? List.of(text) : result;
    }

    private List<String> splitBySentence(String line) {
        List<String> sentences = new ArrayList<>();
        java.util.regex.Matcher matcher = SENTENCE_BOUNDARY.matcher(line);
        int lastEnd = 0;
        while (matcher.find()) {
            int end = matcher.end();
            sentences.add(line.substring(lastEnd, end));
            lastEnd = end;
        }
        if (lastEnd < line.length()) {
            sentences.add(line.substring(lastEnd));
        }
        return sentences.isEmpty() ? List.of(line) : sentences;
    }

    private List<ChunkDraft> mergeAndOverlap(List<String> pieces, ChunkConfig config, List<ParsedSegment> segments) {
        int chunkSize = config.getSize();
        int overlap = config.getOverlap();

        List<ChunkDraft> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentCodePoints = 0;
        int chunkStart = 0;
        int index = 0;

        for (String piece : pieces) {
            int pieceCodePoints = piece.codePointCount(0, piece.length());

            // If a single piece exceeds chunkSize, split by code points.
            // Flush current buffer first if non-empty, then code-point-split the oversized piece.
            if (pieceCodePoints > chunkSize) {
                // Flush current buffer if any
                if (currentCodePoints > 0) {
                    String content = current.toString();
                    int chunkEnd = chunkStart + content.codePointCount(0, content.length());
                    chunks.add(buildChunk(index++, chunkStart, chunkEnd, content, segments));
                    // Reset current (no overlap for oversized piece — it will split into multiple chunks)
                    current.setLength(0);
                    currentCodePoints = 0;
                    chunkStart = chunkEnd;
                }

                // Code-point fallback: split oversized piece into chunkSize pieces
                int offset = 0;
                while (offset < piece.length()) {
                    int remaining = piece.codePointCount(offset, piece.length());
                    int take = Math.min(chunkSize, remaining);
                    int end = piece.offsetByCodePoints(offset, take);
                    String subPiece = piece.substring(offset, end);
                    int subCodePoints = subPiece.codePointCount(0, subPiece.length());

                    int chunkEnd = chunkStart + subCodePoints;
                    chunks.add(buildChunk(index++, chunkStart, chunkEnd, subPiece, segments));
                    chunkStart = chunkEnd;
                    offset = end;
                }
                continue;
            }

            int separatorCodePoints = currentCodePoints > 0 ? 1 : 0;
            if (currentCodePoints > 0
                    && currentCodePoints + separatorCodePoints + pieceCodePoints > chunkSize) {
                String content = current.toString();
                int chunkEnd = chunkStart + content.codePointCount(0, content.length());
                chunks.add(buildChunk(index++, chunkStart, chunkEnd, content, segments));

                // The next append inserts one real separator before the piece.
                // Trim overlap so overlap + separator + piece always fits.
                int maxOverlap = Math.max(0, chunkSize - pieceCodePoints - 1);
                String overlapText = computeOverlap(content, Math.min(overlap, maxOverlap));
                current.setLength(0);
                current.append(overlapText);
                currentCodePoints = overlapText.codePointCount(0, overlapText.length());
                chunkStart = chunkEnd - currentCodePoints;
            }

            if (current.length() > 0) {
                current.append(" ");
                currentCodePoints++;
            }
            current.append(piece);
            currentCodePoints += pieceCodePoints;
        }

        if (!current.isEmpty()) {
            String content = current.toString();
            int chunkEnd = chunkStart + content.codePointCount(0, content.length());
            chunks.add(buildChunk(index, chunkStart, chunkEnd, content, segments));
        }

        return chunks;
    }

    private String computeOverlap(String content, int overlap) {
        if (overlap <= 0 || content.isEmpty()) {
            return "";
        }
        int total = content.codePointCount(0, content.length());
        int overlapCount = Math.min(overlap, total);
        int startIndex = content.offsetByCodePoints(0, total - overlapCount);
        return content.substring(startIndex);
    }

    private ChunkDraft buildChunk(int index, int start, int end, String content, List<ParsedSegment> segments) {
        if (content.isBlank()) {
            throw new IllegalStateException("Chunk content must not be blank");
        }

        Coverage coverage = computeCoverage(segments, start, end);
        Map<String, Object> sourceMetadata = Map.of(
                "strategy", "RECURSIVE",
                "startOffset", start,
                "endOffset", end - 1
        );

        return ChunkDraft.builder()
                .chunkIndex(index)
                .content(content)
                .characterCount(content.codePointCount(0, content.length()))
                .tokenCount(null)
                .pageStart(coverage.pageStart)
                .pageEnd(coverage.pageEnd)
                .sectionPath(coverage.sectionPath)
                .startOffset(start)
                .endOffset(end - 1)
                .sourceMetadata(sourceMetadata)
                .build();
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

            offset += segmentLength + 1;
        }

        return new Coverage(pageStart, pageEnd, sectionPath);
    }

    private record Coverage(Integer pageStart, Integer pageEnd, String sectionPath) {
    }
}
