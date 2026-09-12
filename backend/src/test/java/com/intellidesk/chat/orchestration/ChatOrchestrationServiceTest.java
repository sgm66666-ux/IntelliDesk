package com.intellidesk.chat.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellidesk.chat.ChatLlmException;
import com.intellidesk.chat.ChatLlmProperties;
import com.intellidesk.chat.ChatLlmService;
import com.intellidesk.chat.citation.*;
import com.intellidesk.chat.context.ContextEntry;
import com.intellidesk.chat.context.ContextProperties;
import com.intellidesk.chat.context.RagContext;
import com.intellidesk.chat.context.RagContextBuilder;
import com.intellidesk.chat.conversation.ConversationService;
import com.intellidesk.chat.dto.ChatRequest;
import com.intellidesk.chat.memory.ConversationMemoryService;
import com.intellidesk.chat.memory.MemoryProperties;
import com.intellidesk.chat.message.ChatMessage;
import com.intellidesk.chat.message.ChatMessageStatus;
import com.intellidesk.chat.message.ChatRole;
import com.intellidesk.chat.rewrite.QueryRewriteService;
import com.intellidesk.chat.sse.SseEventBuilder;
import com.intellidesk.common.BusinessException;
import com.intellidesk.common.ErrorCode;
import com.intellidesk.retrieval.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatOrchestrationService")
class ChatOrchestrationServiceTest {

    @Mock private ConversationService conversationService;
    @Mock private QueryRewriteService queryRewriteService;
    @Mock private RetrievalService retrievalService;
    @Mock private RetrievalScopeResolver scopeResolver;
    @Mock private RagContextBuilder ragContextBuilder;
    @Mock private CitationAssembler citationAssembler;
    @Mock private CitationValidator citationValidator;
    @Mock private ConversationMemoryService memoryService;
    @Mock private ChatLlmService chatLlmService;
    @Mock private SseEventBuilder sseEventBuilder;

    private MemoryProperties memoryProperties;
    private ChatLlmProperties chatLlmProperties;
    private ContextProperties contextProperties;
    private ObjectMapper objectMapper;
    private ChatOrchestrationService service;

    private static final Long WORKSPACE_ID = 1L;
    private static final Long CONVERSATION_ID = 10L;
    private static final Long USER_ID = 100L;

    private final AtomicLong messageIdCounter = new AtomicLong(1000);

    @BeforeEach
    void setUp() {
        memoryProperties = new MemoryProperties();
        memoryProperties.setMaxMessages(10);

        chatLlmProperties = new ChatLlmProperties();
        chatLlmProperties.setModel("gpt-4o-mini");

        contextProperties = new ContextProperties();
        contextProperties.setTokenBudget(4096);
        contextProperties.setCandidateTopK(30);
        contextProperties.setTopK(8);

        objectMapper = new ObjectMapper();

        service = new ChatOrchestrationService(
                conversationService, queryRewriteService, retrievalService,
                scopeResolver, ragContextBuilder, citationAssembler,
                citationValidator, memoryService, memoryProperties,
                chatLlmService, chatLlmProperties, contextProperties,
                sseEventBuilder, objectMapper);
    }

    private ChatRequest createRequest(String query) {
        ChatRequest req = new ChatRequest();
        req.setQuery(query);
        req.setKnowledgeBaseIds(List.of(1L));
        req.setRewriteEnabled(true);
        return req;
    }

    private RetrievalScope mockScope() {
        return new RetrievalScope(WORKSPACE_ID, List.of(1L), null);
    }

    private ChatResponse mockChatResponse(String content) {
        AssistantMessage msg = new AssistantMessage(content);
        Generation gen = new Generation(msg, ChatGenerationMetadata.builder().finishReason("stop").build());
        return new ChatResponse(List.of(gen), null);
    }

    private RagContext mockRagContext(boolean empty) {
        if (empty) {
            return new RagContext(List.of(), 0, 4096);
        }
        ContextEntry entry = new ContextEntry(1, 100L, 42L, "test.pdf", "test content",
                0, "Section 1", 0.95f, ScoreType.RRF, 3);
        return new RagContext(List.of(entry), 50, 4096);
    }

