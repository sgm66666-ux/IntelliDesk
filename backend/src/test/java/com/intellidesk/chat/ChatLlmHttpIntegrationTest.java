package com.intellidesk.chat;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Flux;

import java.net.Socket;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real HTTP integration test: Spring AI 1.1.2 → OpenAiChatModel → HTTP → local fixture.
 * Requires e2e-phase4-provider-stub.js running on port 18082.
 */
@DisplayName("ChatLlmService HTTP Integration (real fixture)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChatLlmHttpIntegrationTest {

    private static final String FIXTURE_HOST = "127.0.0.1";
    private static final int FIXTURE_PORT = 18082;
    private static final String FIXTURE_BASE_URL = "http://" + FIXTURE_HOST + ":" + FIXTURE_PORT;
    private static final String FIXTURE_API_KEY = "test-chat-key";

    private static boolean fixtureAvailable;

    @BeforeAll
    static void checkFixture() {
        fixtureAvailable = isPortOpen(FIXTURE_HOST, FIXTURE_PORT);
        if (!fixtureAvailable) {
            System.out.println("[WARN] Chat fixture not available on " + FIXTURE_BASE_URL +
                    ". Skipping ChatLlmHttpIntegrationTest. Start: node deploy/e2e-phase4-provider-stub.js");
        }
    }

    private static boolean isPortOpen(String host, int port) {
        try (Socket socket = new Socket(host, port)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private SpringAiChatLlmService createService(String apiKey, int timeoutSeconds) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));

        OpenAiApi api = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(FIXTURE_BASE_URL)
                .restClientBuilder(RestClient.builder()
                        .requestFactory(requestFactory)
                        .defaultStatusHandler(HttpStatusCode::isError, (req, resp) -> {
                            throw new RuntimeException("HTTP " + resp.getStatusCode().value() + " " + resp.getStatusText());
                        }))
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model("chat-test")
                .build();

        OpenAiChatModel chatModel = new OpenAiChatModel(
                api, options,
                ToolCallingManager.builder().build(),
                RetryUtils.SHORT_RETRY_TEMPLATE,
                ObservationRegistry.NOOP
        );

        ChatLlmProperties props = new ChatLlmProperties();
        props.setModel("chat-test");
        props.setApiKey(apiKey);
        props.setTimeoutSeconds(timeoutSeconds);

        return new SpringAiChatLlmService(chatModel, props);
    }

    private SpringAiChatLlmService createService() {
        return createService(FIXTURE_API_KEY, 60);
    }

    @Nested
    @DisplayName("Sync Chat")
    class SyncChatTests {

        @Test
        @Order(1)
        @DisplayName("normal sync response from fixture")
        void normalSyncResponse() {
            Assumptions.assumeTrue(fixtureAvailable, "Fixture not available");

            SpringAiChatLlmService service = createService();
            Prompt prompt = new Prompt(new UserMessage("Hello"));

            ChatResponse response = service.generate(prompt);

            assertThat(response).isNotNull();
            assertThat(response.getResults()).isNotEmpty();
            assertThat(response.getResults().get(0).getOutput().getText())
                    .isEqualTo("This is a deterministic test response from IntelliDesk Phase 4 chat fixture.");
            assertThat(response.getMetadata().getUsage()).isNotNull();
            assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(70);
        }

        @Test
        @Order(2)
        @DisplayName("correct model in request")
        void correctModelInRequest() {
            Assumptions.assumeTrue(fixtureAvailable, "Fixture not available");

            SpringAiChatLlmService service = createService();
            Prompt prompt = new Prompt(new UserMessage("test model"));

            ChatResponse response = service.generate(prompt);

            assertThat(response.getMetadata().getModel()).isEqualTo("chat-test");
        }

        @Test
        @Order(3)
        @DisplayName("POST /v1/chat/completions with Authorization Bearer")
        void authorizationBearerHeader() {
            Assumptions.assumeTrue(fixtureAvailable, "Fixture not available");

            // With wrong API key (no Bearer prefix simulation), fixture returns 401
            SpringAiChatLlmService service = createService("wrong-key", 60);
            Prompt prompt = new Prompt(new UserMessage("test"));

            assertThatThrownBy(() -> service.generate(prompt))
                    .isInstanceOf(ChatLlmException.class)
                    .extracting("errorCode")
                    .isEqualTo("CHAT_AUTH_ERROR");
        }
    }

    @Nested
    @DisplayName("Streaming Chat")
    class StreamingChatTests {

        @Test
        @Order(4)
        @DisplayName("streaming response from fixture")
        void streamingResponse() {
            Assumptions.assumeTrue(fixtureAvailable, "Fixture not available");

            SpringAiChatLlmService service = createService();
            Prompt prompt = new Prompt(new UserMessage("Stream test"));

            Flux<ChatResponse> flux = service.generateStream(prompt);
            List<ChatResponse> responses = flux.collectList().block();

            assertThat(responses).isNotNull();
            assertThat(responses).isNotEmpty();
            // Verify stream tokens combine to expected text
            StringBuilder fullText = new StringBuilder();
            for (ChatResponse r : responses) {
                if (r.getResults() != null && !r.getResults().isEmpty()) {
                    String text = r.getResults().get(0).getOutput().getText();
                    if (text != null) fullText.append(text);
                }
            }
            assertThat(fullText.toString()).contains("deterministic").contains("streaming");
        }
    }

    @Nested
    @DisplayName("Error Scenarios")
    class ErrorScenarioTests {

        @Test
        @Order(5)
        @DisplayName("429 rate limit")
        void rateLimit429() {
            Assumptions.assumeTrue(fixtureAvailable, "Fixture not available");

            SpringAiChatLlmService service = createService("test-chat-429-key", 60);
            Prompt prompt = new Prompt(new UserMessage("test"));

            assertThatThrownBy(() -> service.generate(prompt))
                    .isInstanceOf(ChatLlmException.class)
                    .extracting("errorCode")
                    .isEqualTo("CHAT_RATE_LIMITED");
        }

        @Test
        @Order(6)
        @DisplayName("500 server error")
        void serverError500() {
            Assumptions.assumeTrue(fixtureAvailable, "Fixture not available");

            SpringAiChatLlmService service = createService("test-chat-failure-key", 60);
            Prompt prompt = new Prompt(new UserMessage("test"));

            assertThatThrownBy(() -> service.generate(prompt))
                    .isInstanceOf(ChatLlmException.class)
                    .extracting("errorCode")
                    .isEqualTo("CHAT_PROVIDER_ERROR");
        }

        @Test
        @Order(7)
        @DisplayName("timeout")
        void timeout() {
            Assumptions.assumeTrue(fixtureAvailable, "Fixture not available");

            // Use short timeout (1s) so fixture's 30s delay triggers timeout
            SpringAiChatLlmService service = createService("test-chat-timeout-key", 1);
            Prompt prompt = new Prompt(new UserMessage("test"));

            assertThatThrownBy(() -> service.generate(prompt))
                    .isInstanceOf(ChatLlmException.class)
                    .extracting("errorCode")
                    .isEqualTo("CHAT_TIMEOUT");
        }

        @Test
        @Order(8)
        @DisplayName("malformed response")
        void malformedResponse() {
            Assumptions.assumeTrue(fixtureAvailable, "Fixture not available");

            SpringAiChatLlmService service = createService("test-chat-malformed-key", 60);
            Prompt prompt = new Prompt(new UserMessage("test"));

            assertThatThrownBy(() -> service.generate(prompt))
                    .isInstanceOf(ChatLlmException.class)
                    .extracting("errorCode")
                    .isEqualTo("CHAT_PROVIDER_ERROR");
        }
    }
}