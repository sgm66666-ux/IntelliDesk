package com.intellidesk.agent.tool.business;

import com.intellidesk.agent.tool.AgentToolExecutionResult;
import com.intellidesk.agent.tool.ToolExecutionContext;
import com.intellidesk.common.BusinessException;
import com.intellidesk.common.ErrorCode;
import com.intellidesk.common.dto.PageResponse;
import com.intellidesk.document.DocumentService;
import com.intellidesk.document.dto.DocumentResponse;
import com.intellidesk.knowledge.KnowledgeBase;
import com.intellidesk.knowledge.KnowledgeBaseService;
import com.intellidesk.retrieval.RetrievalMode;
import com.intellidesk.retrieval.RetrievalQuery;
import com.intellidesk.retrieval.RetrievalResult;
import com.intellidesk.retrieval.RetrievalScope;
import com.intellidesk.retrieval.RetrievalScopeResolver;
import com.intellidesk.retrieval.RetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * IDOR Security Matrix tests for Phase 5 Wave 3 business tools.
 * <p>
 * Covers: Owner, Member, Outsider, Cross-workspace for:
 * knowledge_base_detail, document_list, document_detail,
 * knowledge_search scoped KB, knowledge_search scoped document.
 * <p>
 * Security is enforced by the services (KnowledgeBaseService, DocumentService,
 * RetrievalScopeResolver). Tools pass workspaceId/userId from ToolExecutionContext.
 * These tests verify tools properly delegate to services and return failure results.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Business Tools IDOR Security Tests")
class BusinessToolsSecurityTest {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;
    @Mock
    private DocumentService documentService;
    @Mock
    private RetrievalScopeResolver scopeResolver;
    @Mock
    private RetrievalService retrievalService;

    private static final Long OWNER_ID = 1L;
    private static final Long MEMBER_ID = 2L;
    private static final Long OUTSIDER_ID = 999L;
    private static final Long WORKSPACE_ID = 100L;
    private static final Long OTHER_WORKSPACE_ID = 200L;
    private static final Long KB_ID = 10L;
    private static final Long OTHER_KB_ID = 20L; // belongs to other workspace
    private static final Long DOC_ID = 50L;
    private static final Long OTHER_DOC_ID = 60L; // belongs to other workspace

    private ToolExecutionContext ownerCtx;
    private ToolExecutionContext memberCtx;
    private ToolExecutionContext outsiderCtx;

    @BeforeEach
    void setUp() {
        ownerCtx = new ToolExecutionContext(OWNER_ID, WORKSPACE_ID, 300L, "trace-owner");
        memberCtx = new ToolExecutionContext(MEMBER_ID, WORKSPACE_ID, 300L, "trace-member");
        outsiderCtx = new ToolExecutionContext(OUTSIDER_ID, WORKSPACE_ID, 300L, "trace-outsider");
    }

    // ================================================================
    // knowledge_base_detail IDOR
    // ================================================================

    @Nested
    @DisplayName("knowledge_base_detail - IDOR")
    class KnowledgeBaseDetailIdor {

        @Test
        @DisplayName("Owner can access KB detail")
        void ownerCanAccess() {
            KnowledgeBase kb = makeKb(KB_ID, "Test KB");
            when(knowledgeBaseService.getKnowledgeBase(WORKSPACE_ID, KB_ID, OWNER_ID)).thenReturn(kb);

            KnowledgeBaseDetailTool tool = new KnowledgeBaseDetailTool(knowledgeBaseService);
            AgentToolExecutionResult result = tool.execute(ownerCtx, new KnowledgeBaseDetailArguments(KB_ID));

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("Test KB");
        }

        @Test
        @DisplayName("Member can access KB detail")
        void memberCanAccess() {
            KnowledgeBase kb = makeKb(KB_ID, "Test KB");
            when(knowledgeBaseService.getKnowledgeBase(WORKSPACE_ID, KB_ID, MEMBER_ID)).thenReturn(kb);

            KnowledgeBaseDetailTool tool = new KnowledgeBaseDetailTool(knowledgeBaseService);
            AgentToolExecutionResult result = tool.execute(memberCtx, new KnowledgeBaseDetailArguments(KB_ID));

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("Test KB");
        }

        @Test
        @DisplayName("Outsider rejected from KB detail")
        void outsiderRejected() {
            when(knowledgeBaseService.getKnowledgeBase(WORKSPACE_ID, KB_ID, OUTSIDER_ID))
                    .thenThrow(new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED));

            KnowledgeBaseDetailTool tool = new KnowledgeBaseDetailTool(knowledgeBaseService);
            AgentToolExecutionResult result = tool.execute(outsiderCtx, new KnowledgeBaseDetailArguments(KB_ID));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isNotNull();
        }

