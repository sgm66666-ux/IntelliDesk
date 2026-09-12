package com.intellidesk.agent.tool;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "intellidesk.agent")
public class AgentProperties {

    private static final Logger log = LoggerFactory.getLogger(AgentProperties.class);

    private int toolTimeoutSeconds = 30;
    private int toolMaxResultChars = 16384;
    private int toolCorePoolSize = 2;
    private int toolMaxPoolSize = 4;
    private int toolQueueCapacity = 16;

    @PostConstruct
    public void validate() {
        if (toolTimeoutSeconds < 1 || toolTimeoutSeconds > 300) {
            throw new IllegalStateException("intellidesk.agent.tool-timeout-seconds must be between 1 and 300");
        }
        if (toolMaxResultChars < 256 || toolMaxResultChars > 65536) {
            throw new IllegalStateException("intellidesk.agent.tool-max-result-chars must be between 256 and 65536");
        }
        if (toolCorePoolSize < 1 || toolCorePoolSize > 16) {
            throw new IllegalStateException("intellidesk.agent.tool-core-pool-size must be between 1 and 16");
        }
        if (toolMaxPoolSize < toolCorePoolSize || toolMaxPoolSize > 32) {
            throw new IllegalStateException("intellidesk.agent.tool-max-pool-size must be >= core-pool-size and <= 32");
        }
        if (toolQueueCapacity < 4 || toolQueueCapacity > 256) {
            throw new IllegalStateException("intellidesk.agent.tool-queue-capacity must be between 4 and 256");
        }
        log.info("AgentProperties initialized: toolTimeoutSeconds={}, toolMaxResultChars={}, " +
                        "toolCorePoolSize={}, toolMaxPoolSize={}, toolQueueCapacity={}",
                toolTimeoutSeconds, toolMaxResultChars,
                toolCorePoolSize, toolMaxPoolSize, toolQueueCapacity);
    }
}