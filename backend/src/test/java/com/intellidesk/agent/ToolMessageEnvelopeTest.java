package com.intellidesk.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellidesk.agent.tool.AgentToolExecutionResult;
import com.intellidesk.agent.tool.AgentToolTraceCollector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolMessageEnvelope")
class ToolMessageEnvelopeTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // ================================================================
    // Success envelope
    // ================================================================

    @Nested
    @DisplayName("success envelope")
    class SuccessEnvelopeTests {

        @Test
        @DisplayName("produces valid JSON with all fields")
        void producesValidJson() {
            AgentToolExecutionResult result = AgentToolExecutionResult.success("Found 5 documents", Map.of("resultCount", 5));
            AgentToolTraceCollector.ToolTraceEntry entry = new AgentToolTraceCollector.ToolTraceEntry(
                    "knowledge_search", Map.of("query", "test"), result, 123L);

            ToolMessageEnvelope envelope = ToolMessageEnvelope.fromTraceEntry(entry, "call_abc123");

            String json = envelope.toJson();
            assertThat(json).isNotEmpty();
            assertThat(json).contains("\"toolCallId\":\"call_abc123\"");
            assertThat(json).contains("\"toolName\":\"knowledge_search\"");
            assertThat(json).contains("\"success\":true");
            assertThat(json).contains("\"result\":\"Found 5 documents\"");
            assertThat(json).contains("\"durationMs\":123");
            assertThat(json).contains("\"errorCode\":null");
            assertThat(json).contains("\"errorMessage\":null");
        }

        @Test
        @DisplayName("includes sanitized arguments")
        void includesSanitizedArguments() {
            AgentToolExecutionResult result = AgentToolExecutionResult.success("ok", Map.of());
            AgentToolTraceCollector.ToolTraceEntry entry = new AgentToolTraceCollector.ToolTraceEntry(
                    "knowledge_search", Map.of("query", "What is Java?", "topK", 5), result, 50L);

            ToolMessageEnvelope envelope = ToolMessageEnvelope.fromTraceEntry(entry, "call_1");

            assertThat(envelope.arguments()).containsEntry("query", "What is Java?");
            assertThat(envelope.arguments()).containsEntry("topK", 5);
        }

        @Test
        @DisplayName("does NOT contain stack trace")
        void doesNotContainStackTrace() {
            AgentToolExecutionResult result = AgentToolExecutionResult.success("ok", Map.of());
            AgentToolTraceCollector.ToolTraceEntry entry = new AgentToolTraceCollector.ToolTraceEntry(
                    "knowledge_search", Map.of(), result, 50L);

            ToolMessageEnvelope envelope = ToolMessageEnvelope.fromTraceEntry(entry, "call_1");
            String json = envelope.toJson();

            assertThat(json).doesNotContain("stackTrace");
            assertThat(json).doesNotContain("throwable");
            assertThat(json).doesNotContain("exception");
        }

        @Test
        @DisplayName("does NOT contain raw provider response")
        void doesNotContainRawProviderResponse() {
            AgentToolExecutionResult result = AgentToolExecutionResult.success("ok", Map.of());
            AgentToolTraceCollector.ToolTraceEntry entry = new AgentToolTraceCollector.ToolTraceEntry(
                    "knowledge_search", Map.of(), result, 50L);

            ToolMessageEnvelope envelope = ToolMessageEnvelope.fromTraceEntry(entry, "call_1");
            String json = envelope.toJson();

            assertThat(json).doesNotContain("providerResponse");
            assertThat(json).doesNotContain("rawResponse");
            assertThat(json).doesNotContain("apiKey");
        }
    }

    // ================================================================
    // Business failure envelope
    // ================================================================

    @Nested
    @DisplayName("business failure envelope")
    class BusinessFailureEnvelopeTests {

        @Test
        @DisplayName("produces valid JSON with error fields")
        void producesErrorJson() {
            AgentToolExecutionResult result = AgentToolExecutionResult.failure("TOOL_ERROR", "Document not found");
            AgentToolTraceCollector.ToolTraceEntry entry = new AgentToolTraceCollector.ToolTraceEntry(
                    "document_detail", Map.of("documentId", 999), result, 45L);

            ToolMessageEnvelope envelope = ToolMessageEnvelope.fromTraceEntry(entry, "call_def456");

            assertThat(envelope.success()).isFalse();
            assertThat(envelope.errorCode()).isEqualTo("TOOL_ERROR");
            assertThat(envelope.errorMessage()).isEqualTo("Document not found");
            assertThat(envelope.result()).isNull();

            String json = envelope.toJson();
            assertThat(json).contains("\"success\":false");
            assertThat(json).contains("\"errorCode\":\"TOOL_ERROR\"");
            assertThat(json).contains("\"errorMessage\":\"Document not found\"");
        }
    }

    // ================================================================
    // Sanitization
    // ================================================================

    @Nested
    @DisplayName("sanitization")
    class SanitizationTests {

        @Test
        @DisplayName("does NOT contain Authentication context")
        void doesNotContainAuthentication() {
            AgentToolExecutionResult result = AgentToolExecutionResult.success("ok", Map.of());
            AgentToolTraceCollector.ToolTraceEntry entry = new AgentToolTraceCollector.ToolTraceEntry(
                    "knowledge_search", Map.of("query", "test"), result, 50L);

            ToolMessageEnvelope envelope = ToolMessageEnvelope.fromTraceEntry(entry, "call_1");
            String json = envelope.toJson();

            assertThat(json).doesNotContain("authentication");
            assertThat(json).doesNotContain("password");
            assertThat(json).doesNotContain("token");
            assertThat(json).doesNotContain("secret");
            assertThat(json).doesNotContain("credential");
        }

        @Test
        @DisplayName("does NOT contain ToolExecutionContext")
        void doesNotContainToolExecutionContext() {
            AgentToolExecutionResult result = AgentToolExecutionResult.success("ok", Map.of());
            AgentToolTraceCollector.ToolTraceEntry entry = new AgentToolTraceCollector.ToolTraceEntry(
                    "knowledge_search", Map.of("query", "test"), result, 50L);

            ToolMessageEnvelope envelope = ToolMessageEnvelope.fromTraceEntry(entry, "call_1");
            String json = envelope.toJson();

            assertThat(json).doesNotContain("ToolExecutionContext");
            assertThat(json).doesNotContain("userId");
            assertThat(json).doesNotContain("workspaceId");
            assertThat(json).doesNotContain("conversationId");
        }

        @Test
        @DisplayName("does NOT contain system prompt")
        void doesNotContainSystemPrompt() {
            AgentToolExecutionResult result = AgentToolExecutionResult.success("ok", Map.of());
            AgentToolTraceCollector.ToolTraceEntry entry = new AgentToolTraceCollector.ToolTraceEntry(
                    "knowledge_search", Map.of("query", "test"), result, 50L);

            ToolMessageEnvelope envelope = ToolMessageEnvelope.fromTraceEntry(entry, "call_1");
            String json = envelope.toJson();

            assertThat(json).doesNotContain("systemPrompt");
            assertThat(json).doesNotContain("system_prompt");
        }

        @Test
        @DisplayName("does NOT contain chain-of-thought")
        void doesNotContainChainOfThought() {
            AgentToolExecutionResult result = AgentToolExecutionResult.success("ok", Map.of());
            AgentToolTraceCollector.ToolTraceEntry entry = new AgentToolTraceCollector.ToolTraceEntry(
                    "knowledge_search", Map.of("query", "test"), result, 50L);

            ToolMessageEnvelope envelope = ToolMessageEnvelope.fromTraceEntry(entry, "call_1");
            String json = envelope.toJson();

            assertThat(json).doesNotContain("chainOfThought");
            assertThat(json).doesNotContain("reasoning");
            assertThat(json).doesNotContain("thinking");
        }
    }

    // ================================================================
    // Edge cases
    // ================================================================

    @Nested
    @DisplayName("edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("handles null toolCallId")
        void handlesNullToolCallId() {
            AgentToolExecutionResult result = AgentToolExecutionResult.success("ok", Map.of());
            AgentToolTraceCollector.ToolTraceEntry entry = new AgentToolTraceCollector.ToolTraceEntry(
                    "knowledge_search", Map.of(), result, 50L);

            ToolMessageEnvelope envelope = ToolMessageEnvelope.fromTraceEntry(entry, null);

            String json = envelope.toJson();
            assertThat(json).contains("\"toolCallId\":null");
        }

        @Test
        @DisplayName("handles null arguments")
        void handlesNullArguments() {
            AgentToolExecutionResult result = AgentToolExecutionResult.success("ok", Map.of());
            AgentToolTraceCollector.ToolTraceEntry entry = new AgentToolTraceCollector.ToolTraceEntry(
                    "knowledge_search", null, result, 50L);

            ToolMessageEnvelope envelope = ToolMessageEnvelope.fromTraceEntry(entry, "call_1");

            assertThat(envelope.arguments()).isEmpty();
        }

        @Test
        @DisplayName("handles null result metadata")
        void handlesNullResultMetadata() {
            AgentToolExecutionResult result = AgentToolExecutionResult.success("ok", null);
            AgentToolTraceCollector.ToolTraceEntry entry = new AgentToolTraceCollector.ToolTraceEntry(
                    "knowledge_search", Map.of(), result, 50L);

            ToolMessageEnvelope envelope = ToolMessageEnvelope.fromTraceEntry(entry, "call_1");

            assertThat(envelope.toJson()).isNotEmpty();
        }

        @Test
        @DisplayName("toJson returns non-empty valid JSON")
        void toJsonReturnsValidJson() throws Exception {
            AgentToolExecutionResult result = AgentToolExecutionResult.success("ok", Map.of());
            AgentToolTraceCollector.ToolTraceEntry entry = new AgentToolTraceCollector.ToolTraceEntry(
                    "knowledge_search", Map.of("query", "test"), result, 50L);

            ToolMessageEnvelope envelope = ToolMessageEnvelope.fromTraceEntry(entry, "call_1");
            String json = envelope.toJson();

            // Verify it's valid JSON
            objectMapper.readTree(json);
            assertThat(json).isNotEmpty();
        }
    }
}