    private CitationRegistry mockCitationRegistry() {
        Citation citation = new Citation(1, 42L, "test.pdf", 100L, "test content", 0.95f, 3);
        return CitationRegistry.from(List.of(citation));
    }

    /**
     * Set up insertMessagePair to simulate MyBatis-Plus auto-increment ID assignment.
     * This is critical — without it, userMessage.getId() returns null, causing
     * buildPrompt to pass null as excludeMessageId, cascading to test failures.
     */
    private void stubInsertMessagePair() {
        doAnswer(invocation -> {
            ChatMessage userMsg = invocation.getArgument(1);
            ChatMessage assistantMsg = invocation.getArgument(2);
            userMsg.setId(messageIdCounter.incrementAndGet());
            assistantMsg.setId(messageIdCounter.incrementAndGet());
            return null;
        }).when(conversationService).insertMessagePair(anyLong(), any(ChatMessage.class), any(ChatMessage.class));
    }

    /**
     * Set up common mocks needed by all async pipeline tests.
     * Uses lenient() to avoid UnnecessaryStubbingException when a nested test
     * doesn't consume all stubs.
     */
    private void stubCommonPipeline() {
        lenient().when(conversationService.get(CONVERSATION_ID, WORKSPACE_ID, USER_ID))
                .thenReturn(mock(com.intellidesk.chat.conversation.Conversation.class));
        lenient().doNothing().when(conversationService).requireNotBusy(CONVERSATION_ID);
        lenient().when(scopeResolver.resolve(eq(WORKSPACE_ID), anyList(), isNull(), eq(USER_ID)))
                .thenReturn(mockScope());
        lenient().when(sseEventBuilder.newRequestId()).thenReturn("test-id");
        stubInsertMessagePair();
    }

    // ==================== Validation Tests ====================

    @Nested
    @DisplayName("request validation")
    class ValidationTests {

