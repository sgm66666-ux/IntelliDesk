package com.intellidesk.retrieval.task;

import com.baomidou.mybatisplus.annotation.*;
import com.intellidesk.infrastructure.mybatis.UuidTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("document_retrieval_task")
public class DocumentRetrievalTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;
    private String status;
    private Integer generation;
    private Integer attemptCount;
    private Integer maxAttempts;
    private Long fenceToken;
    @TableField(typeHandler = UuidTypeHandler.class)
    private String messageId;
    private String embeddingModel;
    private Integer embeddingDimension;
    private String esIndexName;
    private Integer indexedChunkCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime lastDispatchedAt;
    private LocalDateTime leaseUntil;
    private String lastErrorCode;
    private String lastErrorMessage;
    private Long requestedBy;
    private LocalDateTime startedAt;
    private LocalDateTime readyAt;

    @Version
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}