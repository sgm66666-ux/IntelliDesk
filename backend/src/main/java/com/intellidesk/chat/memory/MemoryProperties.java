package com.intellidesk.chat.memory;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "intellidesk.chat.memory")
public class MemoryProperties {

    /**
     * Maximum number of messages in conversation memory window. Default 10.
     */
    private int maxMessages = 10;

    @PostConstruct
    public void validate() {
        if (maxMessages < 1) {
            throw new IllegalStateException("intellidesk.chat.memory.max-messages must be >= 1");
        }
    }
}