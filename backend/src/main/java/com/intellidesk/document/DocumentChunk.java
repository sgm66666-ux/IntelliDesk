package com.intellidesk.document;

import com.baomidou.mybatisplus.annotation.*;
import com.intellidesk.infrastructure.mybatis.JsonbTypeHandler;
import com.intellidesk.infrastructure.mybatis.PGvectorTypeHandler;
import com.pgvector.PGvector;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("document_chunk")
public class DocumentChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long knowledgeBaseId;
    private Long documentId;
    private Integer chunkIndex;
    private String content;
    private Integer characterCount;
    private Integer tokenCount;
    private Integer pageStart;
    private Integer pageEnd;
    private String sectionPath;
    private Integer startOffset;
    private Integer endOffset;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String sourceMetadata;

    // Phase 3 embedding fields
    @TableField(typeHandler = PGvectorTypeHandler.class)
    private PGvector embedding;
    private String embeddingModel;
    private Integer embeddingGeneration;
    private Long embeddingFenceToken;
    private LocalDateTime embeddedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
