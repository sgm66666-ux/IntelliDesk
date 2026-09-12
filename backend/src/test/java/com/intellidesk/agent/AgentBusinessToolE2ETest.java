package com.intellidesk.agent;

import com.intellidesk.agent.tool.*;
import com.intellidesk.agent.tool.business.KnowledgeSearchArguments;
import com.intellidesk.agent.tool.business.KnowledgeSearchTool;
import com.intellidesk.chat.ChatLlmProperties;
import com.intellidesk.chat.ChatLlmService;
import com.intellidesk.chat.conversation.ConversationService;
import com.intellidesk.chat.dto.AgentChatRequest;
import com.intellidesk.chat.memory.ConversationMemoryService;
import com.intellidesk.chat.sse.SseEventBuilder;
import com.intellidesk.retrieval.RetrievalMode;
import com.intellidesk.retrieval.RetrievalQuery;
import com.intellidesk.retrieval.RetrievalResult;
import com.intellidesk.retrieval.RetrievalScope;
import com.intellidesk.retrieval.RetrievalScopeResolver;
import com.intellidesk.retrieval.RetrievalService;
import com.intellidesk.retrieval.ScoreType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Agent E2E test: mock LLM → knowledge_search tool call → ToolCallingManager → KnowledgeSearchTool → RetrievalService → result.
 * <p>
 * Verifies the full Agent Loop with a real business Tool, without mocking the Tool infrastructure.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Agent E2E with knowledge_search Tool")
class AgentBusinessToolE2ETest {

    @Mock
    private ChatLlmService chatLlmService;
    @Mock
    private ConversationService conversationService;
    @Mock
    private ConversationMemoryService memoryService;
    @Mock
    private SseEventBuilder sseEventBuilder;
    @Mock
    private RetrievalService retrievalService;
    @Mock
    private RetrievalScopeResolver scopeResolver;

    private ToolRegistry toolRegistry;
    private ToolExecutor toolExecutor;
    private ToolCallbackFactory toolCallbackFactory;
    private AgentLoopConfig agentLoopConfig;
    private AgentOrchestrationService service;

    private static final Long WORKSPACE_ID = 100L;
    private static final Long CONVERSATION_ID = 200L;
    private static final Long USER_ID = 1L;

