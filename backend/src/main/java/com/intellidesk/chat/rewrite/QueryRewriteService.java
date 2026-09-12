package com.intellidesk.chat.rewrite;

import com.intellidesk.chat.ChatLlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class QueryRewriteService {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteService.class);

    private static final String REWRITE_SYSTEM_PROMPT = """
            You are a query rewriter. Rewrite the user's current question into a standalone question \
            by incorporating relevant context from the conversation history.

            Rules:
            - Output ONLY the rewritten question, nothing else.
            - If the current question is already standalone, output it as-is.
            - Do NOT answer the question.

            Conversation History:
            ---
            %s
            ---

            Current Question: %s

            Rewritten Question:""";

    private final ChatLlmService chatLlmService;
    private final QueryRewriteProperties properties;

    public QueryRewriteService(ChatLlmService chatLlmService, QueryRewriteProperties properties) {
        this.chatLlmService = chatLlmService;
        this.properties = properties;
    }

    /**
     * Rewrite user query considering conversation history.
     * Returns original query if rewrite is disabled or fails.
     *
     * @param currentQuery the current user query
     * @param history recent conversation history (user/assistant messages)
     * @param enabled whether rewrite is enabled
     * @return rewritten query or original query
     */
    public String rewrite(String currentQuery, List<String> history, boolean enabled) {
        if (currentQuery == null || currentQuery.isBlank()) {
            throw new IllegalArgumentException("currentQuery must not be blank");
        }

        if (!enabled) {
            return currentQuery;
        }

        // Build history text
        String historyText = buildHistoryText(history);

        // Build prompt
        String promptText = String.format(REWRITE_SYSTEM_PROMPT, historyText, currentQuery);
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(promptText),
                new UserMessage(currentQuery)
        ));

        try {
            ChatResponse response = chatLlmService.generate(prompt);
            if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
                log.warn("Query rewrite returned empty response, falling back to original query");
                return currentQuery;
            }

            String rewritten = response.getResults().get(0).getOutput().getText();
            if (rewritten == null || rewritten.isBlank()) {
                log.warn("Query rewrite returned blank output, falling back to original query");
                return currentQuery;
            }

            // Trim and enforce max length
            rewritten = rewritten.trim();
            if (rewritten.length() > properties.getMaxLength()) {
                rewritten = rewritten.substring(0, properties.getMaxLength());
            }

            log.debug("Query rewritten: '{}' -> '{}'", currentQuery, rewritten);
            return rewritten;

        } catch (Exception e) {
            log.warn("Query rewrite failed, falling back to original query. Error: {}", e.getMessage());
            // Do NOT leak provider body/key
            return currentQuery;
        }
    }

    private String buildHistoryText(List<String> history) {
        if (history == null || history.isEmpty()) {
            return "(no conversation history)";
        }

        int maxMessages = properties.getHistoryMessages();
        List<String> recent = history;
        if (history.size() > maxMessages) {
            recent = history.subList(history.size() - maxMessages, history.size());
        }

        return String.join("\n", recent);
    }
}