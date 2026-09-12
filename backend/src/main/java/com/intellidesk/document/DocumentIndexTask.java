package com.intellidesk.document;

import com.baomidou.mybatisplus.annotation.*;
import com.intellidesk.infrastructure.mybatis.UuidTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("document_index_task")
public class DocumentIndexTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;
    private String taskType;
    private String status;
    private Integer attemptCount;
    private Integer maxAttempts;

    @TableField(typeHandler = UuidTypeHandler.class)
    private String messageId;

    private LocalDateTime nextRetryAt;
    private LocalDateTime lastDispatchedAt;
    private LocalDateTime leaseUntil;
    private String lastErrorCode;
    private String lastErrorMessage;
    private LocalDateTime deadLetteredAt;
    private Long requestedBy;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
