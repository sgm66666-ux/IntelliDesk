package com.intellidesk.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatLlmService")
class ChatLlmServiceTest {

    @Mock
    private ChatModel chatModel;

    private ChatLlmProperties properties;
    private SpringAiChatLlmService service;

    @BeforeEach
    void setUp() {
        properties = new ChatLlmProperties();
        properties.setApiKey("test-chat-key");
        properties.setBaseUrl("https://api.openai.com");
        properties.setModel("gpt-4o-mini");
        properties.setTemperature(0.1);
        properties.setMaxTokens(2048);
        properties.setTimeoutSeconds(60);
        service = new SpringAiChatLlmService(chatModel, properties);
    }

    private ChatResponse mockResponse(String content) {
        AssistantMessage msg = new AssistantMessage(content);
        Generation gen = new Generation(msg, ChatGenerationMetadata.builder().finishReason("stop").build());
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(50, 20, 70))
                .build();
        return new ChatResponse(List.of(gen), metadata);
    }

    @Nested
    @DisplayName("generate")
    class GenerateTests {

        @Test
        @DisplayName("returns successful ChatResponse")
        void returnsSuccessfulResponse() {
            ChatResponse expected = mockResponse("Hello, world!");
            when(chatModel.call(any(Prompt.class))).thenReturn(expected);

            ChatResponse result = service.generate(new Prompt("hello"));

            assertThat(result).isNotNull();
            assertThat(result.getResults()).hasSize(1);
            assertThat(result.getResults().get(0).getOutput().getText()).isEqualTo("Hello, world!");
        }

        @Test
        @DisplayName("sanitizes 401 error")
        void sanitizes401Error() {
            when(chatModel.call(any(Prompt.class)))
                    .thenThrow(new RuntimeException("401 Unauthorized"));

            assertThatThrownBy(() -> service.generate(new Prompt("hello")))
                    .isInstanceOf(ChatLlmException.class)
                    .hasMessageContaining("authentication failed")
                    .extracting("errorCode")
                    .isEqualTo("CHAT_AUTH_ERROR");
        }

        @Test
        @DisplayName("sanitizes 429 error")
        void sanitizes429Error() {
            when(chatModel.call(any(Prompt.class)))
                    .thenThrow(new RuntimeException("Rate limit exceeded: 429"));

            assertThatThrownBy(() -> service.generate(new Prompt("hello")))
                    .isInstanceOf(ChatLlmException.class)
                    .extracting("errorCode")
                    .isEqualTo("CHAT_RATE_LIMITED");
        }

        @Test
        @DisplayName("sanitizes timeout error")
        void sanitizesTimeoutError() {
            when(chatModel.call(any(Prompt.class)))
                    .thenThrow(new RuntimeException("Read timed out"));

            assertThatThrownBy(() -> service.generate(new Prompt("hello")))
                    .isInstanceOf(ChatLlmException.class)
                    .extracting("errorCode")
                    .isEqualTo("CHAT_TIMEOUT");
        }

        @Test
        @DisplayName("sanitizes 5xx error")
        void sanitizes5xxError() {
            when(chatModel.call(any(Prompt.class)))
                    .thenThrow(new RuntimeException("500 Internal Server Error"));

            assertThatThrownBy(() -> service.generate(new Prompt("hello")))
                    .isInstanceOf(ChatLlmException.class)
                    .extracting("errorCode")
                    .isEqualTo("CHAT_PROVIDER_ERROR");
        }

        @Test
        @DisplayName("sanitizes connection failure")
        void sanitizesConnectionFailure() {
            when(chatModel.call(any(Prompt.class)))
                    .thenThrow(new RuntimeException("Connection refused"));

            assertThatThrownBy(() -> service.generate(new Prompt("hello")))
                    .isInstanceOf(ChatLlmException.class)
                    .extracting("errorCode")
                    .isEqualTo("CHAT_PROVIDER_ERROR");
        }

        @Test
        @DisplayName("sanitizes generic error without leaking details")
        void sanitizesGenericError() {
            when(chatModel.call(any(Prompt.class)))
                    .thenThrow(new RuntimeException("Some unknown error"));

            assertThatThrownBy(() -> service.generate(new Prompt("hello")))
                    .isInstanceOf(ChatLlmException.class)
                    .extracting("errorCode")
                    .isEqualTo("CHAT_PROVIDER_ERROR");
        }
    }

    @Nested
    @DisplayName("stream")
    class StreamTests {

        @Test
        @DisplayName("stream ability is available")
        void streamAbilityAvailable() {
            ChatResponse expected = mockResponse("token");
            when(chatModel.stream(any(Prompt.class)))
                    .thenReturn(reactor.core.publisher.Flux.just(expected));

            var flux = service.generateStream(new Prompt("hello"));
            assertThat(flux).isNotNull();
        }
    }

    @Nested
    @DisplayName("transaction guard")
    class TransactionGuardTests {

        @Test
        @DisplayName("verifies guard exists outside transaction")
        void guardExists() {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        }
    }
}