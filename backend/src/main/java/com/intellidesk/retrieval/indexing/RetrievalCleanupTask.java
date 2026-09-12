package com.intellidesk.retrieval.indexing;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("retrieval_cleanup_task")
public class RetrievalCleanupTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;
    private Long workspaceId;
    private Long knowledgeBaseId;
    private String esIndexName;
    private String status;
    private LocalDateTime notBefore;
    private Integer attemptCount;
    private Integer maxAttempts;
    private LocalDateTime nextRetryAt;
    private LocalDateTime leaseUntil;
    private String lastErrorCode;
    private String lastErrorMessage;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}