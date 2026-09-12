package com.intellidesk.knowledge.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class KnowledgeBaseUpdateRequest {

    @Size(min = 1, max = 100, message = "知识库名称长度必须在 1-100 之间")
    private String name;

    @Size(max = 1000, message = "知识库描述长度不能超过 1000")
    private String description;

    private String chunkStrategy;
    private Integer chunkSize;
    private Integer chunkOverlap;
}
