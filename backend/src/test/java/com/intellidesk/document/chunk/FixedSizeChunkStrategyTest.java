package com.intellidesk.document.chunk;

import com.intellidesk.document.parser.ParsedDocument;
import com.intellidesk.document.parser.ParsedSegment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class FixedSizeChunkStrategyTest {

    private final FixedSizeChunkStrategy strategy = new FixedSizeChunkStrategy();

    @Test
    void shouldProduceContinuousIndices() {
        ParsedDocument document = createDocument("0123456789".repeat(20));
        ChunkConfig config = new ChunkConfig(ChunkStrategyType.FIXED_SIZE, 100, 10);

        List<ChunkDraft> chunks = strategy.split(document, config);

        assertThat(chunks).isNotEmpty();
        List<Integer> indices = chunks.stream().map(ChunkDraft::getChunkIndex).toList();
        assertThat(indices).isEqualTo(IntStream.range(0, chunks.size()).boxed().collect(Collectors.toList()));
    }

    @Test
    void shouldPreserveUnicodeAndEmoji() {
        ParsedDocument document = createDocument("中文测试🚀✅".repeat(30));
        ChunkConfig config = new ChunkConfig(ChunkStrategyType.FIXED_SIZE, 100, 20);

        List<ChunkDraft> chunks = strategy.split(document, config);

        assertThat(chunks).isNotEmpty();
        String joined = chunks.stream().map(ChunkDraft::getContent).collect(Collectors.joining(""));
        assertThat(joined).contains("中文测试🚀✅");
        for (ChunkDraft chunk : chunks) {
            assertThat(chunk.getContent()).isNotBlank();
            assertThat(chunk.getCharacterCount()).isEqualTo(chunk.getContent().codePointCount(0, chunk.getContent().length()));
            assertThat(chunk.getTokenCount()).isNull();
        }
    }

    @Test
    void shouldHandleVeryLongTextWithoutDelimiter() {
        ParsedDocument document = createDocument("a".repeat(5000));
        ChunkConfig config = new ChunkConfig(ChunkStrategyType.FIXED_SIZE, 200, 20);

        List<ChunkDraft> chunks = strategy.split(document, config);

        assertThat(chunks.size()).isGreaterThan(1);
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).getChunkIndex()).isEqualTo(i);
        }
    }

    private ParsedDocument createDocument(String text) {
        ParsedSegment segment = ParsedSegment.builder()
                .text(text)
                .startOffset(0)
                .endOffset(text.codePointCount(0, text.length()))
                .build();
        return new ParsedDocument(null, List.of(segment));
    }
}
