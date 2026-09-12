package com.intellidesk.agent.tool.business;

import com.intellidesk.agent.tool.ToolRegistry;
import com.intellidesk.document.DocumentService;
import com.intellidesk.knowledge.KnowledgeBaseService;
import com.intellidesk.retrieval.RetrievalScopeResolver;
import com.intellidesk.retrieval.RetrievalService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * Registers all Phase 5 Wave 3 read-only business tools into ToolRegistry on startup.
 * <p>
 * Five tools are registered:
 * <ol>
 *   <li>knowledge_search — search knowledge bases</li>
 *   <li>knowledge_base_list — list knowledge bases</li>
 *   <li>knowledge_base_detail — get knowledge base detail</li>
 *   <li>document_list — list documents in a KB</li>
 *   <li>document_detail — get document detail</li>
 * </ol>
 * <p>
 * Duplicate names, invalid schemas, or null argumentTypes will fail-fast at startup.
 */
@Configuration
public class AgentToolConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentToolConfig.class);

    private final ToolRegistry toolRegistry;
    private final RetrievalService retrievalService;
    private final RetrievalScopeResolver scopeResolver;
    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentService documentService;

    public AgentToolConfig(ToolRegistry toolRegistry,
                           RetrievalService retrievalService,
                           RetrievalScopeResolver scopeResolver,
                           KnowledgeBaseService knowledgeBaseService,
                           DocumentService documentService) {
        this.toolRegistry = toolRegistry;
        this.retrievalService = retrievalService;
        this.scopeResolver = scopeResolver;
        this.knowledgeBaseService = knowledgeBaseService;
        this.documentService = documentService;
    }

    @PostConstruct
    public void registerTools() {
        log.info("Registering Phase 5 Wave 3 business tools...");

        toolRegistry.register(new KnowledgeSearchTool(retrievalService, scopeResolver));
        toolRegistry.register(new KnowledgeBaseListTool(knowledgeBaseService));
        toolRegistry.register(new KnowledgeBaseDetailTool(knowledgeBaseService));
        toolRegistry.register(new DocumentListTool(documentService));
        toolRegistry.register(new DocumentDetailTool(documentService));

        log.info("Registered {} business tools: {}", toolRegistry.registeredNames().size(), toolRegistry.registeredNames());
    }
}