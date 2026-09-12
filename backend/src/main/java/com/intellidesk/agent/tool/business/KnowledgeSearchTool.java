package com.intellidesk.agent.tool.business;

import com.intellidesk.agent.tool.AgentTool;
import com.intellidesk.agent.tool.AgentToolExecutionResult;
import com.intellidesk.agent.tool.ToolException;
import com.intellidesk.agent.tool.ToolExecutionContext;
import com.intellidesk.common.BusinessException;
import com.intellidesk.retrieval.RetrievalMode;
import com.intellidesk.retrieval.RetrievalQuery;
import com.intellidesk.retrieval.RetrievalResult;
import com.intellidesk.retrieval.RetrievalScope;
import com.intellidesk.retrieval.RetrievalScopeResolver;
import com.intellidesk.retrieval.RetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * knowledge_search — Agent Tool wrapping RetrievalService.
 * <p>
 * Reuses existing RetrievalService + RetrievalScopeResolver for authorization.
 * Default mode: HYBRID.
 */
public class KnowledgeSearchTool implements AgentTool<KnowledgeSearchArguments> {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchTool.class);

    private static final String NAME = "knowledge_search";
    private static final String DESCRIPTION = "Search knowledge bases for relevant document chunks. "
            + "Returns ranked results with document names, scores, and content snippets.";
    private static final String PARAMETERS_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "query": {"type": "string", "description": "Search query text"},
                "knowledgeBaseIds": {"type": "array", "items": {"type": "integer"}, "description": "Knowledge base IDs to search in"},
                "documentIds": {"type": "array", "items": {"type": "integer"}, "description": "Optional document IDs to filter"},
                "topK": {"type": "integer", "description": "Number of results to return", "default": 5},
                "rerank": {"type": "boolean", "description": "Enable reranking", "default": true}
              },
              "required": ["query", "knowledgeBaseIds"]
            }""";

    private final RetrievalService retrievalService;
    private final RetrievalScopeResolver scopeResolver;

    public KnowledgeSearchTool(RetrievalService retrievalService, RetrievalScopeResolver scopeResolver) {
        this.retrievalService = retrievalService;
        this.scopeResolver = scopeResolver;
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
    public Class<KnowledgeSearchArguments> argumentType() {
        return KnowledgeSearchArguments.class;
    }

    @Override
    public AgentToolExecutionResult execute(ToolExecutionContext ctx, KnowledgeSearchArguments args) {
        try {
            // 1. Resolve scope (authorization + validation)
            RetrievalScope scope = scopeResolver.resolve(
                    ctx.workspaceId(),
                    args.knowledgeBaseIds(),
                    args.documentIds(),
                    ctx.authenticatedUserId());

            // 2. Build query
            RetrievalQuery query = new RetrievalQuery(args.query(), scope);

            // 3. Execute search (default HYBRID, candidateTopK = topK * 3)
            int candidateTopK = args.topK() * 3;
            List<RetrievalResult> results = retrievalService.search(
                    query, RetrievalMode.HYBRID, candidateTopK, args.topK(), args.rerank());

            // 4. Format results
            String content = formatResults(results);
            Map<String, Object> metadata = Map.of("resultCount", results.size());

            return AgentToolExecutionResult.success(content, metadata);

        } catch (BusinessException e) {
            log.debug("Business error in knowledge_search: code={}, msg={}", e.getCode(), e.getMessage());
            return AgentToolExecutionResult.failure(String.valueOf(e.getCode()), e.getMessage());
        } catch (IllegalArgumentException e) {
            log.debug("Invalid argument in knowledge_search: {}", e.getMessage());
            return AgentToolExecutionResult.failure("INVALID_ARGUMENT", e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error in knowledge_search", e);
            throw new ToolException("RETRIEVAL_ERROR", "Search failed: " + e.getMessage(), e);
        }
    }

    private String formatResults(List<RetrievalResult> results) {
        if (results.isEmpty()) {
            return "No relevant documents found.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(results.size()).append(" result(s):\n");
        for (int i = 0; i < results.size(); i++) {
            RetrievalResult r = results.get(i);
            sb.append("[").append(i + 1).append("] ");
            sb.append("score: ").append(String.format("%.4f", r.getScore()));
            if (r.getDocumentId() != null) {
                sb.append(" | documentId: ").append(r.getDocumentId());
            }
            if (r.getKnowledgeBaseId() != null) {
                sb.append(" | kbId: ").append(r.getKnowledgeBaseId());
            }
            if (r.getScoreType() != null) {
                sb.append(" | type: ").append(r.getScoreType().name());
            }
            if (r.getContent() != null) {
                sb.append(" | content: \"").append(truncateContent(r.getContent(), 200)).append("\"");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String truncateContent(String content, int maxLen) {
        if (content.length() <= maxLen) {
            return content;
        }
        return content.substring(0, maxLen) + "...";
    }
}