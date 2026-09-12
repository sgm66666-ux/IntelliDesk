package com.intellidesk.agent;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "intellidesk.agent.loop")
public class AgentLoopConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopConfig.class);

    private int maxSteps = 10;
    private int timeoutSeconds = 120;
    private String systemPrompt = """
            You are an intelligent enterprise assistant with access to tools.
            Use the tools to retrieve information from the knowledge base when needed.
            Provide accurate answers based on the tool results.
            """;

    @PostConstruct
    public void validate() {
        if (maxSteps < 1 || maxSteps > 50) {
            throw new IllegalStateException("intellidesk.agent.loop.max-steps must be between 1 and 50");
        }
        if (timeoutSeconds < 10 || timeoutSeconds > 600) {
            throw new IllegalStateException("intellidesk.agent.loop.timeout-seconds must be between 10 and 600");
        }
        log.info("AgentLoopConfig initialized: maxSteps={}, timeoutSeconds={}", maxSteps, timeoutSeconds);
    }
}