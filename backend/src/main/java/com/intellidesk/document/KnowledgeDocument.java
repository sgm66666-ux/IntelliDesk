package com.intellidesk.document;

import com.baomidou.mybatisplus.annotation.*;
import com.intellidesk.infrastructure.mybatis.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("document")
public class KnowledgeDocument {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long knowledgeBaseId;
    private String originalFileName;
    private String fileExtension;
    private String contentType;
    private Long fileSize;
    private String checksumSha256;
    private String bucketName;
    private String objectKey;
    private String status;
    private String chunkStrategy;
    private Integer chunkSize;
    private Integer chunkOverlap;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String parserMetadata;

    private String failureCode;
    private String failureMessage;
    private String cleanupReason;
    private LocalDateTime cleanupEligibleAt;
    private Long createdBy;
    private LocalDateTime completedAt;
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
