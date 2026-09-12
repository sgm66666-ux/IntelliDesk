package com.intellidesk.agent.tool.business;

import com.intellidesk.agent.tool.AgentTool;
import com.intellidesk.agent.tool.AgentToolExecutionResult;
import com.intellidesk.agent.tool.ToolExecutionContext;
import com.intellidesk.common.BusinessException;
import com.intellidesk.common.dto.PageResponse;
import com.intellidesk.document.DocumentService;
import com.intellidesk.document.dto.DocumentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * document_list — Agent Tool listing documents in a knowledge base.
 * <p>
 * Reuses DocumentService.listDocuments() for authorization.
 * workspaceId comes from ToolExecutionContext, knowledgeBaseId from typed DTO.
 */
public class DocumentListTool implements AgentTool<DocumentListArguments> {

    private static final Logger log = LoggerFactory.getLogger(DocumentListTool.class);

    private static final String NAME = "document_list";
    private static final String DESCRIPTION = "List all documents in a knowledge base. "
            + "Returns document IDs, filenames, status, and sizes.";
    private static final String PARAMETERS_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "knowledgeBaseId": {"type": "integer", "description": "Knowledge base ID"},
                "page": {"type": "integer", "description": "Page number", "default": 1, "minimum": 1},
                "size": {"type": "integer", "description": "Items per page", "default": 20, "minimum": 1, "maximum": 100}
              },
              "required": ["knowledgeBaseId"]
            }""";

    private final DocumentService documentService;

    public DocumentListTool(DocumentService documentService) {
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
    public Class<DocumentListArguments> argumentType() {
        return DocumentListArguments.class;
    }

    @Override
    public AgentToolExecutionResult execute(ToolExecutionContext ctx, DocumentListArguments args) {
        try {
            PageResponse<DocumentResponse> page = documentService.listDocuments(
                    ctx.workspaceId(), args.knowledgeBaseId(), ctx.authenticatedUserId(),
                    args.page(), args.size(), null);

            String content = formatDocList(page, args.knowledgeBaseId());
            Map<String, Object> metadata = Map.of(
                    "resultCount", page.getItems().size(),
                    "total", page.getTotal());

            return AgentToolExecutionResult.success(content, metadata);

        } catch (BusinessException e) {
            log.debug("Business error in document_list: code={}, msg={}", e.getCode(), e.getMessage());
            return AgentToolExecutionResult.failure(String.valueOf(e.getCode()), e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error in document_list", e);
            return AgentToolExecutionResult.failure("DOC_LIST_ERROR", "Failed to list documents");
        }
    }

    private String formatDocList(PageResponse<DocumentResponse> page, Long kbId) {
        if (page.getItems().isEmpty()) {
            return "No documents found in knowledge base " + kbId + ".";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Documents in KB ").append(kbId)
                .append(" (page ").append(page.getPage())
                .append(", total ").append(page.getTotal()).append("):\n");
        for (DocumentResponse doc : page.getItems()) {
            sb.append("- [id=").append(doc.getId()).append("] ")
                    .append(doc.getFileName())
                    .append(" | status: ").append(doc.getStatus());
            if (doc.getFileSize() != null) {
                sb.append(" | size: ").append(formatFileSize(doc.getFileSize()));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}