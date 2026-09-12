package com.intellidesk.agent.tool.business;

import com.intellidesk.agent.tool.AgentToolExecutionResult;
import com.intellidesk.agent.tool.ToolExecutionContext;
import com.intellidesk.common.BusinessException;
import com.intellidesk.common.ErrorCode;
import com.intellidesk.retrieval.RetrievalMode;
import com.intellidesk.retrieval.RetrievalQuery;
import com.intellidesk.retrieval.RetrievalResult;
import com.intellidesk.retrieval.RetrievalScope;
import com.intellidesk.retrieval.RetrievalScopeResolver;
import com.intellidesk.retrieval.RetrievalService;
import com.intellidesk.retrieval.ScoreType;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("KnowledgeSearchTool Unit Tests")
class KnowledgeSearchToolTest {

    @Mock
    private RetrievalService retrievalService;
    @Mock
    private RetrievalScopeResolver scopeResolver;

    private ToolExecutionContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new ToolExecutionContext(1L, 100L, 200L, "trace-001");
    }

    @Test
    @DisplayName("descriptor: name, description, schema, argumentType")
    void descriptor() {
        KnowledgeSearchTool tool = new KnowledgeSearchTool(retrievalService, scopeResolver);
        assertThat(tool.name()).isEqualTo("knowledge_search");
        assertThat(tool.description()).contains("Search knowledge bases");
        assertThat(tool.parametersSchema()).contains("query");
        assertThat(tool.parametersSchema()).contains("knowledgeBaseIds");
        assertThat(tool.argumentType()).isEqualTo(KnowledgeSearchArguments.class);
    }

    @Nested
    @DisplayName("normal execution")
    class NormalExecutionTests {

        @Test
        @DisplayName("returns formatted search results")
        void returnsFormattedResults() {
            RetrievalScope scope = RetrievalScope.of(100L, List.of(1L));
            when(scopeResolver.resolve(100L, List.of(1L), List.of(), 1L)).thenReturn(scope);

            RetrievalResult r = new RetrievalResult(10L, 5L, 1L, "relevant content here", 0.95f, ScoreType.RRF, 0, null);
            when(retrievalService.search(any(RetrievalQuery.class), any(RetrievalMode.class), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of(r));

            KnowledgeSearchTool tool = new KnowledgeSearchTool(retrievalService, scopeResolver);
            AgentToolExecutionResult result = tool.execute(ctx,
                    new KnowledgeSearchArguments("test query", List.of(1L), null, 5, true));

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("Found 1 result");
            assertThat(result.content()).contains("relevant content");
            assertThat(result.content()).contains("0.9500");
            assertThat(result.metadata()).containsEntry("resultCount", 1);
        }

        @Test
        @DisplayName("empty retrieval returns empty message")
        void emptyRetrieval() {
            RetrievalScope scope = RetrievalScope.of(100L, List.of(1L));
            when(scopeResolver.resolve(anyLong(), anyList(), any(), anyLong())).thenReturn(scope);
            when(retrievalService.search(any(RetrievalQuery.class), any(RetrievalMode.class), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            KnowledgeSearchTool tool = new KnowledgeSearchTool(retrievalService, scopeResolver);
            AgentToolExecutionResult result = tool.execute(ctx,
                    new KnowledgeSearchArguments("no results", List.of(1L), null, 5, true));

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("No relevant documents found");
            assertThat(result.metadata()).containsEntry("resultCount", 0);
        }
    }

    @Nested
    @DisplayName("error handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("scope resolution failure: returns failure")
        void scopeResolutionFailure() {
            when(scopeResolver.resolve(anyLong(), anyList(), any(), anyLong()))
                    .thenThrow(new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));

            KnowledgeSearchTool tool = new KnowledgeSearchTool(retrievalService, scopeResolver);
            AgentToolExecutionResult result = tool.execute(ctx,
                    new KnowledgeSearchArguments("test", List.of(999L), null, 5, true));

            assertThat(result.success()).isFalse();
        }

        @Test
        @DisplayName("cross-workspace KB: returns failure")
        void crossWorkspaceKb() {
            when(scopeResolver.resolve(anyLong(), anyList(), any(), anyLong()))
                    .thenThrow(new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));

            KnowledgeSearchTool tool = new KnowledgeSearchTool(retrievalService, scopeResolver);
            AgentToolExecutionResult result = tool.execute(ctx,
                    new KnowledgeSearchArguments("test", List.of(5L), null, 5, true));

            assertThat(result.success()).isFalse();
        }

        @Test
        @DisplayName("empty scope: returns failure")
        void emptyScope() {
            when(scopeResolver.resolve(anyLong(), anyList(), any(), anyLong()))
                    .thenThrow(new BusinessException(ErrorCode.RETRIEVAL_SCOPE_EMPTY));

            KnowledgeSearchTool tool = new KnowledgeSearchTool(retrievalService, scopeResolver);
            AgentToolExecutionResult result = tool.execute(ctx,
                    new KnowledgeSearchArguments("test", List.of(), null, 5, true));

            assertThat(result.success()).isFalse();
        }

        @Test
        @DisplayName("retrieval provider failure: throws ToolException")
        void retrievalProviderFailure() {
            RetrievalScope scope = RetrievalScope.of(100L, List.of(1L));
            when(scopeResolver.resolve(anyLong(), anyList(), any(), anyLong())).thenReturn(scope);
            when(retrievalService.search(any(RetrievalQuery.class), any(RetrievalMode.class), anyInt(), anyInt(), anyBoolean()))
                    .thenThrow(new RuntimeException("ES connection refused"));

            KnowledgeSearchTool tool = new KnowledgeSearchTool(retrievalService, scopeResolver);
            try {
                tool.execute(ctx, new KnowledgeSearchArguments("test", List.of(1L), null, 5, true));
                // Should not reach here
                assertThat(true).isFalse(); // force fail
            } catch (Exception e) {
                // ToolException is expected - ToolExecutorImpl catches it
                assertThat(e.getMessage()).contains("Search failed");
            }
        }
    }

    @Nested
    @DisplayName("result sanitization")
    class ResultSanitizationTests {

        @Test
        @DisplayName("content is truncated in result")
        void contentIsTruncated() {
            RetrievalScope scope = RetrievalScope.of(100L, List.of(1L));
            when(scopeResolver.resolve(anyLong(), anyList(), any(), anyLong())).thenReturn(scope);

            String longContent = "A".repeat(500);
            RetrievalResult r = new RetrievalResult(10L, 5L, 1L, longContent, 0.5f, ScoreType.RRF, 0, null);
            when(retrievalService.search(any(RetrievalQuery.class), any(RetrievalMode.class), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of(r));

            KnowledgeSearchTool tool = new KnowledgeSearchTool(retrievalService, scopeResolver);
            AgentToolExecutionResult result = tool.execute(ctx,
                    new KnowledgeSearchArguments("test", List.of(1L), null, 5, true));

            assertThat(result.success()).isTrue();
            // Content truncated to 200 + "..."
            assertThat(result.content()).contains("...");
            assertThat(result.content()).doesNotContain("A".repeat(500));
        }

        @Test
        @DisplayName("no internal fields leaked to LLM")
        void noInternalFieldsLeaked() {
            RetrievalScope scope = RetrievalScope.of(100L, List.of(1L));
            when(scopeResolver.resolve(anyLong(), anyList(), any(), anyLong())).thenReturn(scope);

            RetrievalResult r = new RetrievalResult(10L, 5L, 1L, "safe content", 0.95f, ScoreType.RRF, 0, null);
            when(retrievalService.search(any(RetrievalQuery.class), any(RetrievalMode.class), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of(r));

            KnowledgeSearchTool tool = new KnowledgeSearchTool(retrievalService, scopeResolver);
            AgentToolExecutionResult result = tool.execute(ctx,
                    new KnowledgeSearchArguments("test", List.of(1L), null, 5, true));

            assertThat(result.success()).isTrue();
            assertThat(result.content()).doesNotContain("object_key");
            assertThat(result.content()).doesNotContain("bucket");
            assertThat(result.content()).doesNotContain("embedding");
            assertThat(result.content()).doesNotContain("vector");
            assertThat(result.content()).doesNotContain("stack trace");
            assertThat(result.content()).doesNotContain("SQL");
        }
    }
}