    @BeforeEach
    void setUp() {
        toolRegistry = new ToolRegistryImpl();
        toolExecutor = new TestToolExecutor(toolRegistry);
        toolCallbackFactory = new ToolCallbackFactory(toolExecutor);
        agentLoopConfig = new AgentLoopConfig();
        agentLoopConfig.setMaxSteps(10);
        agentLoopConfig.setTimeoutSeconds(120);
        agentLoopConfig.setSystemPrompt("You are a helpful assistant with tools.");

        ChatLlmProperties chatLlmProperties = new ChatLlmProperties();
        chatLlmProperties.setModel("gpt-4o-mini");

        service = new AgentOrchestrationService(
                chatLlmService, conversationService, memoryService,
                sseEventBuilder, toolRegistry, toolCallbackFactory, agentLoopConfig, chatLlmProperties);
        service.setStreamingExecutor(Runnable::run); // synchronous for testing

        when(sseEventBuilder.newRequestId()).thenReturn("e2e-request-id");
        doNothing().when(conversationService).requireNotBusy(anyLong());
        doNothing().when(conversationService).insertMessagePair(anyLong(), any(), any());
        when(conversationService.tryInsertToolMessage(any(), any(), any())).thenReturn(true);
        when(conversationService.finalizeAssistant(any())).thenReturn(true);
        when(conversationService.get(anyLong(), anyLong(), anyLong())).thenReturn(null);
        when(conversationService.updateMessageCas(any())).thenReturn(true);
        when(memoryService.buildAnswerMessagesExcluding(anyLong(), anyLong(), anyLong(), anyInt(), any()))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("LLM tool_call → knowledge_search → RetrievalService → conversationHistory → final answer")
    void fullKnowledgeSearchE2E() {
        // Register knowledge_search tool
        toolRegistry.register(new KnowledgeSearchTool(retrievalService, scopeResolver));

        // Mock scope resolution
        RetrievalScope scope = RetrievalScope.of(100L, List.of(1L));
        when(scopeResolver.resolve(eq(100L), eq(List.of(1L)), eq(List.of()), eq(1L))).thenReturn(scope);

        // Mock retrieval result
        RetrievalResult r = new RetrievalResult(10L, 5L, 1L, "relevant content", 0.95f, ScoreType.RRF, 0, null);
        when(retrievalService.search(any(RetrievalQuery.class), any(RetrievalMode.class), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(List.of(r));

        // Round 1: LLM returns knowledge_search tool call
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call_1", "function", "knowledge_search",
                "{\"query\":\"test query\",\"knowledgeBaseIds\":[1],\"topK\":5}");
        AssistantMessage toolOutput = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(toolCall))
                .build();
        ChatResponse toolResponse = ChatResponse.builder()
                .generations(List.of(new Generation(toolOutput)))
                .build();

        // Round 2: LLM returns final answer
        AssistantMessage finalOutput = new AssistantMessage("Based on the search results, here is the answer.");
        ChatResponse finalResponse = ChatResponse.builder()
                .generations(List.of(new Generation(finalOutput)))
                .build();

        when(chatLlmService.generate(any(Prompt.class)))
                .thenReturn(toolResponse)
                .thenReturn(finalResponse);

        // Execute
        service.chat(request("search for relevant content"), WORKSPACE_ID, CONVERSATION_ID, USER_ID);

        // Verify: tool_call SSE event sent
        verify(sseEventBuilder).sendToolCall(any(), eq("knowledge_search"), eq(0));

        // Verify: tool_result SSE event sent
        verify(sseEventBuilder).sendToolResult(any(), eq("knowledge_search"), eq(true), anyInt(), anyLong());

        // Verify: final answer
        verify(sseEventBuilder).sendToken(any(), eq("Based on the search results, here is the answer."));

        // Verify: done event
        verify(sseEventBuilder).sendDone(any(), any(), eq("stop"));

        // Verify: knowledge_search was only called via ToolCallingManager (not directly)
        verify(chatLlmService, times(2)).generate(any(Prompt.class));
    }

    @Test
    @DisplayName("knowledge_search executes exactly once")
    void knowledgeSearchExecutesExactlyOnce() {
        toolRegistry.register(new KnowledgeSearchTool(retrievalService, scopeResolver));

        RetrievalScope scope = RetrievalScope.of(100L, List.of(1L));
        when(scopeResolver.resolve(anyLong(), anyList(), any(), anyLong())).thenReturn(scope);

        RetrievalResult r = new RetrievalResult(10L, 5L, 1L, "content", 0.95f, ScoreType.RRF, 0, null);
        when(retrievalService.search(any(RetrievalQuery.class), any(RetrievalMode.class), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(List.of(r));

        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "call_1", "function", "knowledge_search",
                "{\"query\":\"test\",\"knowledgeBaseIds\":[1],\"topK\":5}");
        ChatResponse toolResponse = ChatResponse.builder()
                .generations(List.of(new Generation(AssistantMessage.builder()
                        .content("").toolCalls(List.of(toolCall)).build())))
                .build();
        ChatResponse finalResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("Done"))))
                .build();

        when(chatLlmService.generate(any(Prompt.class)))
                .thenReturn(toolResponse)
                .thenReturn(finalResponse);

        service.chat(request("test"), WORKSPACE_ID, CONVERSATION_ID, USER_ID);

        // RetrievalService.search should be called exactly once
        verify(retrievalService, times(1)).search(any(RetrievalQuery.class), any(RetrievalMode.class), anyInt(), anyInt(), anyBoolean());
    }

    private AgentChatRequest request(String query) {
        AgentChatRequest req = new AgentChatRequest();
        req.setQuery(query);
        return req;
    }

    /**
     * Simple executor for testing — delegates to the registered tool with Map-to-DTO conversion.
     * Mimics ToolExecutorImpl conversion logic without thread pool, timeout, or validation.
     */
    static class TestToolExecutor implements ToolExecutor {
        private final ToolRegistry registry;
        private final ObjectMapper objectMapper = new ObjectMapper();

        TestToolExecutor(ToolRegistry registry) {
            this.registry = registry;
        }

        @Override
        @SuppressWarnings("unchecked")
        public AgentToolExecutionResult execute(String toolName, Map<String, Object> rawArguments, ToolExecutionContext ctx) {
            AgentTool<?> tool = registry.get(toolName);
            if (tool == null) {
                return AgentToolExecutionResult.failure("TOOL_NOT_FOUND", "Unknown tool");
            }
            try {
                Class<?> argType = tool.argumentType();
                Map<String, Object> safeArgs = rawArguments == null ? Map.of() : rawArguments;
                Object typedArgs = objectMapper.convertValue(safeArgs, argType);
                AgentTool<Object> rawTool = (AgentTool<Object>) tool;
                return rawTool.execute(ctx, typedArgs);
            } catch (Exception e) {
                return AgentToolExecutionResult.failure("TOOL_ERROR", e.getMessage());
            }
        }
    }
}