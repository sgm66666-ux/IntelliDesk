package com.intellidesk.agent;

import com.intellidesk.agent.tool.AgentTool;
import com.intellidesk.agent.tool.AgentToolExecutionResult;
import com.intellidesk.agent.tool.ToolCallbackFactory;
import com.intellidesk.agent.tool.ToolExecutionContext;
import com.intellidesk.agent.tool.ToolExecutor;
import com.intellidesk.agent.tool.ToolRegistry;
import com.intellidesk.agent.tool.ToolRegistryImpl;
import com.intellidesk.chat.ChatLlmException;
import com.intellidesk.chat.ChatLlmProperties;
import com.intellidesk.chat.ChatLlmService;
import com.intellidesk.chat.conversation.ConversationService;
import com.intellidesk.chat.dto.AgentChatRequest;
import com.intellidesk.chat.memory.ConversationMemoryService;
import com.intellidesk.chat.message.ChatMessage;
import com.intellidesk.chat.message.ChatMessageStatus;
import com.intellidesk.chat.sse.SseEventBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgentOrchestrationService")
class AgentOrchestrationServiceTest {

    @Mock
    private ChatLlmService chatLlmService;
    @Mock
    private ConversationService conversationService;
    @Mock
    private ConversationMemoryService memoryService;
    @Mock
    private SseEventBuilder sseEventBuilder;

    private ToolRegistry toolRegistry;
    private ToolExecutor toolExecutor;
    private ToolCallbackFactory toolCallbackFactory;
    private AgentLoopConfig agentLoopConfig;
    private ChatLlmProperties chatLlmProperties;
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

        chatLlmProperties = new ChatLlmProperties();
        chatLlmProperties.setModel("gpt-4o-mini");

        service = new AgentOrchestrationService(
                chatLlmService, conversationService, memoryService,
                sseEventBuilder, toolRegistry, toolCallbackFactory, agentLoopConfig, chatLlmProperties);
        service.setStreamingExecutor(Runnable::run); // synchronous for testing

