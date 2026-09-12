package com.intellidesk.chat.rewrite;

import com.intellidesk.chat.ChatLlmProperties;
import com.intellidesk.chat.SpringAiChatLlmService;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.*;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.Socket;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real QueryRewriteService integration test: QueryRewriteService → ChatLlmService → Spring AI → HTTP fixture.
 * Requires e2e-phase4-provider-stub.js running on port 18082.
 */
@DisplayName("QueryRewriteService HTTP Integration (real fixture)")
class QueryRewriteHttpIntegrationTest {

    private static final String FIXTURE_HOST = "127.0.0.1";
    private static final int FIXTURE_PORT = 18082;
    private static final String FIXTURE_BASE_URL = "http://" + FIXTURE_HOST + ":" + FIXTURE_PORT;

    private static boolean fixtureAvailable;

    @BeforeAll
    static void checkFixture() {
        fixtureAvailable = isPortOpen(FIXTURE_HOST, FIXTURE_PORT);
        if (!fixtureAvailable) {
            System.out.println("[WARN] Chat fixture not available on " + FIXTURE_BASE_URL +
                    ". Skipping QueryRewriteHttpIntegrationTest.");
        }
    }

    private static boolean isPortOpen(String host, int port) {
        try (Socket socket = new Socket(host, port)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private QueryRewriteService createRewriteService() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(60));

        OpenAiApi api = OpenAiApi.builder()
                .apiKey("test-chat-key")
                .baseUrl(FIXTURE_BASE_URL)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
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

        ChatLlmProperties chatProps = new ChatLlmProperties();
        chatProps.setModel("chat-test");
        chatProps.setApiKey("test-chat-key");

        SpringAiChatLlmService chatLlmService = new SpringAiChatLlmService(chatModel, chatProps);

        QueryRewriteProperties rewriteProps = new QueryRewriteProperties();
        rewriteProps.setEnabled(true);
        rewriteProps.setHistoryMessages(4);
        rewriteProps.setMaxLength(500);

        return new QueryRewriteService(chatLlmService, rewriteProps);
    }

    @Test
    @DisplayName("history-based standalone rewrite")
    void historyBasedStandaloneRewrite() {
        Assumptions.assumeTrue(fixtureAvailable, "Fixture not available");

        QueryRewriteService service = createRewriteService();
        String originalQuery = "那北京呢？";

        String result = service.rewrite(originalQuery,
                List.of("公司的差旅标准是什么？", "那北京呢？"), true);

        assertThat(result).isNotNull();
        assertThat(result).isNotEqualTo(originalQuery);
        // Fixture returns: "Standalone rewritten: 那北京呢？"
        assertThat(result).contains("Standalone rewritten");
        assertThat(result).contains("北京");
    }

    @Test
    @DisplayName("original query not modified")
    void originalQueryNotModified() {
        Assumptions.assumeTrue(fixtureAvailable, "Fixture not available");

        QueryRewriteService service = createRewriteService();
        String originalQuery = "那北京呢？";

        String result = service.rewrite(originalQuery,
                List.of("公司的差旅标准是什么？"), true);

        // Original query should not be modified by the rewrite process
        assertThat(originalQuery).isEqualTo("那北京呢？");
        assertThat(result).isNotEqualTo(originalQuery);
    }

    @Test
    @DisplayName("disabled does not call provider")
    void disabledDoesNotCallProvider() {
        Assumptions.assumeTrue(fixtureAvailable, "Fixture not available");

        QueryRewriteService service = createRewriteService();
        String originalQuery = "那北京呢？";

        String result = service.rewrite(originalQuery,
                List.of("公司的差旅标准是什么？"), false);

        // Disabled: should return original query unchanged
        assertThat(result).isEqualTo(originalQuery);
    }

    @Test
    @DisplayName("provider failure fallback to original query")
    void providerFailureFallback() {
        Assumptions.assumeTrue(fixtureAvailable, "Fixture not available");

        // Create service with a key that triggers 500 error
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(60));

        OpenAiApi api = OpenAiApi.builder()
                .apiKey("test-chat-failure-key")
                .baseUrl(FIXTURE_BASE_URL)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
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

        ChatLlmProperties chatProps = new ChatLlmProperties();
        chatProps.setModel("chat-test");
        chatProps.setApiKey("test-chat-failure-key");

        SpringAiChatLlmService chatLlmService = new SpringAiChatLlmService(chatModel, chatProps);

        QueryRewriteProperties rewriteProps = new QueryRewriteProperties();
        rewriteProps.setEnabled(true);

        QueryRewriteService service = new QueryRewriteService(chatLlmService, rewriteProps);

        String originalQuery = "那北京呢？";
        String result = service.rewrite(originalQuery,
                List.of("公司的差旅标准是什么？"), true);

        // Provider failure: should fallback to original query
        assertThat(result).isEqualTo(originalQuery);
    }
}