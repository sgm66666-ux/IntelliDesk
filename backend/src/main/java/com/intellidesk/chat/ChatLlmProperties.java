package com.intellidesk.chat;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "intellidesk.chat")
public class ChatLlmProperties {

    private static final Logger log = LoggerFactory.getLogger(ChatLlmProperties.class);

    private String baseUrl = "https://api.openai.com";
    private String apiKey;
    private String model = "gpt-4o-mini";
    private double temperature = 0.1;
    private int maxTokens = 2048;
    private int timeoutSeconds = 60;

    @PostConstruct
    public void validate() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("CHAT_API_KEY is not set. ChatModel will not be available.");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("intellidesk.chat.model must be configured");
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 300) {
            throw new IllegalStateException("intellidesk.chat.timeout-seconds must be between 1 and 300");
        }
    }
}