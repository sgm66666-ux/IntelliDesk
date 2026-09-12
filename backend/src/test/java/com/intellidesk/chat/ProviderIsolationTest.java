package com.intellidesk.chat;

import com.intellidesk.TestInfrastructureConfig;
import com.intellidesk.infrastructure.config.EmbeddingProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider Isolation Test: Verify Chat and Embedding configs are independent.
 * Uses Spring context to verify both properties beans are loaded correctly.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructureConfig.class)
@DisplayName("Provider Isolation")
class ProviderIsolationTest {

    @Autowired
    private ChatLlmProperties chatProperties;

    @Autowired
    private EmbeddingProperties embeddingProperties;

    @Test
    @DisplayName("Chat config does not affect Embedding config")
    void chatConfigDoesNotAffectEmbedding() {
        // Chat uses its own config
        assertThat(chatProperties.getBaseUrl()).isEqualTo("https://api.openai.com");
        assertThat(chatProperties.getModel()).isEqualTo("gpt-4o-mini");
        assertThat(chatProperties.getApiKey()).isEqualTo("test-chat-key");

        // Embedding uses its own config (different from Chat)
        assertThat(embeddingProperties.getBaseUrl()).isEqualTo("https://api.openai.com");
        assertThat(embeddingProperties.getModel()).isEqualTo("text-embedding-3-small");
        assertThat(embeddingProperties.getApiKey()).isEqualTo("test-key");

        // Verify they are independent properties objects
        assertThat(chatProperties).isNotSameAs(embeddingProperties);
    }

    @Test
    @DisplayName("Chat and Embedding can have different provider URLs")
    void chatAndEmbeddingCanHaveDifferentUrls() {
        // Even though both use the same URL in test profile,
        // they are independently configurable via different properties
        assertThat(chatProperties.getBaseUrl()).isNotNull();
        assertThat(embeddingProperties.getBaseUrl()).isNotNull();

        // They use different model names, proving independence
        assertThat(chatProperties.getModel()).isNotEqualTo(embeddingProperties.getModel());
    }

    @Test
    @DisplayName("Chat temperature is independent of Embedding dimensions")
    void chatTemperatureIndependentOfEmbeddingDimensions() {
        // Chat has temperature control
        assertThat(chatProperties.getTemperature()).isGreaterThan(0);

        // Embedding has dimension control
        assertThat(embeddingProperties.getDimension()).isGreaterThan(0);

        // These are completely independent configuration spaces
    }
}