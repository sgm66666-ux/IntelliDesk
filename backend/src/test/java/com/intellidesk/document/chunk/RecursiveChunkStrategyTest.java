package com.intellidesk.document.chunk;

import com.intellidesk.document.parser.ParsedDocument;
import com.intellidesk.document.parser.ParsedSegment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class RecursiveChunkStrategyTest {

    private final RecursiveChunkStrategy strategy = new RecursiveChunkStrategy();

    @Test
    void keepsIndicesContinuousAndEveryChunkWithinLimit() {
        List<ChunkDraft> chunks = split(
                "First paragraph.\n\nSecond line has several words. Final sentence!",
                100,
                10);

        assertInvariant(chunks, 100);
        assertThat(chunks.stream().map(ChunkDraft::getChunkIndex).toList())
                .containsExactlyElementsOf(IntStream.range(0, chunks.size()).boxed().toList());
    }

    @Test
    void countsSeparatorForNinetyNinePlusSpacePlusOneBoundary() {
        List<ChunkDraft> chunks = split("a".repeat(99) + " b", 100, 10);

        assertInvariant(chunks, 100);
        assertThat(chunks).hasSize(2);
        assertThat(codePointLength(chunks.get(0).getContent())).isEqualTo(99);
    }

    @Test
    void trimsOverlapWhenOverlapPlusSeparatorPlusNextPieceWouldOverflow() {
        List<ChunkDraft> chunks = split("a".repeat(100) + " " + "b".repeat(80), 100, 20);

        assertInvariant(chunks, 100);
        assertThat(chunks).hasSize(2);
        assertThat(codePointLength(chunks.get(1).getContent())).isEqualTo(100);
        assertThat(chunks.get(1).getContent()).endsWith("b".repeat(80));
    }

    @Test
    void splitsLongChineseWithoutWhitespaceByCodePoint() {
        List<ChunkDraft> chunks = split("\u4e2d".repeat(501), 100, 10);

        assertInvariant(chunks, 100);
        assertThat(chunks.size()).isGreaterThan(1);
    }

    @Test
    void splitsLongEnglishWithoutWhitespaceByCodePoint() {
        List<ChunkDraft> chunks = split("abcdefghijklmnopqrstuvwxyz0123456789".repeat(30), 100, 10);

        assertInvariant(chunks, 100);
        assertThat(chunks.size()).isGreaterThan(1);
    }

    @Test
    void neverBreaksEmojiOrSupplementaryUnicodeSurrogatePairs() {
        String text = ("prefix-\uD83D\uDE80-\uD83D\uDE00-\uD840\uDC00-suffix").repeat(40);
        List<ChunkDraft> chunks = split(text, 100, 10);

        assertInvariant(chunks, 100);
        for (ChunkDraft chunk : chunks) {
            assertSurrogatePairsAreComplete(chunk.getContent());
        }
    }

    @Test
    void invariantHoldsAcrossParagraphNewlineSentenceWhitespaceAndFallbackPaths() {
        String text = "paragraph one has words.\n\n"
                + "line-two? next sentence!\n"
                + "x".repeat(240)
                + " tail "
                + "\u4e2d".repeat(230)
                + " \uD83D\uDE80".repeat(60);

        List<ChunkDraft> chunks = split(text, 100, 25);

        assertInvariant(chunks, 100);
        chunks.forEach(chunk -> assertSurrogatePairsAreComplete(chunk.getContent()));
    }

    private List<ChunkDraft> split(String text, int chunkSize, int overlap) {
        ParsedSegment segment = ParsedSegment.builder()
                .text(text)
                .startOffset(0)
                .endOffset(codePointLength(text))
                .build();
        ParsedDocument document = new ParsedDocument(null, List.of(segment));
        return strategy.split(
                document,
                new ChunkConfig(ChunkStrategyType.RECURSIVE, chunkSize, overlap));
    }

    private void assertInvariant(List<ChunkDraft> chunks, int chunkSize) {
        assertThat(chunks).isNotEmpty();
        for (ChunkDraft chunk : chunks) {
            int length = codePointLength(chunk.getContent());
            assertThat(chunk.getContent()).isNotBlank();
            assertThat(length)
                    .as("chunk %d code-point length", chunk.getChunkIndex())
                    .isLessThanOrEqualTo(chunkSize);
            assertThat(chunk.getCharacterCount()).isEqualTo(length);
            assertThat(chunk.getTokenCount()).isNull();
        }
    }

    private void assertSurrogatePairsAreComplete(String content) {
        for (int i = 0; i < content.length(); i++) {
            char current = content.charAt(i);
            if (Character.isHighSurrogate(current)) {
                assertThat(i + 1).isLessThan(content.length());
                assertThat(Character.isLowSurrogate(content.charAt(i + 1))).isTrue();
            } else if (Character.isLowSurrogate(current)) {
                assertThat(i).isGreaterThan(0);
                assertThat(Character.isHighSurrogate(content.charAt(i - 1))).isTrue();
            }
        }
    }

    private int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }
}
