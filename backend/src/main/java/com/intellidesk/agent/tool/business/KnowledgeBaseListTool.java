package com.intellidesk.agent.tool.business;

import com.intellidesk.agent.tool.AgentTool;
import com.intellidesk.agent.tool.AgentToolExecutionResult;
import com.intellidesk.agent.tool.ToolExecutionContext;
import com.intellidesk.common.BusinessException;
import com.intellidesk.common.dto.PageResponse;
import com.intellidesk.knowledge.KnowledgeBase;
import com.intellidesk.knowledge.KnowledgeBaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * knowledge_base_list — Agent Tool listing knowledge bases in current workspace.
 * <p>
 * Reuses KnowledgeBaseService.listKnowledgeBases() for authorization.
 * workspaceId comes from ToolExecutionContext, never from LLM arguments.
 */
public class KnowledgeBaseListTool implements AgentTool<KnowledgeBaseListArguments> {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseListTool.class);

    private static final String NAME = "knowledge_base_list";
    private static final String DESCRIPTION = "List all knowledge bases in the current workspace. "
            + "Returns knowledge base IDs, names, descriptions, and status.";
    private static final String PARAMETERS_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "page": {"type": "integer", "description": "Page number", "default": 1, "minimum": 1},
                "size": {"type": "integer", "description": "Items per page", "default": 20, "minimum": 1, "maximum": 100}
              }
            }""";

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseListTool(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
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
    public Class<KnowledgeBaseListArguments> argumentType() {
        return KnowledgeBaseListArguments.class;
    }

    @Override
    public AgentToolExecutionResult execute(ToolExecutionContext ctx, KnowledgeBaseListArguments args) {
        try {
            PageResponse<KnowledgeBase> page = knowledgeBaseService.listKnowledgeBases(
                    ctx.workspaceId(), ctx.authenticatedUserId(),
                    args.page(), args.size(), null);

            String content = formatKbList(page);
            Map<String, Object> metadata = Map.of(
                    "resultCount", page.getItems().size(),
                    "total", page.getTotal());

            return AgentToolExecutionResult.success(content, metadata);

        } catch (BusinessException e) {
            log.debug("Business error in knowledge_base_list: code={}, msg={}", e.getCode(), e.getMessage());
            return AgentToolExecutionResult.failure(String.valueOf(e.getCode()), e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error in knowledge_base_list", e);
            return AgentToolExecutionResult.failure("KB_LIST_ERROR", "Failed to list knowledge bases");
        }
    }

    private String formatKbList(PageResponse<KnowledgeBase> page) {
        if (page.getItems().isEmpty()) {
            return "No knowledge bases found.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Knowledge Bases (page ").append(page.getPage())
                .append(", total ").append(page.getTotal()).append("):\n");
        for (KnowledgeBase kb : page.getItems()) {
            sb.append("- [id=").append(kb.getId()).append("] ")
                    .append(kb.getName())
                    .append(" | status: ").append(kb.getStatus());
            if (kb.getDescription() != null && !kb.getDescription().isBlank()) {
                sb.append(" | desc: ").append(truncate(kb.getDescription(), 80));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen) + "...";
    }
}