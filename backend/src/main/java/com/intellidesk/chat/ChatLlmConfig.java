package com.intellidesk.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import io.micrometer.observation.ObservationRegistry;

import java.time.Duration;

@Configuration
public class ChatLlmConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatLlmConfig.class);

    private final ChatLlmProperties properties;

    public ChatLlmConfig(ChatLlmProperties properties) {
        this.properties = properties;
    }

    /**
     * Fallback NOOP ObservationRegistry ONLY when Micrometer observation support is absent
     * from the classpath. When Micrometer is present (Wave 5 observability), this bean is
     * skipped so Micrometer's real ObservationRegistry is auto-configured; otherwise the
     * NOOP fallback would shadow it and silently drop observation-based metrics (e.g.
     * http.server.requests) used by Prometheus/Grafana.
     */
    @Bean
    @ConditionalOnMissingClass("io.micrometer.observation.ObservationRegistry")
    public ObservationRegistry observationRegistry() {
        return ObservationRegistry.NOOP;
    }

    @Bean
    @ConditionalOnMissingBean(ChatModel.class)
    public ChatModel chatModel(ObservationRegistry observationRegistry) {
        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("CHAT_API_KEY is not set. ChatModel will not be available.");
            throw new IllegalStateException("CHAT_API_KEY must be configured");
        }

        String baseUrl = properties.getBaseUrl();
        log.info("Configuring OpenAiChatModel with baseUrl={}, model={}", baseUrl, properties.getModel());
        // Do NOT log apiKey

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()));

        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(requestFactory);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .restClientBuilder(restClientBuilder)
                .build();

        OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder()
                .model(properties.getModel())
                .temperature(properties.getTemperature())
                .maxTokens(properties.getMaxTokens())
                .build();

        return new OpenAiChatModel(
                openAiApi,
                defaultOptions,
                ToolCallingManager.builder().build(),
                RetryUtils.DEFAULT_RETRY_TEMPLATE,
                observationRegistry
        );
    }
}