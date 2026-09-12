package com.intellidesk.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.intellidesk.agent.tool.AgentToolExecutionResult;
import com.intellidesk.agent.tool.AgentToolTraceCollector;
import com.intellidesk.agent.tool.ToolCallbackFactory;
import com.intellidesk.agent.tool.ToolExecutionContext;
import com.intellidesk.agent.tool.ToolRegistry;
import com.intellidesk.chat.ChatLlmException;
import com.intellidesk.chat.ChatLlmProperties;
import com.intellidesk.chat.ChatLlmService;
import com.intellidesk.chat.conversation.ConversationService;
import com.intellidesk.chat.dto.AgentChatRequest;
import com.intellidesk.chat.memory.ConversationMemoryService;
import com.intellidesk.chat.message.ChatMessage;
import com.intellidesk.chat.message.ChatMessageStatus;
import com.intellidesk.chat.message.ChatRole;
import com.intellidesk.chat.sse.SseEventBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Agent orchestration service.
 * <p>
 * Manages the Agent Loop: LLM → tool_call → execute → result → continue → final answer.
 * <p>
 * Phase 4 ChatOrchestrationService is NOT modified.
 * Agent mode does NOT do automatic RAG (knowledge_search is an explicit Tool).
 * <p>
 * ToolCallingManager.executeToolCalls(prompt, chatResponse) is the ONLY tool execution entry point.
 * AgentOrchestrationService does NOT manually call ToolExecutor a second time.
 * <p>
 * Wave 4 additions: TOOL message persistence, sequence relocation, SSE lifecycle,
 * cancellation/race handling, concurrency guard.
 */
