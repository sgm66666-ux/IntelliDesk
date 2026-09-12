package com.intellidesk.agent.tool.business;

import com.intellidesk.agent.tool.AgentTool;
import com.intellidesk.agent.tool.AgentToolExecutionResult;
import com.intellidesk.agent.tool.ToolExecutionContext;
import com.intellidesk.common.BusinessException;
import com.intellidesk.document.DocumentService;
import com.intellidesk.document.dto.DocumentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * document_detail — Agent Tool getting document detail.
 * <p>
 * Reuses DocumentService.getDocument() for authorization.
 * workspaceId comes from ToolExecutionContext, knowledgeBaseId + documentId from typed DTO.
 */
public class DocumentDetailTool implements AgentTool<DocumentDetailArguments> {

    private static final Logger log = LoggerFactory.getLogger(DocumentDetailTool.class);

    private static final String NAME = "document_detail";
    private static final String DESCRIPTION = "Get detailed information about a specific document. "
            + "Returns filename, status, file size, content type, and processing info.";
    private static final String PARAMETERS_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "knowledgeBaseId": {"type": "integer", "description": "Knowledge base ID"},
                "documentId": {"type": "integer", "description": "Document ID"}
              },
              "required": ["knowledgeBaseId", "documentId"]
            }""";

    private final DocumentService documentService;

    public DocumentDetailTool(DocumentService documentService) {
        this.documentService = documentService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public String parametersSchema() {
        return PARAMETERS_SCHEMA;
    }

    @Override
    public Class<DocumentDetailArguments> argumentType() {
        return DocumentDetailArguments.class;
    }

    @Override
    public AgentToolExecutionResult execute(ToolExecutionContext ctx, DocumentDetailArguments args) {
        try {
            DocumentResponse doc = documentService.getDocument(
                    ctx.workspaceId(), args.knowledgeBaseId(), args.documentId(), ctx.authenticatedUserId());

            String content = formatDocDetail(doc);
            Map<String, Object> metadata = Map.of("resultCount", 1);

            return AgentToolExecutionResult.success(content, metadata);

        } catch (BusinessException e) {
            log.debug("Business error in document_detail: code={}, msg={}", e.getCode(), e.getMessage());
            return AgentToolExecutionResult.failure(String.valueOf(e.getCode()), e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error in document_detail", e);
            return AgentToolExecutionResult.failure("DOC_DETAIL_ERROR", "Failed to get document detail");
        }
    }

    private String formatDocDetail(DocumentResponse doc) {
        StringBuilder sb = new StringBuilder();
        sb.append("Document Detail:\n");
        sb.append("ID: ").append(doc.getId()).append("\n");
        sb.append("Name: ").append(doc.getFileName()).append("\n");
        sb.append("Status: ").append(doc.getStatus()).append("\n");
        if (doc.getFileSize() != null) {
            sb.append("File Size: ").append(formatFileSize(doc.getFileSize())).append("\n");
        }
        if (doc.getContentType() != null) {
            sb.append("Content Type: ").append(doc.getContentType()).append("\n");
        }
        if (doc.getFileExtension() != null) {
            sb.append("Extension: ").append(doc.getFileExtension()).append("\n");
        }
        if (doc.getChunkStrategy() != null) {
            sb.append("Chunk Strategy: ").append(doc.getChunkStrategy())
                    .append(" (size=").append(doc.getChunkSize())
                    .append(", overlap=").append(doc.getChunkOverlap()).append(")\n");
        }
        if (doc.getFailureCode() != null) {
            sb.append("Failure Code: ").append(doc.getFailureCode()).append("\n");
        }
        return sb.toString();
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}