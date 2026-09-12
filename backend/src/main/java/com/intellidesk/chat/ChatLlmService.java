package com.intellidesk.chat;

import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

/**
 * LLM abstraction wrapper for Chat operations.
 * Business code must not depend directly on OpenAiChatModel.
 */
public interface ChatLlmService {

    /**
     * Synchronous generation.
     */
    ChatResponse generate(Prompt prompt);

    /**
     * Streaming generation. Wave 1: adapter capability + targeted test only.
     * Do NOT start SSE implementation.
     */
    Flux<ChatResponse> generateStream(Prompt prompt);
}