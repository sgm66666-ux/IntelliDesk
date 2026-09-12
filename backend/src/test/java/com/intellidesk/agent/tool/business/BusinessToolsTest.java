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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Business Tools Unit Tests")
class BusinessToolsTest {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;
    @Mock
    private DocumentService documentService;

    private ToolExecutionContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new ToolExecutionContext(1L, 100L, 200L, "trace-001");
    }

    // ==================== knowledge_base_list ====================

    @Nested
    @DisplayName("knowledge_base_list")
    class KnowledgeBaseListTests {

        @Test
        @DisplayName("descriptor: name, description, schema, argumentType")
        void descriptor() {
            KnowledgeBaseListTool tool = new KnowledgeBaseListTool(knowledgeBaseService);
            assertThat(tool.name()).isEqualTo("knowledge_base_list");
            assertThat(tool.description()).contains("List all knowledge bases");
            assertThat(tool.parametersSchema()).contains("page");
            assertThat(tool.argumentType()).isEqualTo(KnowledgeBaseListArguments.class);
        }

        @Test
        @DisplayName("success: returns formatted KB list")
        void success() {
            KnowledgeBase kb = new KnowledgeBase();
            kb.setId(1L);
            kb.setName("Test KB");
            kb.setDescription("A test knowledge base");
            kb.setStatus("ACTIVE");
            PageResponse<KnowledgeBase> page = PageResponse.<KnowledgeBase>builder()
                    .items(List.of(kb)).page(1).size(20).total(1).build();

            when(knowledgeBaseService.listKnowledgeBases(eq(100L), eq(1L), eq(1), eq(20), any()))
                    .thenReturn(page);

            KnowledgeBaseListTool tool = new KnowledgeBaseListTool(knowledgeBaseService);
            AgentToolExecutionResult result = tool.execute(ctx, new KnowledgeBaseListArguments(1, 20));

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("Test KB");
            assertThat(result.content()).contains("id=1");
            assertThat(result.content()).contains("ACTIVE");
            assertThat(result.metadata()).containsEntry("resultCount", 1);
        }

        @Test
        @DisplayName("empty list: returns empty message")
        void emptyList() {
            PageResponse<KnowledgeBase> page = PageResponse.<KnowledgeBase>builder()
                    .items(List.of()).page(1).size(20).total(0).build();

            when(knowledgeBaseService.listKnowledgeBases(anyLong(), anyLong(), anyInt(), anyInt(), any()))
                    .thenReturn(page);

            KnowledgeBaseListTool tool = new KnowledgeBaseListTool(knowledgeBaseService);
            AgentToolExecutionResult result = tool.execute(ctx, new KnowledgeBaseListArguments(1, 20));

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("No knowledge bases found");
            assertThat(result.metadata()).containsEntry("resultCount", 0);
        }

        @Test
        @DisplayName("authorization failure: returns tool failure")
        void authorizationFailure() {
            when(knowledgeBaseService.listKnowledgeBases(anyLong(), anyLong(), anyInt(), anyInt(), any()))
                    .thenThrow(new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED));

            KnowledgeBaseListTool tool = new KnowledgeBaseListTool(knowledgeBaseService);
            AgentToolExecutionResult result = tool.execute(ctx, new KnowledgeBaseListArguments(1, 20));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isNotNull();
        }
    }

    // ==================== knowledge_base_detail ====================

    @Nested
    @DisplayName("knowledge_base_detail")
    class KnowledgeBaseDetailTests {

        @Test
        @DisplayName("descriptor: name, description, schema, argumentType")
        void descriptor() {
            KnowledgeBaseDetailTool tool = new KnowledgeBaseDetailTool(knowledgeBaseService);
            assertThat(tool.name()).isEqualTo("knowledge_base_detail");
            assertThat(tool.description()).contains("Get detailed information");
            assertThat(tool.parametersSchema()).contains("knowledgeBaseId");
            assertThat(tool.argumentType()).isEqualTo(KnowledgeBaseDetailArguments.class);
        }

        @Test
        @DisplayName("success: returns formatted KB detail")
        void success() {
            KnowledgeBase kb = new KnowledgeBase();
            kb.setId(1L);
            kb.setName("Detail KB");
            kb.setDescription("A detailed KB");
            kb.setStatus("ACTIVE");
            kb.setChunkStrategy("RECURSIVE");
            kb.setChunkSize(1000);
            kb.setChunkOverlap(150);

            when(knowledgeBaseService.getKnowledgeBase(100L, 1L, 1L)).thenReturn(kb);

            KnowledgeBaseDetailTool tool = new KnowledgeBaseDetailTool(knowledgeBaseService);
            AgentToolExecutionResult result = tool.execute(ctx, new KnowledgeBaseDetailArguments(1L));

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("Detail KB");
            assertThat(result.content()).contains("RECURSIVE");
            assertThat(result.content()).contains("chunkSize=1000");
        }

        @Test
        @DisplayName("knowledge base not found: returns failure")
        void notFound() {
            when(knowledgeBaseService.getKnowledgeBase(100L, 999L, 1L))
                    .thenThrow(new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));

            KnowledgeBaseDetailTool tool = new KnowledgeBaseDetailTool(knowledgeBaseService);
            AgentToolExecutionResult result = tool.execute(ctx, new KnowledgeBaseDetailArguments(999L));

            assertThat(result.success()).isFalse();
        }

        @Test
        @DisplayName("cross-workspace KB: returns failure")
        void crossWorkspaceKb() {
            when(knowledgeBaseService.getKnowledgeBase(100L, 5L, 1L))
                    .thenThrow(new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));

            KnowledgeBaseDetailTool tool = new KnowledgeBaseDetailTool(knowledgeBaseService);
            AgentToolExecutionResult result = tool.execute(ctx, new KnowledgeBaseDetailArguments(5L));

            assertThat(result.success()).isFalse();
        }
    }

    // ==================== document_list ====================

    @Nested
    @DisplayName("document_list")
    class DocumentListTests {

        @Test
        @DisplayName("descriptor: name, description, schema, argumentType")
        void descriptor() {
            DocumentListTool tool = new DocumentListTool(documentService);
            assertThat(tool.name()).isEqualTo("document_list");
            assertThat(tool.description()).contains("List all documents");
            assertThat(tool.parametersSchema()).contains("knowledgeBaseId");
            assertThat(tool.argumentType()).isEqualTo(DocumentListArguments.class);
        }

        @Test
        @DisplayName("success: returns formatted document list")
        void success() {
            DocumentResponse doc = DocumentResponse.builder()
                    .id(10L).fileName("test.pdf").status("COMPLETED").fileSize(1024L).build();
            PageResponse<DocumentResponse> page = PageResponse.<DocumentResponse>builder()
                    .items(List.of(doc)).page(1).size(20).total(1).build();

            when(documentService.listDocuments(100L, 1L, 1L, 1, 20, null)).thenReturn(page);

            DocumentListTool tool = new DocumentListTool(documentService);
            AgentToolExecutionResult result = tool.execute(ctx, new DocumentListArguments(1L, 1, 20));

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("test.pdf");
            assertThat(result.content()).contains("id=10");
            assertThat(result.content()).contains("COMPLETED");
        }

        @Test
        @DisplayName("empty list: returns empty message")
        void emptyList() {
            PageResponse<DocumentResponse> page = PageResponse.<DocumentResponse>builder()
                    .items(List.of()).page(1).size(20).total(0).build();

            when(documentService.listDocuments(anyLong(), anyLong(), anyLong(), anyInt(), anyInt(), any()))
                    .thenReturn(page);

            DocumentListTool tool = new DocumentListTool(documentService);
            AgentToolExecutionResult result = tool.execute(ctx, new DocumentListArguments(1L, 1, 20));

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("No documents found");
        }

        @Test
        @DisplayName("KB not found: returns failure")
        void kbNotFound() {
            when(documentService.listDocuments(anyLong(), anyLong(), anyLong(), anyInt(), anyInt(), any()))
                    .thenThrow(new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));

            DocumentListTool tool = new DocumentListTool(documentService);
            AgentToolExecutionResult result = tool.execute(ctx, new DocumentListArguments(999L, 1, 20));

            assertThat(result.success()).isFalse();
        }
    }

    // ==================== document_detail ====================

    @Nested
    @DisplayName("document_detail")
    class DocumentDetailTests {

        @Test
        @DisplayName("descriptor: name, description, schema, argumentType")
        void descriptor() {
            DocumentDetailTool tool = new DocumentDetailTool(documentService);
            assertThat(tool.name()).isEqualTo("document_detail");
            assertThat(tool.description()).contains("Get detailed information");
            assertThat(tool.parametersSchema()).contains("knowledgeBaseId");
            assertThat(tool.parametersSchema()).contains("documentId");
            assertThat(tool.argumentType()).isEqualTo(DocumentDetailArguments.class);
        }

        @Test
        @DisplayName("success: returns formatted document detail")
        void success() {
            DocumentResponse doc = DocumentResponse.builder()
                    .id(10L).fileName("report.pdf").status("COMPLETED")
                    .fileSize(2048L).contentType("application/pdf").fileExtension("pdf")
                    .chunkStrategy("RECURSIVE").chunkSize(1000).chunkOverlap(150).build();

            when(documentService.getDocument(100L, 1L, 10L, 1L)).thenReturn(doc);

            DocumentDetailTool tool = new DocumentDetailTool(documentService);
            AgentToolExecutionResult result = tool.execute(ctx, new DocumentDetailArguments(1L, 10L));

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("report.pdf");
            assertThat(result.content()).contains("COMPLETED");
            assertThat(result.content()).contains("application/pdf");
        }

        @Test
        @DisplayName("document not found: returns failure")
        void notFound() {
            when(documentService.getDocument(100L, 1L, 999L, 1L))
                    .thenThrow(new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));

            DocumentDetailTool tool = new DocumentDetailTool(documentService);
            AgentToolExecutionResult result = tool.execute(ctx, new DocumentDetailArguments(1L, 999L));

            assertThat(result.success()).isFalse();
        }

        @Test
        @DisplayName("document/KB mismatch: returns failure")
        void documentKbMismatch() {
            when(documentService.getDocument(100L, 1L, 10L, 1L))
                    .thenThrow(new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));

            DocumentDetailTool tool = new DocumentDetailTool(documentService);
            AgentToolExecutionResult result = tool.execute(ctx, new DocumentDetailArguments(1L, 10L));

            assertThat(result.success()).isFalse();
        }
    }
}