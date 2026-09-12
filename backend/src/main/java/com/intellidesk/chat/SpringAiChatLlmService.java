package com.intellidesk.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Flux;

@Service
public class SpringAiChatLlmService implements ChatLlmService {

    private static final Logger log = LoggerFactory.getLogger(SpringAiChatLlmService.class);

    private final ChatModel chatModel;
    private final ChatLlmProperties properties;

    public SpringAiChatLlmService(ChatModel chatModel, ChatLlmProperties properties) {
        this.chatModel = chatModel;
        this.properties = properties;
    }

    @Override
    public ChatResponse generate(Prompt prompt) {
        assertNotInTransaction();
        try {
            ChatResponse response = chatModel.call(prompt);
            log.debug("Chat provider response: model={}, finishReason={}, usage={}",
                    properties.getModel(),
                    extractFinishReason(response),
                    extractUsage(response));
            return response;
        } catch (ChatLlmException e) {
            throw e;
        } catch (Exception e) {
            throw sanitizeError(e);
        }
    }

    @Override
    public Flux<ChatResponse> generateStream(Prompt prompt) {
        assertNotInTransaction();
        try {
            return chatModel.stream(prompt)
                    .doOnError(e -> log.error("Chat streaming error", e));
        } catch (Exception e) {
            throw sanitizeError(e);
        }
    }

    private void assertNotInTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new ChatLlmException(
                    "CHAT_TX_VIOLATION",
                    "External chat provider must not be called within a database transaction"
            );
        }
    }

    private String extractFinishReason(ChatResponse response) {
        try {
            if (response != null && response.getResults() != null && !response.getResults().isEmpty()) {
                var gen = response.getResults().get(0);
                if (gen.getMetadata() != null && gen.getMetadata().getFinishReason() != null) {
                    return gen.getMetadata().getFinishReason();
                }
            }
        } catch (Exception ignored) {
        }
        return "unknown";
    }

    private String extractUsage(ChatResponse response) {
        try {
            if (response != null && response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                var usage = response.getMetadata().getUsage();
                return String.format("prompt=%d, completion=%d, total=%d",
                        usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
            }
        } catch (Exception ignored) {
        }
        return "n/a";
    }

    private ChatLlmException sanitizeError(Exception e) {
        log.error("Chat provider error", e);

        // Unwrap the cause chain to find HTTP status codes (Spring AI retry wraps exceptions)
        String fullMessage = buildFullMessage(e);

        if (fullMessage.contains("401") || fullMessage.contains("Unauthorized")) {
            return new ChatLlmException("CHAT_AUTH_ERROR", "Chat authentication failed");
        }
        if (fullMessage.contains("429") || fullMessage.contains("rate") || fullMessage.contains("Rate limit")) {
            return new ChatLlmException("CHAT_RATE_LIMITED", "Chat rate limit exceeded");
        }
        if (fullMessage.contains("timeout") || fullMessage.contains("Timeout") || fullMessage.contains("timed out")
                || fullMessage.contains("Read timed out") || fullMessage.contains("connect timed out")) {
            return new ChatLlmException("CHAT_TIMEOUT", "Chat provider timed out");
        }
        if (fullMessage.contains("500") || fullMessage.contains("502") || fullMessage.contains("503")
                || fullMessage.contains("Server Error") || fullMessage.contains("Internal Server Error")) {
            return new ChatLlmException("CHAT_PROVIDER_ERROR", "Chat provider server error");
        }
        if (fullMessage.contains("Connection refused") || fullMessage.contains("connect")
                || fullMessage.contains("UnknownHost") || fullMessage.contains("Connection reset")) {
            return new ChatLlmException("CHAT_PROVIDER_ERROR", "Chat provider connection failed");
        }

        return new ChatLlmException("CHAT_PROVIDER_ERROR", fullMessage);
    }

    /**
     * Build a full message string by traversing the exception cause chain.
     * This ensures we catch HTTP status codes buried in wrapped exceptions
     * (e.g., RetryExhaustedException → HttpClientErrorException).
     */
    private String buildFullMessage(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable current = e;
        while (current != null) {
            if (current.getMessage() != null) {
                if (sb.length() > 0) sb.append(" | ");
                sb.append(current.getMessage());
            }
            current = current.getCause();
        }
        return sb.length() > 0 ? sb.toString() : "Unknown chat error";
    }
}