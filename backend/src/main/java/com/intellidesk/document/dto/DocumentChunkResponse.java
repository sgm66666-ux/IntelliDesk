package com.intellidesk.document.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Map;

@Getter
@Builder
public class DocumentChunkResponse {

    private final Long id;
    private final Long documentId;
    private final Integer chunkIndex;
    private final String content;
    private final Integer characterCount;
    private final Integer tokenCount;
    private final Integer pageStart;
    private final Integer pageEnd;
    private final String sectionPath;
    private final Map<String, Object> sourceMetadata;
    private final LocalDateTime createdAt;
}
