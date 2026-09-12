package com.intellidesk.knowledge.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class KnowledgeBaseResponse {

    private Long id;
    private Long workspaceId;
    private String name;
    private String description;
    private String chunkStrategy;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private String status;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
