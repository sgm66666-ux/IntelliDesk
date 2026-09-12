package com.intellidesk.document.chunk;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ChunkConfig {

    private final ChunkStrategyType type;
    private final int size;
    private final int overlap;

    public ChunkConfig(ChunkStrategyType type, int size, int overlap) {
        if (type == null) {
            throw new IllegalArgumentException("Chunk strategy type must not be null");
        }
        if (size < 100 || size > 4000) {
            throw new IllegalArgumentException("Chunk size must be between 100 and 4000");
        }
        if (overlap < 0 || overlap > 1000) {
            throw new IllegalArgumentException("Chunk overlap must be between 0 and 1000");
        }
        if (overlap >= size) {
            throw new IllegalArgumentException("Chunk overlap must be less than chunk size");
        }
        this.type = type;
        this.size = size;
        this.overlap = overlap;
    }
}
