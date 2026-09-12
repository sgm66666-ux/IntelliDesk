package com.intellidesk.retrieval.indexing.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class RetrievalReindexResponse {

    private Long documentId;
    private Long taskId;
    private Integer generation;
    private String status;
    private LocalDateTime createdAt;
}