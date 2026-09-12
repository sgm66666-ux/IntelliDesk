package com.intellidesk.document.chunk;

import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.Map;

@Getter
@Builder
public class ChunkDraft {

    private final int chunkIndex;
    private final String content;
    private final int characterCount;
    private final Integer tokenCount;
    private final Integer pageStart;
    private final Integer pageEnd;
    private final String sectionPath;
    private final int startOffset;
    private final int endOffset;
    private final Map<String, Object> sourceMetadata;

    public Map<String, Object> getSourceMetadata() {
        return sourceMetadata != null ? sourceMetadata : Collections.emptyMap();
    }
}
