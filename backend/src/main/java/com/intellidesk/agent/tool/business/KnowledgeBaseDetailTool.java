package com.intellidesk.agent.tool.business;

import com.intellidesk.agent.tool.AgentTool;
import com.intellidesk.agent.tool.AgentToolExecutionResult;
import com.intellidesk.agent.tool.ToolExecutionContext;
import com.intellidesk.common.BusinessException;
import com.intellidesk.knowledge.KnowledgeBase;
import com.intellidesk.knowledge.KnowledgeBaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * knowledge_base_detail — Agent Tool getting knowledge base detail.
 * <p>
 * Reuses KnowledgeBaseService.getKnowledgeBase() for authorization.
 * workspaceId + knowledgeBaseId from typed DTO, userId from context.
 */
public class KnowledgeBaseDetailTool implements AgentTool<KnowledgeBaseDetailArguments> {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseDetailTool.class);

    private static final String NAME = "knowledge_base_detail";
    private static final String DESCRIPTION = "Get detailed information about a specific knowledge base. "
            + "Returns name, description, status, and chunk configuration.";
    private static final String PARAMETERS_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "knowledgeBaseId": {"type": "integer", "description": "Knowledge base ID"}
              },
              "required": ["knowledgeBaseId"]
            }""";

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseDetailTool(KnowledgeBaseService knowledgeBaseService) {
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
    public Class<KnowledgeBaseDetailArguments> argumentType() {
        return KnowledgeBaseDetailArguments.class;
    }

    @Override
    public AgentToolExecutionResult execute(ToolExecutionContext ctx, KnowledgeBaseDetailArguments args) {
        try {
            KnowledgeBase kb = knowledgeBaseService.getKnowledgeBase(
                    ctx.workspaceId(), args.knowledgeBaseId(), ctx.authenticatedUserId());

            String content = formatKbDetail(kb);
            Map<String, Object> metadata = Map.of("resultCount", 1);

            return AgentToolExecutionResult.success(content, metadata);

        } catch (BusinessException e) {
            log.debug("Business error in knowledge_base_detail: code={}, msg={}", e.getCode(), e.getMessage());
            return AgentToolExecutionResult.failure(String.valueOf(e.getCode()), e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error in knowledge_base_detail", e);
            return AgentToolExecutionResult.failure("KB_DETAIL_ERROR", "Failed to get knowledge base detail");
        }
    }

    private String formatKbDetail(KnowledgeBase kb) {
        return "Knowledge Base Detail:\n" +
                "ID: " + kb.getId() + "\n" +
                "Name: " + kb.getName() + "\n" +
                "Description: " + (kb.getDescription() != null ? kb.getDescription() : "N/A") + "\n" +
                "Status: " + kb.getStatus() + "\n" +
                "Chunk Strategy: " + kb.getChunkStrategy() +
                " (chunkSize=" + kb.getChunkSize() + ", overlap=" + kb.getChunkOverlap() + ")";
    }
}