        @Test
        @DisplayName("null query is rejected")
        void nullQueryRejected() {
            ChatRequest req = createRequest(null);
            req.setQuery(null);

            assertThatThrownBy(() -> service.chat(req, WORKSPACE_ID, CONVERSATION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(6003);
        }

        @Test
        @DisplayName("blank query is rejected")
        void blankQueryRejected() {
            ChatRequest req = createRequest("   ");

            assertThatThrownBy(() -> service.chat(req, WORKSPACE_ID, CONVERSATION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(6003);
        }

        @Test
        @DisplayName("query exceeding max length is rejected")
        void queryTooLongRejected() {
            ChatRequest req = createRequest("a".repeat(5001));

            assertThatThrownBy(() -> service.chat(req, WORKSPACE_ID, CONVERSATION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(6003);
        }

        @Test
        @DisplayName("topK > candidateTopK is rejected")
        void topKExceedsCandidateTopK() {
            ChatRequest req = createRequest("test query");
            req.setTopK(10);
            req.setCandidateTopK(5);

            assertThatThrownBy(() -> service.chat(req, WORKSPACE_ID, CONVERSATION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(6003);
        }

        @Test
        @DisplayName("empty knowledgeBaseIds is rejected")
        void emptyKnowledgeBaseIdsRejected() {
            ChatRequest req = createRequest("test query");
            req.setKnowledgeBaseIds(List.of());

            assertThatThrownBy(() -> service.chat(req, WORKSPACE_ID, CONVERSATION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(6003);
        }
    }

    // ==================== Authorization Tests ====================

    @Nested
    @DisplayName("authorization")
    class AuthorizationTests {

        @Test
        @DisplayName("conversation not found returns 404")
        void conversationNotFound() {
            ChatRequest req = createRequest("test query");
            when(conversationService.get(CONVERSATION_ID, WORKSPACE_ID, USER_ID))
                    .thenThrow(new BusinessException(ErrorCode.CHAT_CONVERSATION_NOT_FOUND));

            assertThatThrownBy(() -> service.chat(req, WORKSPACE_ID, CONVERSATION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(6001);
        }

        @Test
        @DisplayName("conversation busy returns 409")
        void conversationBusy() {
            ChatRequest req = createRequest("test query");
            when(conversationService.get(CONVERSATION_ID, WORKSPACE_ID, USER_ID))
                    .thenReturn(mock(com.intellidesk.chat.conversation.Conversation.class));
            doThrow(new BusinessException(ErrorCode.CHAT_CONVERSATION_BUSY))
                    .when(conversationService).requireNotBusy(CONVERSATION_ID);

            assertThatThrownBy(() -> service.chat(req, WORKSPACE_ID, CONVERSATION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(6009);
        }
    }

    // ==================== Normal Pipeline Tests ====================

    @Nested
    @DisplayName("normal pipeline")
    class NormalPipelineTests {

        @BeforeEach
        void setUpNormalPipeline() {
            stubCommonPipeline();

            // Memory
            lenient().when(memoryService.buildRewriteContext(anyLong(), anyLong(), anyLong()))
                    .thenReturn("User: previous question\nAssistant: previous answer");
            lenient().when(memoryService.buildAnswerMessagesExcluding(anyLong(), anyLong(), anyLong(), anyInt(), anyLong()))
                    .thenReturn(List.of());

            // Query Rewrite
            lenient().when(queryRewriteService.rewrite(anyString(), anyList(), anyBoolean()))
                    .thenReturn("rewritten query");

            // Retrieval
            RetrievalResult result = new RetrievalResult(
                    100L, 42L, 1L, "test content", 0.95f, ScoreType.RRF, 0, null);
            result.setRetrievalSource(RetrievalSource.HYBRID);
            lenient().when(retrievalService.search(any(), any(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of(result));

            // Context
            RagContext ragContext = mockRagContext(false);
            lenient().when(ragContextBuilder.build(anyList(), anyInt())).thenReturn(ragContext);

            // Citation
            CitationRegistry registry = mockCitationRegistry();
            lenient().when(citationAssembler.assemble(any())).thenReturn(registry);
            lenient().when(citationValidator.validate(anyString(), any()))
                    .thenReturn(new CitationValidationResult(List.of(1), List.of(), false));

            // LLM streaming
            lenient().when(chatLlmService.generateStream(any(Prompt.class)))
                    .thenReturn(Flux.just(
                            mockChatResponse("Hello"),
                            mockChatResponse(" world"),
                            mockChatResponse("!")
                    ));
        }

        @Test
        @DisplayName("full pipeline starts successfully")
        void fullPipelineStarts() {
            ChatRequest req = createRequest("test query");

            var emitter = service.chat(req, WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            assertThat(emitter).isNotNull();
            verify(sseEventBuilder).sendStart(any(), eq("test-id"), eq(CONVERSATION_ID), anyLong());
        }

        @Test
        @DisplayName("inserts message pair in single transaction")
        void insertsMessagePairInSingleTx() {
            ChatRequest req = createRequest("test query");

            service.chat(req, WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            verify(conversationService).insertMessagePair(eq(CONVERSATION_ID), any(ChatMessage.class), any(ChatMessage.class));
        }

        @Test
        @DisplayName("persists USER message with SUCCESS status")
        void persistsUserMessageSuccess() {
            ChatRequest req = createRequest("test query");

            ArgumentCaptor<ChatMessage> userCaptor = ArgumentCaptor.forClass(ChatMessage.class);
            ArgumentCaptor<ChatMessage> assistantCaptor = ArgumentCaptor.forClass(ChatMessage.class);

            service.chat(req, WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            verify(conversationService).insertMessagePair(eq(CONVERSATION_ID), userCaptor.capture(), assistantCaptor.capture());

            ChatMessage userMsg = userCaptor.getValue();
            assertThat(userMsg.getRole()).isEqualTo(ChatRole.USER.name());
            assertThat(userMsg.getStatus()).isEqualTo(ChatMessageStatus.SUCCESS.name());
            assertThat(userMsg.getContent()).isEqualTo("test query");
        }

        @Test
        @DisplayName("persists ASSISTANT message with GENERATING status")
        void persistsAssistantMessageGenerating() {
            ChatRequest req = createRequest("test query");

            ArgumentCaptor<ChatMessage> assistantCaptor = ArgumentCaptor.forClass(ChatMessage.class);

            service.chat(req, WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            verify(conversationService).insertMessagePair(eq(CONVERSATION_ID), any(ChatMessage.class), assistantCaptor.capture());

            ChatMessage assistantMsg = assistantCaptor.getValue();
            assertThat(assistantMsg.getRole()).isEqualTo(ChatRole.ASSISTANT.name());
            assertThat(assistantMsg.getStatus()).isEqualTo(ChatMessageStatus.GENERATING.name());
            assertThat(assistantMsg.getContent()).isEmpty();
        }

        @Test
        @DisplayName("Phase 4 SSE regression: normal Chat does NOT emit tool_call or tool_result")
        void normalChatDoesNotEmitToolEvents() {
            ChatRequest req = createRequest("test query");

            service.chat(req, WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            // Normal Chat should never emit Agent tool events
            verify(sseEventBuilder, never()).sendToolCall(any(), anyString(), anyInt());
            verify(sseEventBuilder, never()).sendToolResult(any(), anyString(), anyBoolean(), anyInt(), anyLong());
        }
    }

    // ==================== Query Rewrite Tests ====================

    @Nested
    @DisplayName("query rewrite")
    class QueryRewriteTests {

        @BeforeEach
        void setUpBase() {
            stubCommonPipeline();
            lenient().when(memoryService.buildRewriteContext(anyLong(), anyLong(), anyLong()))
                    .thenReturn("User: previous\nAssistant: answer");
            lenient().when(memoryService.buildAnswerMessagesExcluding(anyLong(), anyLong(), anyLong(), anyInt(), anyLong()))
                    .thenReturn(List.of());
            lenient().when(retrievalService.search(any(), any(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            lenient().when(ragContextBuilder.build(anyList(), anyInt())).thenReturn(mockRagContext(true));
            lenient().when(citationAssembler.assemble(any())).thenReturn(CitationRegistry.empty());
            lenient().when(citationValidator.validate(anyString(), any()))
                    .thenReturn(CitationValidationResult.empty());
            lenient().when(chatLlmService.generateStream(any(Prompt.class)))
                    .thenReturn(Flux.just(mockChatResponse("no answer")));
        }

        @Test
        @DisplayName("rewrite enabled calls rewrite service")
        void rewriteEnabled() {
            ChatRequest req = createRequest("test query");
            req.setRewriteEnabled(true);
            when(queryRewriteService.rewrite(anyString(), anyList(), anyBoolean()))
                    .thenReturn("rewritten query");

            service.chat(req, WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            verify(queryRewriteService, timeout(5000)).rewrite(eq("test query"), anyList(), eq(true));
        }

        @Test
        @DisplayName("rewrite disabled skips rewrite")
        void rewriteDisabled() {
            ChatRequest req = createRequest("test query");
            req.setRewriteEnabled(false);

            service.chat(req, WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            verify(queryRewriteService, never()).rewrite(anyString(), anyList(), anyBoolean());
        }

        @Test
        @DisplayName("rewrite failure falls back to original query")
        void rewriteFailureFallback() {
            ChatRequest req = createRequest("test query");
            req.setRewriteEnabled(true);
            when(queryRewriteService.rewrite(anyString(), anyList(), anyBoolean()))
                    .thenThrow(new RuntimeException("rewrite failed"));

            service.chat(req, WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            // Should still retrieve with original query — no exception thrown
            verify(retrievalService, timeout(5000)).search(any(RetrievalQuery.class), any(), anyInt(), anyInt(), anyBoolean());
        }
    }

    // ==================== Retrieval Tests ====================

    @Nested
    @DisplayName("retrieval")
    class RetrievalTests {

        @BeforeEach
        void setUpBase() {
            stubCommonPipeline();
            lenient().when(memoryService.buildRewriteContext(anyLong(), anyLong(), anyLong())).thenReturn("");
            lenient().when(queryRewriteService.rewrite(anyString(), anyList(), anyBoolean()))
                    .thenReturn("test query");
            lenient().when(memoryService.buildAnswerMessagesExcluding(anyLong(), anyLong(), anyLong(), anyInt(), anyLong()))
                    .thenReturn(List.of());
        }

        @Test
        @DisplayName("retrieval empty builds empty context")
        void retrievalEmpty() {
            ChatRequest req = createRequest("test query");
            when(retrievalService.search(any(), any(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            when(ragContextBuilder.build(anyList(), anyInt())).thenReturn(mockRagContext(true));
            when(citationAssembler.assemble(any())).thenReturn(CitationRegistry.empty());
            when(citationValidator.validate(anyString(), any()))
                    .thenReturn(CitationValidationResult.empty());
            when(chatLlmService.generateStream(any(Prompt.class)))
                    .thenReturn(Flux.just(mockChatResponse("no answer")));

            service.chat(req, WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            verify(ragContextBuilder, timeout(5000)).build(eq(List.of()), eq(4096));
        }

        @Test
        @DisplayName("retrieval failure returns error event")
        void retrievalFailure() {
            ChatRequest req = createRequest("test query");
            when(retrievalService.search(any(), any(), anyInt(), anyInt(), anyBoolean()))
                    .thenThrow(new RuntimeException("retrieval failed"));

            service.chat(req, WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            // Wait for async execution
            verify(sseEventBuilder, timeout(5000)).sendError(any(), eq("CHAT_PROVIDER_ERROR"), anyString());
        }
    }

    // ==================== Citation Tests ====================

    @Nested
    @DisplayName("citation")
    class CitationTests {

        @BeforeEach
        void setUpBase() {
            stubCommonPipeline();
            lenient().when(memoryService.buildRewriteContext(anyLong(), anyLong(), anyLong())).thenReturn("");
            lenient().when(queryRewriteService.rewrite(anyString(), anyList(), anyBoolean()))
                    .thenReturn("test query");
            lenient().when(memoryService.buildAnswerMessagesExcluding(anyLong(), anyLong(), anyLong(), anyInt(), anyLong()))
                    .thenReturn(List.of());
        }

        @Test
        @DisplayName("valid citation is sent and persisted")
        void validCitationSent() {
            ChatRequest req = createRequest("test query");

            RetrievalResult result = new RetrievalResult(
                    100L, 42L, 1L, "test content", 0.95f, ScoreType.RRF, 0, null);
            result.setRetrievalSource(RetrievalSource.HYBRID);
            when(retrievalService.search(any(), any(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of(result));

            RagContext ragContext = mockRagContext(false);
            when(ragContextBuilder.build(anyList(), anyInt())).thenReturn(ragContext);

            CitationRegistry registry = mockCitationRegistry();
            when(citationAssembler.assemble(any())).thenReturn(registry);
            when(citationValidator.validate(anyString(), any()))
                    .thenReturn(new CitationValidationResult(List.of(1), List.of(), false));

            when(chatLlmService.generateStream(any(Prompt.class)))
                    .thenReturn(Flux.just(mockChatResponse("test [1]")));

            service.chat(req, WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            verify(sseEventBuilder, timeout(5000)).sendCitations(any(), argThat(list ->
                    list != null && list.size() == 1 && list.get(0).citationId() == 1));
        }

        @Test
        @DisplayName("hallucinated citation is filtered")
        void hallucinatedCitationFiltered() {
            ChatRequest req = createRequest("test query");

            RetrievalResult result = new RetrievalResult(
                    100L, 42L, 1L, "test content", 0.95f, ScoreType.RRF, 0, null);
            result.setRetrievalSource(RetrievalSource.HYBRID);
            when(retrievalService.search(any(), any(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of(result));

            RagContext ragContext = mockRagContext(false);
            when(ragContextBuilder.build(anyList(), anyInt())).thenReturn(ragContext);

            CitationRegistry registry = mockCitationRegistry();
            when(citationAssembler.assemble(any())).thenReturn(registry);
            // LLM output [99] but registry only has [1] — hallucinated
            when(citationValidator.validate(anyString(), any()))
                    .thenReturn(new CitationValidationResult(List.of(), List.of(99), true));

            when(chatLlmService.generateStream(any(Prompt.class)))
                    .thenReturn(Flux.just(mockChatResponse("test [99]")));

            service.chat(req, WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            // Should send empty citations (hallucinated filtered)
            verify(sseEventBuilder, timeout(5000)).sendCitations(any(), argThat(List::isEmpty));
        }

        @Test
        @DisplayName("empty citation registry handled gracefully")
        void emptyCitationRegistry() {
            ChatRequest req = createRequest("test query");

            when(retrievalService.search(any(), any(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            when(ragContextBuilder.build(anyList(), anyInt())).thenReturn(mockRagContext(true));
            when(citationAssembler.assemble(any())).thenReturn(CitationRegistry.empty());
            when(citationValidator.validate(anyString(), any()))
                    .thenReturn(CitationValidationResult.empty());
            when(chatLlmService.generateStream(any(Prompt.class)))
                    .thenReturn(Flux.just(mockChatResponse("no citations")));

            service.chat(req, WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            verify(sseEventBuilder, timeout(5000)).sendCitations(any(), argThat(List::isEmpty));
        }
    }

    // ==================== Provider Error Tests ====================

    @Nested
    @DisplayName("provider errors")
    class ProviderErrorTests {

        @BeforeEach
        void setUpBase() {
            stubCommonPipeline();
            lenient().when(memoryService.buildRewriteContext(anyLong(), anyLong(), anyLong())).thenReturn("");
            lenient().when(queryRewriteService.rewrite(anyString(), anyList(), anyBoolean()))
                    .thenReturn("test query");
            lenient().when(memoryService.buildAnswerMessagesExcluding(anyLong(), anyLong(), anyLong(), anyInt(), anyLong()))
                    .thenReturn(List.of());
        }

        @Test
        @DisplayName("LLM 429 error returns CHAT_RATE_LIMITED")
        void llm429Error() {
            ChatRequest req = createRequest("test query");

            when(retrievalService.search(any(), any(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            when(ragContextBuilder.build(anyList(), anyInt())).thenReturn(mockRagContext(true));
            when(citationAssembler.assemble(any())).thenReturn(CitationRegistry.empty());
            when(chatLlmService.generateStream(any(Prompt.class)))
                    .thenReturn(Flux.error(new ChatLlmException("CHAT_RATE_LIMITED", "Rate limit exceeded")));

            service.chat(req, WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            verify(sseEventBuilder, timeout(5000)).sendError(any(), eq("CHAT_RATE_LIMITED"), anyString());
        }

        @Test
        @DisplayName("LLM 5xx error returns CHAT_PROVIDER_ERROR")
        void llm5xxError() {
            ChatRequest req = createRequest("test query");

            when(retrievalService.search(any(), any(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            when(ragContextBuilder.build(anyList(), anyInt())).thenReturn(mockRagContext(true));
            when(citationAssembler.assemble(any())).thenReturn(CitationRegistry.empty());
            when(chatLlmService.generateStream(any(Prompt.class)))
                    .thenReturn(Flux.error(new RuntimeException("500 Internal Server Error")));

            service.chat(req, WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            verify(sseEventBuilder, timeout(5000)).sendError(any(), eq("CHAT_PROVIDER_ERROR"), anyString());
        }

        @Test
        @DisplayName("provider error sets assistant to FAILED")
        void providerErrorSetsAssistantFailed() {
            ChatRequest req = createRequest("test query");

            when(retrievalService.search(any(), any(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            when(ragContextBuilder.build(anyList(), anyInt())).thenReturn(mockRagContext(true));
            when(citationAssembler.assemble(any())).thenReturn(CitationRegistry.empty());
            when(chatLlmService.generateStream(any(Prompt.class)))
                    .thenReturn(Flux.error(new RuntimeException("500 error")));

            service.chat(req, WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            verify(conversationService, timeout(5000)).updateMessageCas(argThat(msg ->
                    ChatMessageStatus.FAILED.name().equals(msg.getStatus())));
        }

        @Test
        @DisplayName("empty provider stream fails closed without a done event")
        void emptyProviderStreamFailsClosed() {
            ChatRequest req = createRequest("test query");

            when(retrievalService.search(any(), any(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            when(ragContextBuilder.build(anyList(), anyInt())).thenReturn(mockRagContext(true));
            when(citationAssembler.assemble(any())).thenReturn(CitationRegistry.empty());
            when(chatLlmService.generateStream(any(Prompt.class))).thenReturn(Flux.empty());

            service.chat(req, WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            verify(sseEventBuilder, timeout(5000)).sendError(
                    any(), eq("CHAT_INVALID_RESPONSE"), eq("LLM returned an empty response"));
            verify(conversationService, timeout(5000)).updateMessageCas(argThat(msg ->
                    ChatMessageStatus.FAILED.name().equals(msg.getStatus())
                            && "CHAT_INVALID_RESPONSE".equals(msg.getErrorCode())
                            && "".equals(msg.getContent())));
            verify(sseEventBuilder, never()).sendDone(any(), anyLong(), anyString());
            verify(citationValidator, never()).validate(anyString(), any());
        }
    }

    // ==================== Memory Tests ====================

    @Nested
    @DisplayName("memory")
    class MemoryTests {

        @BeforeEach
        void setUpBase() {
            stubCommonPipeline();
            lenient().when(memoryService.buildRewriteContext(anyLong(), anyLong(), anyLong())).thenReturn("");
            lenient().when(queryRewriteService.rewrite(anyString(), anyList(), anyBoolean()))
                    .thenReturn("test query");
            lenient().when(retrievalService.search(any(), any(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            lenient().when(ragContextBuilder.build(anyList(), anyInt())).thenReturn(mockRagContext(true));
            lenient().when(citationAssembler.assemble(any())).thenReturn(CitationRegistry.empty());
            lenient().when(citationValidator.validate(anyString(), any()))
                    .thenReturn(CitationValidationResult.empty());
            lenient().when(chatLlmService.generateStream(any(Prompt.class)))
                    .thenReturn(Flux.just(mockChatResponse("answer")));
        }

        @Test
        @DisplayName("buildAnswerMessagesExcluding uses excludeMessageId")
        void memoryExcludesCurrentMessage() {
            ChatRequest req = createRequest("test query");

            ArgumentCaptor<Long> excludeIdCaptor = ArgumentCaptor.forClass(Long.class);

            service.chat(req, WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            verify(memoryService, timeout(5000)).buildAnswerMessagesExcluding(
                    anyLong(), anyLong(), anyLong(), anyInt(), excludeIdCaptor.capture());

            assertThat(excludeIdCaptor.getValue()).isNotNull();
        }

        @Test
        @DisplayName("uses memoryProperties maxMessages")
        void usesMemoryPropertiesMaxMessages() {
            ChatRequest req = createRequest("test query");

            service.chat(req, WORKSPACE_ID, CONVERSATION_ID, USER_ID);

            verify(memoryService, timeout(5000)).buildAnswerMessagesExcluding(
                    anyLong(), anyLong(), anyLong(), eq(10), anyLong());
        }
    }

    // ==================== Prompt Tests ====================

    @Nested
    @DisplayName("prompt")
    class PromptTests {

        @Test
        @DisplayName("buildPrompt includes system prompt with context")
        void buildPromptWithContext() {
            RagContext ragContext = mockRagContext(false);
            Prompt prompt = service.buildPrompt("test query", ragContext, WORKSPACE_ID,
                    CONVERSATION_ID, USER_ID, 999L);

            assertThat(prompt).isNotNull();
            assertThat(prompt.getInstructions()).hasSizeGreaterThanOrEqualTo(1);
            // First message should be system message
            assertThat(prompt.getInstructions().get(0).getMessageType().name()).contains("SYSTEM");
            // Last message should be user query
            Message lastMsg = prompt.getInstructions().get(prompt.getInstructions().size() - 1);
            assertThat(lastMsg.getMessageType().name()).contains("USER");
        }

        @Test
        @DisplayName("buildPrompt uses no-context prompt when empty")
        void buildPromptEmptyContext() {
            RagContext ragContext = mockRagContext(true);
            Prompt prompt = service.buildPrompt("test query", ragContext, WORKSPACE_ID,
                    CONVERSATION_ID, USER_ID, 999L);

            assertThat(prompt).isNotNull();
            String systemText = prompt.getInstructions().get(0).getText();
            assertThat(systemText).contains("No relevant context was found");
        }
    }

    // ==================== Delta Extraction Tests ====================

    @Nested
    @DisplayName("delta extraction")
    class DeltaExtractionTests {

        @Test
        @DisplayName("extracts text from ChatResponse")
        void extractsDelta() {
            ChatResponse response = mockChatResponse("hello");
            String delta = service.extractDelta(response);
            assertThat(delta).isEqualTo("hello");
        }

        @Test
        @DisplayName("null response returns null")
        void nullResponseReturnsNull() {
            assertThat(service.extractDelta(null)).isNull();
        }

        @Test
        @DisplayName("empty results returns null")
        void emptyResultsReturnsNull() {
            ChatResponse response = new ChatResponse(List.of(), null);
            assertThat(service.extractDelta(response)).isNull();
        }
    }

    // ==================== Error Code Mapping Tests ====================

    @Nested
    @DisplayName("error code mapping")
    class ErrorCodeMappingTests {

        @Test
        @DisplayName("maps ChatLlmException error code")
        void mapsChatLlmException() {
            String code = service.mapErrorCode(new ChatLlmException("CHAT_AUTH_ERROR", "auth failed"));
            assertThat(code).isEqualTo("CHAT_AUTH_ERROR");
        }

        @Test
        @DisplayName("maps 429 to CHAT_RATE_LIMITED")
        void maps429() {
            String code = service.mapErrorCode(new RuntimeException("429 Too Many Requests"));
            assertThat(code).isEqualTo("CHAT_RATE_LIMITED");
        }

        @Test
        @DisplayName("maps timeout to CHAT_TIMEOUT")
        void mapsTimeout() {
            String code = service.mapErrorCode(new RuntimeException("connection timeout"));
            assertThat(code).isEqualTo("CHAT_TIMEOUT");
        }

        @Test
        @DisplayName("maps 5xx to CHAT_PROVIDER_ERROR")
        void maps5xx() {
            String code = service.mapErrorCode(new RuntimeException("500 Internal Server Error"));
            assertThat(code).isEqualTo("CHAT_PROVIDER_ERROR");
        }

        @Test
        @DisplayName("defaults to CHAT_PROVIDER_ERROR")
        void defaultsToProviderError() {
            String code = service.mapErrorCode(new RuntimeException("unknown"));
            assertThat(code).isEqualTo("CHAT_PROVIDER_ERROR");
        }
    }

    // ==================== Sanitization Tests ====================

    @Nested
    @DisplayName("error sanitization")
    class ErrorSanitizationTests {

        @Test
        @DisplayName("sanitizes ChatLlmException message")
        void sanitizesChatLlmException() {
            String msg = service.sanitizeErrorMessage(new ChatLlmException("ERR", "detailed error"));
            assertThat(msg).isEqualTo("detailed error");
        }

        @Test
        @DisplayName("truncates long messages")
        void truncatesLongMessages() {
            String longMsg = "a".repeat(300);
            String msg = service.sanitizeErrorMessage(new RuntimeException(longMsg));
            assertThat(msg.length()).isLessThanOrEqualTo(200);
        }
    }
}