        @Test
        @DisplayName("Cross-workspace KB IDOR: KB from other workspace returns failure")
        void crossWorkspaceKb() {
            when(knowledgeBaseService.getKnowledgeBase(WORKSPACE_ID, OTHER_KB_ID, OWNER_ID))
                    .thenThrow(new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));

            KnowledgeBaseDetailTool tool = new KnowledgeBaseDetailTool(knowledgeBaseService);
            AgentToolExecutionResult result = tool.execute(ownerCtx, new KnowledgeBaseDetailArguments(OTHER_KB_ID));

            assertThat(result.success()).isFalse();
        }

        @Test
        @DisplayName("Forged KB ID from LLM (non-existent) returns failure")
        void forgedKbId() {
            when(knowledgeBaseService.getKnowledgeBase(WORKSPACE_ID, 99999L, OWNER_ID))
                    .thenThrow(new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));

            KnowledgeBaseDetailTool tool = new KnowledgeBaseDetailTool(knowledgeBaseService);
            AgentToolExecutionResult result = tool.execute(ownerCtx, new KnowledgeBaseDetailArguments(99999L));

            assertThat(result.success()).isFalse();
        }
    }

    // ================================================================
    // document_list IDOR
    // ================================================================

    @Nested
    @DisplayName("document_list - IDOR")
    class DocumentListIdor {

        @Test
        @DisplayName("Owner can list documents")
        void ownerCanList() {
            PageResponse<DocumentResponse> page = PageResponse.<DocumentResponse>builder()
                    .items(List.of()).page(1).size(20).total(0).build();
            when(documentService.listDocuments(WORKSPACE_ID, KB_ID, OWNER_ID, 1, 20, null)).thenReturn(page);

            DocumentListTool tool = new DocumentListTool(documentService);
            AgentToolExecutionResult result = tool.execute(ownerCtx, new DocumentListArguments(KB_ID, 1, 20));

            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("Member can list documents")
        void memberCanList() {
            PageResponse<DocumentResponse> page = PageResponse.<DocumentResponse>builder()
                    .items(List.of()).page(1).size(20).total(0).build();
            when(documentService.listDocuments(WORKSPACE_ID, KB_ID, MEMBER_ID, 1, 20, null)).thenReturn(page);

            DocumentListTool tool = new DocumentListTool(documentService);
            AgentToolExecutionResult result = tool.execute(memberCtx, new DocumentListArguments(KB_ID, 1, 20));

            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("Outsider rejected from document list")
        void outsiderRejected() {
            when(documentService.listDocuments(WORKSPACE_ID, KB_ID, OUTSIDER_ID, 1, 20, null))
                    .thenThrow(new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED));

            DocumentListTool tool = new DocumentListTool(documentService);
            AgentToolExecutionResult result = tool.execute(outsiderCtx, new DocumentListArguments(KB_ID, 1, 20));

            assertThat(result.success()).isFalse();
        }

        @Test
        @DisplayName("Cross-workspace KB IDOR: listing docs from other workspace KB returns failure")
        void crossWorkspaceKb() {
            when(documentService.listDocuments(WORKSPACE_ID, OTHER_KB_ID, OWNER_ID, 1, 20, null))
                    .thenThrow(new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));

            DocumentListTool tool = new DocumentListTool(documentService);
            AgentToolExecutionResult result = tool.execute(ownerCtx, new DocumentListArguments(OTHER_KB_ID, 1, 20));

            assertThat(result.success()).isFalse();
        }
    }

    // ================================================================
    // document_detail IDOR
    // ================================================================

    @Nested
    @DisplayName("document_detail - IDOR")
    class DocumentDetailIdor {

        @Test
        @DisplayName("Owner can access document detail")
        void ownerCanAccess() {
            DocumentResponse doc = makeDoc(DOC_ID, "report.pdf");
            when(documentService.getDocument(WORKSPACE_ID, KB_ID, DOC_ID, OWNER_ID)).thenReturn(doc);

            DocumentDetailTool tool = new DocumentDetailTool(documentService);
            AgentToolExecutionResult result = tool.execute(ownerCtx, new DocumentDetailArguments(KB_ID, DOC_ID));

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("report.pdf");
        }

        @Test
        @DisplayName("Member can access document detail")
        void memberCanAccess() {
            DocumentResponse doc = makeDoc(DOC_ID, "report.pdf");
            when(documentService.getDocument(WORKSPACE_ID, KB_ID, DOC_ID, MEMBER_ID)).thenReturn(doc);

            DocumentDetailTool tool = new DocumentDetailTool(documentService);
            AgentToolExecutionResult result = tool.execute(memberCtx, new DocumentDetailArguments(KB_ID, DOC_ID));

            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("Outsider rejected from document detail")
        void outsiderRejected() {
            when(documentService.getDocument(WORKSPACE_ID, KB_ID, DOC_ID, OUTSIDER_ID))
                    .thenThrow(new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED));

            DocumentDetailTool tool = new DocumentDetailTool(documentService);
            AgentToolExecutionResult result = tool.execute(outsiderCtx, new DocumentDetailArguments(KB_ID, DOC_ID));

            assertThat(result.success()).isFalse();
        }

        @Test
        @DisplayName("Cross-workspace document IDOR: document from other workspace returns failure")
        void crossWorkspaceDocument() {
            when(documentService.getDocument(WORKSPACE_ID, KB_ID, OTHER_DOC_ID, OWNER_ID))
                    .thenThrow(new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));

            DocumentDetailTool tool = new DocumentDetailTool(documentService);
            AgentToolExecutionResult result = tool.execute(ownerCtx, new DocumentDetailArguments(KB_ID, OTHER_DOC_ID));

            assertThat(result.success()).isFalse();
        }

        @Test
        @DisplayName("Document/KB mismatch: document does not belong to specified KB")
        void documentKbMismatch() {
            when(documentService.getDocument(WORKSPACE_ID, KB_ID, DOC_ID, OWNER_ID))
                    .thenThrow(new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));

            DocumentDetailTool tool = new DocumentDetailTool(documentService);
            AgentToolExecutionResult result = tool.execute(ownerCtx, new DocumentDetailArguments(KB_ID, DOC_ID));

            assertThat(result.success()).isFalse();
        }

        @Test
        @DisplayName("Forged document ID from LLM returns failure")
        void forgedDocumentId() {
            when(documentService.getDocument(WORKSPACE_ID, KB_ID, 99999L, OWNER_ID))
                    .thenThrow(new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));

            DocumentDetailTool tool = new DocumentDetailTool(documentService);
            AgentToolExecutionResult result = tool.execute(ownerCtx, new DocumentDetailArguments(KB_ID, 99999L));

            assertThat(result.success()).isFalse();
        }
    }

    // ================================================================
    // knowledge_search IDOR (scoped KB and document)
    // ================================================================

    @Nested
    @DisplayName("knowledge_search - IDOR")
    class KnowledgeSearchIdor {

        @Test
        @DisplayName("Owner can search scoped KB")
        void ownerCanSearchScopedKb() {
            RetrievalScope scope = RetrievalScope.of(WORKSPACE_ID, List.of(KB_ID));
            when(scopeResolver.resolve(WORKSPACE_ID, List.of(KB_ID), List.of(), OWNER_ID)).thenReturn(scope);
            when(retrievalService.search(any(RetrievalQuery.class), any(RetrievalMode.class), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            KnowledgeSearchTool tool = new KnowledgeSearchTool(retrievalService, scopeResolver);
            AgentToolExecutionResult result = tool.execute(ownerCtx,
                    new KnowledgeSearchArguments("test", List.of(KB_ID), null, 5, true));

            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("Member can search scoped KB")
        void memberCanSearchScopedKb() {
            RetrievalScope scope = RetrievalScope.of(WORKSPACE_ID, List.of(KB_ID));
            when(scopeResolver.resolve(WORKSPACE_ID, List.of(KB_ID), List.of(), MEMBER_ID)).thenReturn(scope);
            when(retrievalService.search(any(RetrievalQuery.class), any(RetrievalMode.class), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            KnowledgeSearchTool tool = new KnowledgeSearchTool(retrievalService, scopeResolver);
            AgentToolExecutionResult result = tool.execute(memberCtx,
                    new KnowledgeSearchArguments("test", List.of(KB_ID), null, 5, true));

            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("Outsider rejected from searching scoped KB")
        void outsiderRejectedFromSearch() {
            when(scopeResolver.resolve(WORKSPACE_ID, List.of(KB_ID), List.of(), OUTSIDER_ID))
                    .thenThrow(new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED));

            KnowledgeSearchTool tool = new KnowledgeSearchTool(null, scopeResolver);
            AgentToolExecutionResult result = tool.execute(outsiderCtx,
                    new KnowledgeSearchArguments("test", List.of(KB_ID), null, 5, true));

            assertThat(result.success()).isFalse();
        }

        @Test
        @DisplayName("Cross-workspace KB IDOR: searching KB from other workspace returns failure")
        void crossWorkspaceKbSearch() {
            when(scopeResolver.resolve(WORKSPACE_ID, List.of(OTHER_KB_ID), List.of(), OWNER_ID))
                    .thenThrow(new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));

            KnowledgeSearchTool tool = new KnowledgeSearchTool(null, scopeResolver);
            AgentToolExecutionResult result = tool.execute(ownerCtx,
                    new KnowledgeSearchArguments("test", List.of(OTHER_KB_ID), null, 5, true));

            assertThat(result.success()).isFalse();
        }

        @Test
        @DisplayName("Cross-workspace document IDOR: searching scoped document from other workspace returns failure")
        void crossWorkspaceDocSearch() {
            when(scopeResolver.resolve(WORKSPACE_ID, List.of(KB_ID), List.of(OTHER_DOC_ID), OWNER_ID))
                    .thenThrow(new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));

            KnowledgeSearchTool tool = new KnowledgeSearchTool(null, scopeResolver);
            AgentToolExecutionResult result = tool.execute(ownerCtx,
                    new KnowledgeSearchArguments("test", List.of(KB_ID), List.of(OTHER_DOC_ID), 5, true));

            assertThat(result.success()).isFalse();
        }

        @Test
        @DisplayName("Forged knowledge base IDs from LLM return failure")
        void forgedKbIds() {
            when(scopeResolver.resolve(WORKSPACE_ID, List.of(99999L), List.of(), OWNER_ID))
                    .thenThrow(new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));

            KnowledgeSearchTool tool = new KnowledgeSearchTool(null, scopeResolver);
            AgentToolExecutionResult result = tool.execute(ownerCtx,
                    new KnowledgeSearchArguments("test", List.of(99999L), null, 5, true));

            assertThat(result.success()).isFalse();
        }
    }

    // ================================================================
    // knowledge_base_list IDOR
    // ================================================================

    @Nested
    @DisplayName("knowledge_base_list - IDOR")
    class KnowledgeBaseListIdor {

        @Test
        @DisplayName("Owner can list KBs")
        void ownerCanList() {
            PageResponse<KnowledgeBase> page = PageResponse.<KnowledgeBase>builder()
                    .items(List.of()).page(1).size(20).total(0).build();
            when(knowledgeBaseService.listKnowledgeBases(WORKSPACE_ID, OWNER_ID, 1, 20, null)).thenReturn(page);

            KnowledgeBaseListTool tool = new KnowledgeBaseListTool(knowledgeBaseService);
            AgentToolExecutionResult result = tool.execute(ownerCtx, new KnowledgeBaseListArguments(1, 20));

            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("Member can list KBs")
        void memberCanList() {
            PageResponse<KnowledgeBase> page = PageResponse.<KnowledgeBase>builder()
                    .items(List.of()).page(1).size(20).total(0).build();
            when(knowledgeBaseService.listKnowledgeBases(WORKSPACE_ID, MEMBER_ID, 1, 20, null)).thenReturn(page);

            KnowledgeBaseListTool tool = new KnowledgeBaseListTool(knowledgeBaseService);
            AgentToolExecutionResult result = tool.execute(memberCtx, new KnowledgeBaseListArguments(1, 20));

            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("Outsider rejected from listing KBs")
        void outsiderRejected() {
            when(knowledgeBaseService.listKnowledgeBases(WORKSPACE_ID, OUTSIDER_ID, 1, 20, null))
                    .thenThrow(new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED));

            KnowledgeBaseListTool tool = new KnowledgeBaseListTool(knowledgeBaseService);
            AgentToolExecutionResult result = tool.execute(outsiderCtx, new KnowledgeBaseListArguments(1, 20));

            assertThat(result.success()).isFalse();
        }

        @Test
        @DisplayName("Workspace ID from context is used, not from LLM")
        void workspaceIdFromContext() {
            // workspaceId is always from ToolExecutionContext, verified by service parameter matching
            PageResponse<KnowledgeBase> page = PageResponse.<KnowledgeBase>builder()
                    .items(List.of()).page(1).size(20).total(0).build();
            when(knowledgeBaseService.listKnowledgeBases(WORKSPACE_ID, OWNER_ID, 1, 20, null)).thenReturn(page);

            KnowledgeBaseListTool tool = new KnowledgeBaseListTool(knowledgeBaseService);
            AgentToolExecutionResult result = tool.execute(ownerCtx, new KnowledgeBaseListArguments(1, 20));

            assertThat(result.success()).isTrue();
            // The service was called with WORKSPACE_ID from context, not from any argument
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    private KnowledgeBase makeKb(Long id, String name) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setName(name);
        kb.setDescription("desc");
        kb.setStatus("ACTIVE");
        kb.setChunkStrategy("RECURSIVE");
        kb.setChunkSize(1000);
        kb.setChunkOverlap(150);
        return kb;
    }

    private DocumentResponse makeDoc(Long id, String fileName) {
        return DocumentResponse.builder()
                .id(id).fileName(fileName).status("COMPLETED")
                .fileSize(1024L).contentType("application/pdf").fileExtension("pdf")
                .chunkStrategy("RECURSIVE").chunkSize(1000).chunkOverlap(150).build();
    }
}