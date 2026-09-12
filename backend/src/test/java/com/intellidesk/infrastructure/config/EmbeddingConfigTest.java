package com.intellidesk.infrastructure.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellidesk.embedding.EmbeddingException;
import com.intellidesk.embedding.SpringAiEmbeddingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AMENDMENT-A tests: EmbeddingProperties.dimension must propagate to
 * OpenAiEmbeddingOptions.dimensions and be present in the HTTP request body.
 *
 * These tests are provider-neutral: they do not hardcode Ollama, OpenAI, or any
 * specific model; they only verify that the configured dimension reaches the wire.
 */
@DisplayName("EmbeddingConfig")
class EmbeddingConfigTest {

    private HttpServer server;
    private String baseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> capturedBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", this::handleEmbeddings);
        server.start();
        int port = server.getAddress().getPort();
        baseUrl = "http://127.0.0.1:" + port;
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handleEmbeddings(HttpExchange exchange) throws IOException {
        String requestBody;
        try (InputStream is = exchange.getRequestBody()) {
            requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        capturedBody.set(requestBody);

        JsonNode body = parseJson(requestBody);
        int count;
        if (body.has("input") && body.get("input").isArray()) {
            count = body.get("input").size();
        } else if (body.has("input")) {
            count = 1;
        } else {
            count = 1;
        }

        StringBuilder data = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                data.append(",");
            }
            data.append("{\"object\":\"embedding\",\"embedding\":");
            data.append(vectorJson(1536));
            data.append(",\"index\":").append(i).append("}");
        }

        String response = """
                {
                  "object": "list",
                  "data": [%s],
                  "model": "test-model",
                  "usage": { "prompt_tokens": 4, "total_tokens": 4 }
                }
                """.formatted(data.toString());

        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String vectorJson(int dim) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < dim; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("0.001");
        }
        sb.append("]");
        return sb.toString();
    }

    private EmbeddingProperties properties() {
        EmbeddingProperties p = new EmbeddingProperties();
        p.setApiKey("test-key");
        p.setBaseUrl(baseUrl);
        p.setModel("test-model");
        p.setDimension(1536);
        p.setBatchSize(10);
        return p;
    }

    @Test
    @DisplayName("configured dimension is propagated to OpenAiEmbeddingOptions and sent in HTTP request")
    void configuredDimensionPropagatesToRequest() {
        EmbeddingConfig config = new EmbeddingConfig(properties());
        EmbeddingModel model = config.embeddingModel();

        SpringAiEmbeddingService service = new SpringAiEmbeddingService(model, properties());
        float[] vector = service.embedDocuments(List.of("hello world")).getEmbeddings().get(0);

        assertThat(vector).hasSize(1536);

        JsonNode body = parseCapturedBody();
        assertThat(body.has("dimensions")).isTrue();
        assertThat(body.get("dimensions").asInt()).isEqualTo(1536);
        assertThat(body.get("model").asText()).isEqualTo("test-model");
        assertThat(body.get("input").isArray()).isTrue();
        assertThat(body.get("input")).hasSize(1);
        assertThat(body.get("input").get(0).asText()).isEqualTo("hello world");
    }

    @Test
    @DisplayName("embedDocuments batch sends dimensions=1536 for every batch")
    void embedDocumentsBatchSendsDimensions() {
        EmbeddingConfig config = new EmbeddingConfig(properties());
        EmbeddingModel model = config.embeddingModel();
        SpringAiEmbeddingService service = new SpringAiEmbeddingService(model, properties());

        service.embedDocuments(List.of("first text", "second text"));

        JsonNode body = parseCapturedBody();
        assertThat(body.get("dimensions").asInt()).isEqualTo(1536);
        assertThat(body.get("input").isArray()).isTrue();
        assertThat(body.get("input")).hasSize(2);
    }

    @Test
    @DisplayName("dimension mismatch remains fail-closed when provider returns wrong size")
    void dimensionMismatchFailClosed() {
        EmbeddingConfig config = new EmbeddingConfig(properties());
        EmbeddingModel model = config.embeddingModel();
        SpringAiEmbeddingService service = new SpringAiEmbeddingService(model, properties());

        // Override the server to return 768-dim vectors (simulating a provider that ignores dimensions)
        server.removeContext("/v1/embeddings");
        server.createContext("/v1/embeddings", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            capturedBody.set(requestBody);
            JsonNode body = parseJson(requestBody);
            int count = body.has("input") && body.get("input").isArray() ? body.get("input").size() : 1;

            StringBuilder data = new StringBuilder();
            for (int i = 0; i < count; i++) {
                if (i > 0) data.append(",");
                data.append("{\"object\":\"embedding\",\"embedding\":").append(vectorJson(768))
                        .append(",\"index\":").append(i).append("}");
            }
            String response = """
                    {
                      "object": "list",
                      "data": [%s],
                      "model": "test-model",
                      "usage": { "prompt_tokens": 4, "total_tokens": 4 }
                    }
                    """.formatted(data.toString());
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        assertThatThrownBy(() -> service.embedDocuments(List.of("hello world")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("Expected 1536 dimensions");
    }

    @Test
    @DisplayName("no provider or model specific branching in EmbeddingConfig")
    void noProviderSpecificBranching() throws Exception {
        // The source must not contain hardcoded provider/model checks.
        java.nio.file.Path source = java.nio.file.Path.of("src/main/java/com/intellidesk/infrastructure/config/EmbeddingConfig.java")
                .toAbsolutePath().normalize();
        String content = java.nio.file.Files.readString(source, StandardCharsets.UTF_8).toLowerCase();
        assertThat(content)
                .as("EmbeddingConfig must remain provider-neutral")
                .doesNotContain("if (ollama", "if ollama", "qwen3", "openai.com", "text-embedding-3-small");
    }

    private JsonNode parseCapturedBody() {
        String body = capturedBody.get();
        assertThat(body).isNotNull();
        return parseJson(body);
    }

    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse JSON: " + json, e);
        }
    }
}