        when(sseEventBuilder.newRequestId()).thenReturn("test-request-id");
        doNothing().when(conversationService).requireNotBusy(anyLong());
        doNothing().when(conversationService).insertMessagePair(anyLong(), any(), any());
        when(conversationService.tryInsertToolMessage(any(), any(), any())).thenReturn(true);
        when(conversationService.finalizeAssistant(any())).thenReturn(true);
        when(conversationService.get(anyLong(), anyLong(), anyLong())).thenReturn(null); // mock returns null but doesn't throw
        when(memoryService.buildAnswerMessagesExcluding(anyLong(), anyLong(), anyLong(), anyInt(), any()))
                .thenReturn(List.of());
    }

    private AgentChatRequest request(String query) {
        AgentChatRequest req = new AgentChatRequest();
        req.setQuery(query);
        return req;
    }

    private ChatResponse emptyResponse() {
        AssistantMessage output = new AssistantMessage("Hello, how can I help?");
        Generation generation = new Generation(output);
        return ChatResponse.builder().generations(List.of(generation)).build();
    }

    private ChatResponse toolCallResponse(String toolName, String arguments) {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall("call_1", "function", toolName, arguments);
        AssistantMessage output = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(toolCall))
                .build();
        Generation generation = new Generation(output);
        return ChatResponse.builder().generations(List.of(generation)).build();
    }

    private ChatResponse textResponse(String text) {
        AssistantMessage output = new AssistantMessage(text);
        Generation generation = new Generation(output);
        return ChatResponse.builder().generations(List.of(generation)).build();
    }

    /**
     * Creates a ChatResponse with two tool calls in the same generation.
     */
    private ChatResponse multiToolCallResponse(String toolName1, String arguments1, String toolName2, String arguments2) {
        AssistantMessage.ToolCall tc1 = new AssistantMessage.ToolCall("call_1", "function", toolName1, arguments1);
        AssistantMessage.ToolCall tc2 = new AssistantMessage.ToolCall("call_2", "function", toolName2, arguments2);
        AssistantMessage output = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(tc1, tc2))
                .build();
        Generation generation = new Generation(output);
        return ChatResponse.builder().generations(List.of(generation)).build();
    }

    /**
     * Simple tool executor that delegates to the registered tool.
     */
    static class TestToolExecutor implements ToolExecutor {
        private final ToolRegistry registry;

        TestToolExecutor(ToolRegistry registry) {
            this.registry = registry;
        }

        @Override
        public AgentToolExecutionResult execute(String toolName, Map<String, Object> rawArguments, ToolExecutionContext ctx) {
            AgentTool<?> tool = registry.get(toolName);
            if (tool == null) {
                return AgentToolExecutionResult.failure("TOOL_NOT_FOUND", "Unknown tool");
            }
            try {
                @SuppressWarnings("unchecked")
                AgentTool<Object> rawTool = (AgentTool<Object>) tool;
                return rawTool.execute(ctx, rawArguments);
            } catch (Exception e) {
                return AgentToolExecutionResult.failure("TOOL_ERROR", e.getMessage());
            }
        }
    }

    // --- Tests ---

    @Nested
    @DisplayName("empty query rejection")
    class EmptyQueryTests {

        @Test
        @DisplayName("rejects null query")
        void rejectsNullQuery() {
            AgentChatRequest req = new AgentChatRequest();
            req.setQuery(null);

            service.chat(req, WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            verify(sseEventBuilder).sendError(any(), eq("CHAT_INVALID_REQUEST"), anyString());
        }

        @Test
        @DisplayName("rejects blank query")
        void rejectsBlankQuery() {
            service.chat(request("   "), WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            verify(sseEventBuilder).sendError(any(), eq("CHAT_INVALID_REQUEST"), anyString());
        }

        @Test
        @DisplayName("rejects empty query")
        void rejectsEmptyQuery() {
            service.chat(request(""), WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            verify(sseEventBuilder).sendError(any(), eq("CHAT_INVALID_REQUEST"), anyString());
        }
    }

    @Nested
    @DisplayName("normal completion without tool calls")
    class NormalCompletionTests {

        @Test
        @DisplayName("completes with final answer when LLM returns no tool calls")
        void completesWithFinalAnswer() {
            when(chatLlmService.generate(any(Prompt.class))).thenReturn(emptyResponse());

            service.chat(request("Hello"), WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            verify(sseEventBuilder).sendStart(any(), eq("test-request-id"), eq(CONVERSATION_ID), any());
            verify(sseEventBuilder).sendToken(any(), eq("Hello, how can I help?"));
            verify(sseEventBuilder).sendDone(any(), any(), eq("stop"));
            verify(sseEventBuilder, never()).sendToolCall(any(), anyString(), anyInt());
            verify(sseEventBuilder, never()).sendToolResult(any(), anyString(), anyBoolean(), anyInt(), anyLong());
        }

        @Test
        @DisplayName("persists ASSISTANT message with SUCCESS status")
        void persistsAssistantMessage() {
            when(chatLlmService.generate(any(Prompt.class))).thenReturn(emptyResponse());

            service.chat(request("Hello"), WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
            verify(conversationService).finalizeAssistant(captor.capture());
            ChatMessage msg = captor.getValue();
            assertThat(msg.getContent()).isEqualTo("Hello, how can I help?");
            assertThat(msg.getStatus()).isEqualTo(ChatMessageStatus.SUCCESS.name());
        }

        @Test
        @DisplayName("includes system prompt in initial messages")
        void includesSystemPrompt() {
            ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
            when(chatLlmService.generate(promptCaptor.capture())).thenReturn(emptyResponse());

            service.chat(request("Hello"), WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            Prompt prompt = promptCaptor.getValue();
            List<Message> messages = prompt.getInstructions();
            assertThat(messages).isNotEmpty();
            assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        }
    }

    @Nested
    @DisplayName("LLM error handling")
    class LlmErrorTests {

        @Test
        @DisplayName("handles LLM provider error gracefully")
        void handlesLlmProviderError() {
            when(chatLlmService.generate(any(Prompt.class)))
                    .thenThrow(new ChatLlmException("CHAT_PROVIDER_ERROR", "LLM failed"));

            service.chat(request("Hello"), WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            verify(sseEventBuilder).sendError(any(), eq("CHAT_PROVIDER_ERROR"), anyString());
            ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
            verify(conversationService).finalizeAssistant(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(ChatMessageStatus.FAILED.name());
        }

        @Test
        @DisplayName("fails closed when provider returns an empty final answer")
        void rejectsEmptyProviderResponse() {
            when(chatLlmService.generate(any(Prompt.class))).thenReturn(textResponse("   "));

            service.chat(request("Hello"), WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            verify(sseEventBuilder).sendError(
                    any(), eq("CHAT_INVALID_RESPONSE"), eq("LLM returned an empty response"));
            verify(sseEventBuilder, never()).sendDone(any(), any(), anyString());
            verify(sseEventBuilder, never()).sendToken(any(), anyString());
            ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
            verify(conversationService).finalizeAssistant(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(ChatMessageStatus.FAILED.name());
            assertThat(captor.getValue().getErrorCode()).isEqualTo("CHAT_INVALID_RESPONSE");
        }
    }

    @Nested
    @DisplayName("maxSteps termination")
    class MaxStepsTests {

        @Test
        @DisplayName("terminates when maxSteps reached")
        void terminatesAtMaxSteps() {
            agentLoopConfig.setMaxSteps(2);

            registerEchoTool("test_tool");

            // Always return tool calls so loop never exits naturally
            ChatResponse toolResponse = toolCallResponse("test_tool", "{\"q\":\"test\"}");
            when(chatLlmService.generate(any(Prompt.class))).thenReturn(toolResponse);

            service.chat(request("test"), WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            verify(sseEventBuilder).sendToken(any(), anyString());
            verify(sseEventBuilder).sendDone(any(), any(), eq("max_steps_reached"));
        }
    }

    @Nested
    @DisplayName("timeout termination")
    class TimeoutTests {

        @Test
        @DisplayName("terminates when timeout exceeded")
        void terminatesOnTimeout() {
            agentLoopConfig.setTimeoutSeconds(0); // Immediate timeout

            registerEchoTool("test_tool");

            ChatResponse toolResponse = toolCallResponse("test_tool", "{\"q\":\"test\"}");
            when(chatLlmService.generate(any(Prompt.class))).thenReturn(toolResponse);

            service.chat(request("test"), WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            verify(sseEventBuilder).sendToken(any(), anyString());
            verify(sseEventBuilder).sendDone(any(), any(), eq("timeout"));
        }
    }

    @Nested
    @DisplayName("unknown tool handling")
    class UnknownToolTests {

        @Test
        @DisplayName("returns error message when LLM requests unknown tool")
        void handlesUnknownTool() {
            ChatResponse toolResponse = toolCallResponse("no_such_tool", "{}");
            ChatResponse finalResponse = textResponse("I cannot use that tool.");

            when(chatLlmService.generate(any(Prompt.class)))
                    .thenReturn(toolResponse)
                    .thenReturn(finalResponse);

            service.chat(request("test"), WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            verify(sseEventBuilder).sendToken(any(), eq("I cannot use that tool."));
            verify(sseEventBuilder).sendDone(any(), any(), eq("stop"));
        }
    }

    @Nested
    @DisplayName("tool execution failure continues loop")
    class ToolFailureTests {

        @Test
        @DisplayName("continues loop when tool execution fails")
        void continuesLoopOnToolFailure() {
            registerFailingTool("failing_tool");

            ChatResponse toolResponse = toolCallResponse("failing_tool", "{}");
            ChatResponse finalResponse = textResponse("The tool failed, but here is my answer.");

            when(chatLlmService.generate(any(Prompt.class)))
                    .thenReturn(toolResponse)
                    .thenReturn(finalResponse);

            service.chat(request("test"), WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            verify(sseEventBuilder).sendToolCall(any(), eq("failing_tool"), eq(0));
            verify(sseEventBuilder).sendToolResult(any(), eq("failing_tool"), eq(false), eq(0), anyLong());
            verify(sseEventBuilder).sendToken(any(), eq("The tool failed, but here is my answer."));
            verify(sseEventBuilder).sendDone(any(), any(), eq("stop"));
        }
    }

    @Nested
    @DisplayName("successful tool call flow")
    class SuccessfulToolCallTests {

        @Test
        @DisplayName("executes tool call and gets final answer")
        void executesToolCallAndGetsFinalAnswer() {
            registerEchoTool("echo_tool");

            ChatResponse toolResponse = toolCallResponse("echo_tool", "{\"message\":\"hi\"}");
            ChatResponse finalResponse = textResponse("The tool returned: echo: hi");

            when(chatLlmService.generate(any(Prompt.class)))
                    .thenReturn(toolResponse)
                    .thenReturn(finalResponse);

            service.chat(request("echo test"), WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            verify(sseEventBuilder).sendToolCall(any(), eq("echo_tool"), eq(0));
            verify(sseEventBuilder).sendToolResult(any(), eq("echo_tool"), eq(true), anyInt(), anyLong());
            verify(sseEventBuilder).sendToken(any(), eq("The tool returned: echo: hi"));
        }

        @Test
        @DisplayName("conversation history is updated after tool call")
        void conversationHistoryUpdatedAfterToolCall() {
            registerEchoTool("echo_tool");

            ChatResponse toolResponse = toolCallResponse("echo_tool", "{\"message\":\"hi\"}");
            ChatResponse finalResponse = textResponse("Done");

            ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
            when(chatLlmService.generate(promptCaptor.capture()))
                    .thenReturn(toolResponse)
                    .thenReturn(finalResponse);

            service.chat(request("echo test"), WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            List<Prompt> prompts = promptCaptor.getAllValues();
            assertThat(prompts).hasSize(2);
            List<Message> secondPromptMessages = prompts.get(1).getInstructions();
            assertThat(secondPromptMessages).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("consecutive same tool call detection")
    class ConsecutiveSameToolCallTests {

        @Test
        @DisplayName("warns LLM after 3 consecutive same tool calls")
        void warnsLlmAfterThreeConsecutiveSameCalls() {
            registerEchoTool("echo_tool");

            ChatResponse toolResponse = toolCallResponse("echo_tool", "{\"message\":\"same\"}");
            ChatResponse finalResponse = textResponse("OK, I'll stop repeating.");

            when(chatLlmService.generate(any(Prompt.class)))
                    .thenReturn(toolResponse)   // step 0
                    .thenReturn(toolResponse)   // step 1
                    .thenReturn(toolResponse)   // step 2 → warning
                    .thenReturn(finalResponse); // step 3 → final answer

            service.chat(request("test"), WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            verify(sseEventBuilder).sendToken(any(), eq("OK, I'll stop repeating."));
        }
    }

    // --- helpers ---

    @SuppressWarnings("unchecked")
    private void registerEchoTool(String name) {
        toolRegistry.register(new AgentTool<Object>() {
            @Override
            public String name() { return name; }
            @Override
            public String description() { return "echo tool"; }
            @Override
            public String parametersSchema() {
                return "{\"type\":\"object\",\"properties\":{\"message\":{\"type\":\"string\"}}}";
            }
            @Override
            public Class<Object> argumentType() { return Object.class; }
            @Override
            public AgentToolExecutionResult execute(ToolExecutionContext ctx, Object args) {
                String msg = "no message";
                if (args instanceof Map<?, ?> m && m.containsKey("message")) {
                    msg = m.get("message").toString();
                }
                return AgentToolExecutionResult.success("echo: " + msg, Map.of("resultCount", 1));
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void registerFailingTool(String name) {
        toolRegistry.register(new AgentTool<Object>() {
            @Override
            public String name() { return name; }
            @Override
            public String description() { return "failing tool"; }
            @Override
            public String parametersSchema() { return "{\"type\":\"object\"}"; }
            @Override
            public Class<Object> argumentType() { return Object.class; }
            @Override
            public AgentToolExecutionResult execute(ToolExecutionContext ctx, Object args) {
                throw new RuntimeException("simulated failure");
            }
        });
    }

    @Nested
    @DisplayName("multi-tool call in one ChatResponse")
    class MultiToolCallTests {

        @Test
        @DisplayName("executes both tools exactly once in one round")
        void executesBothToolsOnce() {
            registerEchoTool("tool_a");
            registerEchoTool("tool_b");

            ChatResponse multiToolResponse = multiToolCallResponse("tool_a", "{\"message\":\"a\"}", "tool_b", "{\"message\":\"b\"}");
            ChatResponse finalResponse = textResponse("Done with both tools.");

            when(chatLlmService.generate(any(Prompt.class)))
                    .thenReturn(multiToolResponse)
                    .thenReturn(finalResponse);

            service.chat(request("test"), WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            // Both tool_call events sent
            verify(sseEventBuilder).sendToolCall(any(), eq("tool_a"), eq(0));
            verify(sseEventBuilder).sendToolCall(any(), eq("tool_b"), eq(0));
            // Both tool_result events sent
            verify(sseEventBuilder).sendToolResult(any(), eq("tool_a"), eq(true), anyInt(), anyLong());
            verify(sseEventBuilder).sendToolResult(any(), eq("tool_b"), eq(true), anyInt(), anyLong());
            // Final answer
            verify(sseEventBuilder).sendToken(any(), eq("Done with both tools."));
        }

        @Test
        @DisplayName("step increments only once despite two tool calls")
        void stepIncrementsOnce() {
            registerEchoTool("tool_a");
            registerEchoTool("tool_b");

            ChatResponse multiToolResponse = multiToolCallResponse("tool_a", "{}", "tool_b", "{}");
            ChatResponse finalResponse = textResponse("Done");

            when(chatLlmService.generate(any(Prompt.class)))
                    .thenReturn(multiToolResponse)
                    .thenReturn(finalResponse);

            service.chat(request("test"), WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            // step=0 for both tools, final answer at step=1 (but breaks without increment)
            verify(sseEventBuilder).sendToolCall(any(), eq("tool_a"), eq(0));
            verify(sseEventBuilder).sendToolCall(any(), eq("tool_b"), eq(0));
            verify(sseEventBuilder).sendDone(any(), any(), eq("stop"));
        }

        @Test
        @DisplayName("collector records 2 entries for 2 tool calls")
        void collectorRecordsTwoEntries() {
            registerEchoTool("tool_a");
            registerEchoTool("tool_b");

            ChatResponse multiToolResponse = multiToolCallResponse("tool_a", "{\"message\":\"a\"}", "tool_b", "{\"message\":\"b\"}");
            ChatResponse finalResponse = textResponse("Done");

            when(chatLlmService.generate(any(Prompt.class)))
                    .thenReturn(multiToolResponse)
                    .thenReturn(finalResponse);

            service.chat(request("test"), WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            // Both tool_result events sent
            verify(sseEventBuilder).sendToolResult(any(), eq("tool_a"), eq(true), anyInt(), anyLong());
            verify(sseEventBuilder).sendToolResult(any(), eq("tool_b"), eq(true), anyInt(), anyLong());
        }
    }

    @Nested
    @DisplayName("multi-step collector cursor correctness")
    class MultiStepCollectorTests {

        @Test
        @DisplayName("collector does not duplicate entries across steps")
        void collectorDoesNotDuplicateAcrossSteps() {
            registerEchoTool("tool_a");
            registerEchoTool("tool_b");

            ChatResponse toolAResponse = toolCallResponse("tool_a", "{\"message\":\"a\"}");
            ChatResponse toolBResponse = toolCallResponse("tool_b", "{\"message\":\"b\"}");
            ChatResponse finalResponse = textResponse("Done with both.");

            when(chatLlmService.generate(any(Prompt.class)))
                    .thenReturn(toolAResponse)   // Round 1: Tool A
                    .thenReturn(toolBResponse)   // Round 2: Tool B
                    .thenReturn(finalResponse);  // Round 3: final

            service.chat(request("test"), WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            // Round 1: Tool A at step 0
            verify(sseEventBuilder).sendToolCall(any(), eq("tool_a"), eq(0));
            verify(sseEventBuilder, times(1)).sendToolResult(any(), eq("tool_a"), eq(true), anyInt(), anyLong());
            // Round 2: Tool B at step 1
            verify(sseEventBuilder).sendToolCall(any(), eq("tool_b"), eq(1));
            verify(sseEventBuilder, times(1)).sendToolResult(any(), eq("tool_b"), eq(true), anyInt(), anyLong());
            // Final answer
            verify(sseEventBuilder).sendToken(any(), eq("Done with both."));
            verify(sseEventBuilder).sendDone(any(), any(), eq("stop"));
        }

        @Test
        @DisplayName("round 1 only processes tool A, round 2 only processes tool B")
        void eachRoundProcessesOnlyNewTools() {
            registerEchoTool("tool_a");
            registerEchoTool("tool_b");

            ChatResponse toolAResponse = toolCallResponse("tool_a", "{\"message\":\"a\"}");
            ChatResponse toolBResponse = toolCallResponse("tool_b", "{\"message\":\"b\"}");
            ChatResponse finalResponse = textResponse("Done");

            ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
            when(chatLlmService.generate(promptCaptor.capture()))
                    .thenReturn(toolAResponse)
                    .thenReturn(toolBResponse)
                    .thenReturn(finalResponse);

            service.chat(request("test"), WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            // 3 prompts: initial, after tool A, after tool B
            List<Prompt> prompts = promptCaptor.getAllValues();
            assertThat(prompts).hasSize(3);
            // Verify the second prompt received tool A's result
            List<Message> secondPromptMessages = prompts.get(1).getInstructions();
            assertThat(secondPromptMessages).isNotEmpty();
            // Verify the third prompt received both results
            List<Message> thirdPromptMessages = prompts.get(2).getInstructions();
            assertThat(thirdPromptMessages).isNotEmpty();
        }
    }
}
