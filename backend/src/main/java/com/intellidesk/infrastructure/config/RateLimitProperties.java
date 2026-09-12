package com.intellidesk.infrastructure.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "intellidesk.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;
    private String namespace = "intellidesk:dev";
    private int windowSeconds = 60;

    private int agent = 10;
    private int chat = 30;
    private int retrieval = 60;
    private int upload = 20;
    private int auth = 10;

    @PostConstruct
    public void validate() {
        if (windowSeconds <= 0) {
            throw new IllegalStateException("rate-limit.window-seconds must be > 0");
        }
        if (agent <= 0 || chat <= 0 || retrieval <= 0 || upload <= 0 || auth <= 0) {
            throw new IllegalStateException("All rate-limit categories must have limit > 0");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public int getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(int windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    public int getAgent() {
        return agent;
    }

    public void setAgent(int agent) {
        this.agent = agent;
    }

    public int getChat() {
        return chat;
    }

    public void setChat(int chat) {
        this.chat = chat;
    }

    public int getRetrieval() {
        return retrieval;
    }

    public void setRetrieval(int retrieval) {
        this.retrieval = retrieval;
    }

    public int getUpload() {
        return upload;
    }

    public void setUpload(int upload) {
        this.upload = upload;
    }

    public int getAuth() {
        return auth;
    }

    public void setAuth(int auth) {
        this.auth = auth;
    }

    public int getLimit(String category) {
        return switch (category) {
            case "agent" -> agent;
            case "chat" -> chat;
            case "retrieval" -> retrieval;
            case "upload" -> upload;
            case "auth" -> auth;
            default -> throw new IllegalArgumentException("Unknown rate limit category: " + category);
        };
    }
}