@Service
public class AgentOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrationService.class);

    private final ChatLlmService chatLlmService;
    private final ConversationService conversationService;
    private final ConversationMemoryService memoryService;
    private final SseEventBuilder sseEventBuilder;
    private final ToolRegistry toolRegistry;
    private final ToolCallbackFactory toolCallbackFactory;
    private final AgentLoopConfig agentLoopConfig;
    private final ChatLlmProperties chatLlmProperties;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private ExecutorService streamingExecutor = Executors.newCachedThreadPool();

    /**
     * Replace the streaming executor. Intended for testing to make the agent loop synchronous.
     * Accepts {@link Executor} (functional interface) and wraps it as an ExecutorService.
     * Use {@code Runnable::run} for synchronous testing.
     */
    void setStreamingExecutor(Executor executor) {
        this.streamingExecutor = new ExecutorService() {
            private volatile boolean shutdown = false;
            @Override public void execute(Runnable command) { executor.execute(command); }
            @Override public void shutdown() { shutdown = true; }
            @Override public java.util.List<Runnable> shutdownNow() { shutdown = true; return java.util.List.of(); }
            @Override public boolean isShutdown() { return shutdown; }
            @Override public boolean isTerminated() { return shutdown; }
            @Override public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) { return shutdown; }
            @Override public <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> task) {
                // Synchronous: execute immediately and return a trivial Future
                var future = new java.util.concurrent.FutureTask<>(task);
                executor.execute(future);
                return future;
            }
            @Override public <T> java.util.concurrent.Future<T> submit(Runnable task, T result) {
                var future = new java.util.concurrent.FutureTask<>(task, result);
                executor.execute(future);
                return future;
            }
            @Override public java.util.concurrent.Future<?> submit(Runnable task) {
                var future = new java.util.concurrent.FutureTask<>(task, null);
                executor.execute(future);
                return future;
            }
            @Override public <T> java.util.List<java.util.concurrent.Future<T>> invokeAll(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks) { throw new UnsupportedOperationException(); }
            @Override public <T> java.util.List<java.util.concurrent.Future<T>> invokeAll(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks, long timeout, java.util.concurrent.TimeUnit unit) { throw new UnsupportedOperationException(); }
            @Override public <T> T invokeAny(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks) { throw new UnsupportedOperationException(); }
            @Override public <T> T invokeAny(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks, long timeout, java.util.concurrent.TimeUnit unit) { throw new UnsupportedOperationException(); }
        };
    }

    public AgentOrchestrationService(ChatLlmService chatLlmService,
                                     ConversationService conversationService,
                                     ConversationMemoryService memoryService,
                                     SseEventBuilder sseEventBuilder,
                                     ToolRegistry toolRegistry,
                                     ToolCallbackFactory toolCallbackFactory,
                                     AgentLoopConfig agentLoopConfig,
                                     ChatLlmProperties chatLlmProperties) {
        this.chatLlmService = chatLlmService;
        this.conversationService = conversationService;
        this.memoryService = memoryService;
        this.sseEventBuilder = sseEventBuilder;
        this.toolRegistry = toolRegistry;
        this.toolCallbackFactory = toolCallbackFactory;
        this.agentLoopConfig = agentLoopConfig;
        this.chatLlmProperties = chatLlmProperties;
    }

    /**
     * Execute Agent chat with tool calling.
     */
    public SseEmitter chat(AgentChatRequest request, Long workspaceId, Long conversationId, Long userId) {
        String requestId = sseEventBuilder.newRequestId();
        SseEmitter emitter = new SseEmitter(agentLoopConfig.getTimeoutSeconds() * 1000L + 30_000L);

        // Validate query
        String query = request.getQuery();
        if (query == null || query.isBlank()) {
            sseEventBuilder.sendError(emitter, "CHAT_INVALID_REQUEST", "Query must not be blank");
            emitter.complete();
            return emitter;
        }

        // Validate conversation ownership (IDOR protection)
        conversationService.get(conversationId, workspaceId, userId);

        // TX1: persist USER + ASSISTANT(GENERATING) — single transaction with authoritative busy check
        // The busy check is now inside insertMessagePair (after SELECT FOR UPDATE) to prevent TOCTOU races
        ChatMessage userMessage = new ChatMessage();
        userMessage.setConversationId(conversationId);
        userMessage.setRole(ChatRole.USER.name());
        userMessage.setContent(query);
        userMessage.setStatus(ChatMessageStatus.SUCCESS.name());
        userMessage.setCreatedAt(LocalDateTime.now());

        ChatMessage assistantMessage = new ChatMessage();
        assistantMessage.setConversationId(conversationId);
        assistantMessage.setRole(ChatRole.ASSISTANT.name());
        assistantMessage.setContent("");
        assistantMessage.setStatus(ChatMessageStatus.GENERATING.name());
        assistantMessage.setModel(chatLlmProperties.getModel());
        assistantMessage.setCreatedAt(LocalDateTime.now());

        conversationService.insertMessagePair(conversationId, userMessage, assistantMessage);

        sseEventBuilder.sendStart(emitter, requestId, conversationId, assistantMessage.getId());

        // Per-request cancellation control
        AgentExecutionControl control = new AgentExecutionControl();

        // Register SSE lifecycle callbacks BEFORE executing the agent loop.
        registerEmitterCallbacks(emitter, assistantMessage, control);

        // Execute agent loop in separate thread to avoid blocking Tomcat
        // Use submit() to obtain a Future for cancellation
        Future<?> future = streamingExecutor.submit(() -> executeAgentLoop(
                request, workspaceId, conversationId, userId,
                requestId, emitter, userMessage, assistantMessage, control));
        control.bindFuture(future);

        return emitter;
    }

    /**
     * Agent loop execution — runs in streaming executor thread.
     * All provider I/O (LLM, Tool execution) is outside DB transactions.
     * Only short TX for TOOL persistence and TX2 finalization.
     * <p>
     * Cooperative cancellation: checks {@link AgentExecutionControl#isCancelled()} at key points
     * and exits immediately if cancelled. This prevents the agent thread from continuing LLM calls,
     * Tool execution, and TOOL persistence after the client disconnects.
     */
    private void executeAgentLoop(AgentChatRequest request, Long workspaceId, Long conversationId,
                                   Long userId, String requestId, SseEmitter emitter,
                                   ChatMessage userMessage, ChatMessage assistantMessage,
                                   AgentExecutionControl control) {

        // Checkpoint: cancelled before we even start
        if (control.isCancelled()) {
            return;
        }

        // Per-request context (NOT bound to singleton ToolCallback)
        ToolExecutionContext ctx = new ToolExecutionContext(userId, workspaceId, conversationId, requestId);
        AgentToolTraceCollector collector = new AgentToolTraceCollector();

        long startTime = System.currentTimeMillis();
        String finalAnswer = null;
        String finishReason = "stop";

        try {
            // Load conversation history (USER + ASSISTANT only, no TOOL)
            List<Message> history = memoryService.buildAnswerMessagesExcluding(
                    workspaceId, conversationId, userId,
                    agentLoopConfig.getMaxSteps() * 2 + 4,
                    userMessage.getId());

            // Build initial prompt messages
            List<Message> currentMessages = new ArrayList<>();
            currentMessages.add(new SystemMessage(agentLoopConfig.getSystemPrompt()));
            currentMessages.addAll(history);
            currentMessages.add(new UserMessage(request.getQuery()));

            // Build ToolCallbacks (stateless — no ctx passed)
            List<ToolCallback> toolCallbacks = toolCallbackFactory.buildCallbacks(toolRegistry);

            // Build ToolCallingManager with tool callbacks for resolution
            ToolCallingManager toolCallingManager = ToolCallingManager.builder()
                    .toolCallbackResolver(new StaticToolCallbackResolver(toolCallbacks))
                    .build();

            // Agent Loop
            int step = 0;
            String lastToolCallFingerprint = null;
            int consecutiveSameToolCount = 0;

            while (step < agentLoopConfig.getMaxSteps()) {
                // Checkpoint: cancelled before each step
                if (control.isCancelled()) {
                    log.info("Agent execution cancelled before step {}", step);
                    return;
                }

                long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed >= agentLoopConfig.getTimeoutSeconds() * 1000L) {
                    finishReason = "timeout";
                    log.warn("Agent loop timeout after {}ms", elapsed);
                    break;
                }

                // Build ToolCallingChatOptions per request
                var options = OpenAiChatOptions.builder()
                        .toolCallbacks(toolCallbacks)
                        .internalToolExecutionEnabled(false)
                        .toolContext(Map.of("ctx", ctx, "collector", collector))
                        .build();

                Prompt prompt = new Prompt(currentMessages, options);

                // Checkpoint: cancelled before LLM call
                if (control.isCancelled()) {
                    log.info("Agent execution cancelled before LLM generate at step {}", step);
                    return;
                }

                ChatResponse chatResponse = chatLlmService.generate(prompt);

                // Checkpoint: cancelled after LLM returns (even if provider ignored interrupt)
                if (control.isCancelled()) {
                    log.info("Agent execution cancelled after LLM generate at step {}", step);
                    return;
                }

                // Check for tool calls
                if (hasToolCalls(chatResponse)) {

                    // Extract tool call info from LLM response (for SSE + fingerprint)
                    List<Generation> toolCallGenerations = chatResponse.getResults().stream()
                            .filter(g -> g.getOutput() != null
                                    && g.getOutput().getToolCalls() != null
                                    && !g.getOutput().getToolCalls().isEmpty())
                            .toList();

                    // Validate tool names and send SSE tool_call events
                    boolean allToolsValid = true;
                    for (Generation gen : toolCallGenerations) {
                        for (var toolCall : gen.getOutput().getToolCalls()) {
                            String toolName = toolCall.name();
                            if (toolRegistry.get(toolName) == null) {
                                log.warn("Unknown tool requested by LLM: {}", toolName);
                                allToolsValid = false;
                            }
                            // Send SSE tool_call event BEFORE execution
                            sendToolCallSse(emitter, toolName, step);
                        }
                    }

                    if (!allToolsValid) {
                        currentMessages.add(new AssistantMessage(
                                "Error: Unknown tool requested. Available tools: "
                                        + String.join(", ", toolRegistry.registeredNames())));
                        step++;
                        continue;
                    }

                    // Duplicate tool call detection
                    String toolFingerprint = buildToolCallFingerprint(toolCallGenerations);
                    if (toolFingerprint.equals(lastToolCallFingerprint)) {
                        consecutiveSameToolCount++;
                        if (consecutiveSameToolCount >= 3) {
                            log.warn("Same tool call repeated {} times, warning LLM", consecutiveSameToolCount);
                            currentMessages.add(new AssistantMessage(
                                    "Warning: You have called the same tool with the same arguments multiple times. "
                                            + "Please try a different approach or provide your answer based on available results."));
                            lastToolCallFingerprint = null;
                            consecutiveSameToolCount = 0;
                            step++;
                            continue;
                        }
                    } else {
                        lastToolCallFingerprint = toolFingerprint;
                        consecutiveSameToolCount = 1;
                    }

                    // Checkpoint: cancelled before tool execution
                    if (control.isCancelled()) {
                        log.info("Agent execution cancelled before tool execution at step {}", step);
                        return;
                    }

                    // Execute tool calls — ONLY entry point (provider I/O, outside DB TX)
                    int collectorSizeBefore = collector.size();
                    ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, chatResponse);

                    // Checkpoint: cancelled after tool execution (even if Tool was already running)
                    if (control.isCancelled()) {
                        log.info("Agent execution cancelled after tool execution at step {}", step);
                        return;
                    }

                    // Read results from collector (populated by IntelliDeskToolCallback)
                    List<AgentToolTraceCollector.ToolTraceEntry> newEntries = new ArrayList<>();
                    List<AgentToolTraceCollector.ToolTraceEntry> allEntries = collector.getEntries();
                    for (int i = collectorSizeBefore; i < allEntries.size(); i++) {
                        newEntries.add(allEntries.get(i));
                    }

                    // Extract tool call IDs from the LLM response for persistence
                    List<String> toolCallIds = extractToolCallIds(toolCallGenerations);

                    // Persist TOOL messages and send SSE tool_result events
                    for (int i = 0; i < newEntries.size(); i++) {
                        AgentToolTraceCollector.ToolTraceEntry entry = newEntries.get(i);
                        String toolCallId = (i < toolCallIds.size()) ? toolCallIds.get(i) : null;

                        // Checkpoint: cancelled before TOOL persistence
                        if (control.isCancelled()) {
                            log.info("Agent execution cancelled before TOOL persistence at step {}", step);
                            return;
                        }

                        // Persist TOOL message in short TX, gated on assistant GENERATING status
                        boolean inserted = tryInsertToolMessage(conversationId, assistantMessage.getId(),
                                entry, toolCallId);

                        if (!inserted) {
                            // Assistant already terminal (cancelled/failed) — stop immediately
                            log.info("TOOL insert rejected — assistant already terminal for conversation {}", conversationId);
                            return;
                        }

                        // Checkpoint: cancelled before SSE tool_result (after persistence)
                        if (control.isCancelled()) {
                            log.info("Agent execution cancelled after TOOL persistence at step {}", step);
                            return;
                        }

                        // Send SSE tool_result event AFTER persistence
                        AgentToolExecutionResult result = entry.result();
                        sendToolResultSse(emitter, entry.toolName(),
                                result != null && result.success(),
                                result != null && result.metadata() != null
                                        ? extractResultCount(result.metadata()) : 0,
                                entry.durationMs());
                    }

                    // Update conversation history for next iteration
                    currentMessages = new ArrayList<>(toolExecutionResult.conversationHistory());
                    step++;
                } else {
                    // Final answer — no tool calls
                    finalAnswer = extractContent(chatResponse);
                    finishReason = "stop";
                    break;
                }
            }

            // Checkpoint: cancelled before final answer
            if (control.isCancelled()) {
                log.info("Agent execution cancelled before final answer");
                return;
            }

            if (finalAnswer == null) {
                if (finishReason.equals("timeout")) {
                    finalAnswer = "The request timed out.";
                } else if (step >= agentLoopConfig.getMaxSteps()) {
                    finishReason = "max_steps_reached";
                    finalAnswer = "I was unable to complete the answer within the step limit.";
                } else {
                    finalAnswer = "I was unable to complete the answer.";
                }
            }

            if (finalAnswer.isBlank()) {
                boolean won = finalizeAssistantFailed(assistantMessage,
                        "CHAT_INVALID_RESPONSE", "LLM returned an empty response");
                if (won) {
                    sseEventBuilder.sendError(emitter, "CHAT_INVALID_RESPONSE",
                            "LLM returned an empty response");
                }
                return;
            }

            // TX2: Finalize ASSISTANT message with sequence relocation if TOOL messages exist
            // DB-authoritative: the DB determines whether relocation is needed (no hasTools flag)
            assistantMessage.setContent(finalAnswer);
            assistantMessage.setStatus(ChatMessageStatus.SUCCESS.name());
            boolean casOk = conversationService.finalizeAssistant(assistantMessage);

            if (casOk) {
                // Only send terminal SSE events if CAS succeeded (we won the race)
                sseEventBuilder.sendToken(emitter, finalAnswer);
                sseEventBuilder.sendDone(emitter, assistantMessage.getId(), finishReason);
            } else {
                // CAS failed — another path (disconnect/timeout) already finalized
                log.warn("TX2 CAS failed for assistant id={} — already finalized by another path (disconnect/timeout)", assistantMessage.getId());
            }

        } catch (AgentExecutionControl.AgentCancelledException e) {
            log.info("Agent execution cancelled via cooperative checkpoint: {}", e.getMessage());
            // Don't send error — cancellation already handled by callback
        } catch (ChatLlmException e) {
            // Check cancellation FIRST — if cancelled, the interrupt may have triggered this exception.
            // Do NOT finalize as FAILED when cancellation intent is already set.
            if (control.isCancelled()) {
                log.info("ChatLlmException caught but cancellation already requested — exiting worker");
                return;
            }
            log.error("LLM error in agent loop", e);
            boolean won = finalizeAssistantFailed(assistantMessage, "CHAT_PROVIDER_ERROR", "LLM service error");
            if (won) {
                sseEventBuilder.sendError(emitter, "CHAT_PROVIDER_ERROR", "LLM service error");
            }
        } catch (Exception e) {
            // Check cancellation FIRST — if cancelled, the interrupt may have triggered this exception.
            // Do NOT finalize as FAILED when cancellation intent is already set.
            if (control.isCancelled()) {
                log.info("Exception caught but cancellation already requested — exiting worker: {}", e.getMessage());
                return;
            }
            log.error("Unexpected error in agent loop", e);
            boolean won = finalizeAssistantFailed(assistantMessage, "INTERNAL_ERROR", "Internal error");
            if (won) {
                sseEventBuilder.sendError(emitter, "INTERNAL_ERROR", "Internal error");
            }
        } finally {
            safeComplete(emitter);
        }
    }

    /**
     * Register SSE lifecycle callbacks for disconnect/timeout handling.
     * <p>
     * When the client disconnects or the emitter times out, we follow a strict ordering:
     * 1. requestCancellation() — sets the cancelled flag (cooperative checkpoints will see this)
     * 2. finalizeAssistantCancelled() — persists CANCELLED state in DB (with conversation lock)
     * 3. interruptFuture() — best-effort interrupt of the background thread
     * <p>
     * This ordering ensures that any interrupt-induced exception in the worker thread
     * finds the cancellation flag already set, preventing the worker from finalizing as FAILED.
     * <p>
     * Only one path (success vs cancel vs timeout) wins via CAS.
     * onCompletion is intentionally left as a no-op — normal completion is handled by the success path.
     */
    private void registerEmitterCallbacks(SseEmitter emitter, ChatMessage assistantMessage,
                                           AgentExecutionControl control) {
        emitter.onCompletion(() -> {
            log.debug("SSE emitter completed for conversation {}", assistantMessage.getConversationId());
            // No-op: normal completion is handled by the success path.
            // Cancellation is handled by onTimeout/onError.
        });

        emitter.onTimeout(() -> {
            log.warn("SSE emitter timed out for conversation {}", assistantMessage.getConversationId());
            // Step 1: Set cancellation flag first
            control.requestCancellation();
            try {
                // Step 2: Persist CANCELLED state in DB (with conversation lock)
                finalizeAssistantCancelled(assistantMessage, "TIMEOUT", "Stream timed out");
            } catch (Exception ex) {
                log.error("Failed to save CANCELLED state on timeout", ex);
            }
            // Step 3: Attempt best-effort interrupt of the background thread
            control.interruptFuture();
            safeComplete(emitter);
        });

        emitter.onError(throwable -> {
            log.warn("SSE emitter error for conversation {}: {}", assistantMessage.getConversationId(),
                    throwable != null ? throwable.getMessage() : "unknown");
            // Step 1: Set cancellation flag first
            control.requestCancellation();
            try {
                // Step 2: Persist CANCELLED state in DB (with conversation lock)
                finalizeAssistantCancelled(assistantMessage, "CLIENT_DISCONNECT", "Client disconnected");
            } catch (Exception ex) {
                log.error("Failed to save CANCELLED state on disconnect", ex);
            }
            // Step 3: Attempt best-effort interrupt of the background thread
            control.interruptFuture();
            safeComplete(emitter);
        });
    }

    // ================================================================
    // Persistence helpers
    // ================================================================

    /**
     * Try to persist a single TOOL message in a short transaction,
     * gated on the assistant still being GENERATING.
     * <p>
     * Uses {@link ConversationService#tryInsertToolMessage} which locks the conversation
     * and verifies the assistant status before inserting. If the assistant is already
     * terminal (CANCELLED/FAILED/SUCCESS), the insert is rejected.
     *
     * @return true if the TOOL was persisted, false if the assistant is no longer GENERATING
     */
    private boolean tryInsertToolMessage(Long conversationId, Long assistantMessageId,
                                          AgentToolTraceCollector.ToolTraceEntry entry, String toolCallId) {
        try {
            ToolMessageEnvelope envelope = ToolMessageEnvelope.fromTraceEntry(entry, toolCallId);
            ChatMessage toolMessage = new ChatMessage();
            toolMessage.setConversationId(conversationId);
            toolMessage.setRole(ChatRole.TOOL.name());
            toolMessage.setContent(envelope.toJson());
            toolMessage.setStatus(ChatMessageStatus.SUCCESS.name());
            toolMessage.setCreatedAt(LocalDateTime.now());
            return conversationService.tryInsertToolMessage(conversationId, assistantMessageId, toolMessage);
        } catch (Exception e) {
            log.error("Failed to persist TOOL message for tool {}: {}", entry.toolName(), e.getMessage());
            // Persistence failure is fatal — rethrow to trigger FAILED flow
            throw new RuntimeException("Failed to persist TOOL message", e);
        }
    }

    /**
     * Finalize ASSISTANT to FAILED (CAS guard).
     * DB-authoritative relocation — no hasTools parameter needed.
     * @return true if CAS succeeded (we won the race), false otherwise
     */
    private boolean finalizeAssistantFailed(ChatMessage assistantMessage,
                                          String errorCode, String errorMessage) {
        try {
            assistantMessage.setStatus(ChatMessageStatus.FAILED.name());
            assistantMessage.setErrorCode(errorCode);
            assistantMessage.setErrorMessage(errorMessage);
            return conversationService.finalizeAssistant(assistantMessage);
        } catch (Exception e) {
            log.error("Failed to persist FAILED state for assistant message {}", assistantMessage.getId(), e);
            return false;
        }
    }

    /**
     * Finalize ASSISTANT to CANCELLED (CAS guard).
     * DB-authoritative relocation — no hasTools parameter needed.
     * @return true if CAS succeeded (we won the race), false otherwise
     */
    private boolean finalizeAssistantCancelled(ChatMessage assistantMessage,
                                             String errorCode, String errorMessage) {
        try {
            assistantMessage.setStatus(ChatMessageStatus.CANCELLED.name());
            assistantMessage.setErrorCode(errorCode);
            assistantMessage.setErrorMessage(errorMessage);
            return conversationService.finalizeAssistant(assistantMessage);
        } catch (Exception e) {
            log.error("Failed to persist CANCELLED state for assistant message {}", assistantMessage.getId(), e);
            return false;
        }
    }

    // ================================================================
    // Tool call helpers
    // ================================================================

    private boolean hasToolCalls(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResults() == null) {
            return false;
        }
        return chatResponse.getResults().stream()
                .anyMatch(result -> result.getOutput() != null
                        && result.getOutput().getToolCalls() != null
                        && !result.getOutput().getToolCalls().isEmpty());
    }

    private String extractContent(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResults() == null || chatResponse.getResults().isEmpty()) {
            return "";
        }
        var output = chatResponse.getResults().get(0).getOutput();
        return output != null && output.getText() != null ? output.getText() : "";
    }

    /**
     * Extract tool call IDs from the LLM response for persistence.
     * Uses Spring AI ToolCall.id() when available.
     */
    private List<String> extractToolCallIds(List<Generation> toolCallGenerations) {
        List<String> ids = new ArrayList<>();
        for (Generation gen : toolCallGenerations) {
            if (gen.getOutput() != null && gen.getOutput().getToolCalls() != null) {
                for (var tc : gen.getOutput().getToolCalls()) {
                    try {
                        String id = tc.id();
                        ids.add(id != null ? id : "unknown");
                    } catch (Exception e) {
                        ids.add("unknown");
                    }
                }
            }
        }
        return ids;
    }

    private String buildToolCallFingerprint(List<Generation> toolCallGenerations) {
        StringBuilder sb = new StringBuilder();
        for (Generation gen : toolCallGenerations) {
            if (gen.getOutput() != null && gen.getOutput().getToolCalls() != null) {
                for (var tc : gen.getOutput().getToolCalls()) {
                    sb.append(tc.name()).append(":").append(canonicalizeArguments(tc.arguments())).append(";");
                }
            }
        }
        return sb.toString();
    }

    private String canonicalizeArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return "";
        }
        try {
            Object parsed = objectMapper.readValue(arguments, Object.class);
            return objectMapper.writeValueAsString(parsed);
        } catch (JsonProcessingException e) {
            log.debug("Failed to canonicalize arguments JSON, using raw string: {}", arguments);
            return arguments;
        }
    }

    // ================================================================
    // SSE helpers
    // ================================================================

    private void sendToolCallSse(SseEmitter emitter, String toolName, int step) {
        try {
            sseEventBuilder.sendToolCall(emitter, toolName, step);
        } catch (Exception ignored) {
            // SSE send may fail if client disconnected
        }
    }

    private void sendToolResultSse(SseEmitter emitter, String toolName, boolean success, int resultCount, long durationMs) {
        try {
            sseEventBuilder.sendToolResult(emitter, toolName, success, resultCount, durationMs);
        } catch (Exception ignored) {
            // SSE send may fail if client disconnected
        }
    }

    private int extractResultCount(Map<String, Object> metadata) {
        Object count = metadata.get("resultCount");
        if (count instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }

    private void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("SSE emitter already completed: {}", e.getMessage());
        }
    }
}
