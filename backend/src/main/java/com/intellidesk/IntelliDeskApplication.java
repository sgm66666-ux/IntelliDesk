package com.intellidesk;

import com.intellidesk.api_key.ApiKeyProperties;
import com.intellidesk.infrastructure.config.DistributedLockProperties;
import com.intellidesk.infrastructure.config.RateLimitProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;

@SpringBootApplication(exclude = {
    OpenAiAudioSpeechAutoConfiguration.class,
    OpenAiAudioTranscriptionAutoConfiguration.class,
    OpenAiImageAutoConfiguration.class,
    OpenAiModerationAutoConfiguration.class,
    OpenAiChatAutoConfiguration.class
})
@EnableConfigurationProperties({ApiKeyProperties.class, RateLimitProperties.class, DistributedLockProperties.class})
public class IntelliDeskApplication {

    public static void main(String[] args) {
        SpringApplication.run(IntelliDeskApplication.class, args);
    }
}