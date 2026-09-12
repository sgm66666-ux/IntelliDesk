package com.intellidesk.evaluation;

import com.intellidesk.embedding.SpringAiEmbeddingService;
import com.intellidesk.infrastructure.config.EmbeddingConfig;
import com.intellidesk.infrastructure.config.EmbeddingProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.ai.embedding.EmbeddingModel;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Ollama embedding smoke test for Phase 8 Wave 1 AMENDMENT-A.
 *
 * Verifies the production path:
 *   SpringAiEmbeddingService -> EmbeddingModel -> OpenAiEmbeddingModel
 *   -> Ollama /v1/embeddings
 *   -> request dimensions=1536
 *   -> returned vector length=1536
 *
 * Skipped if Ollama is not reachable at 127.0.0.1:11434.
 */
@EnabledIf("ollamaAvailable")
class OllamaEmbeddingSmokeTest {

    private static final String OLLAMA_BASE_URL = "http://127.0.0.1:11434";
    private static final String MODEL = "qwen3-embedding:8b";
    private static final int DIMENSION = 1536;

    static boolean ollamaAvailable() {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OLLAMA_BASE_URL + "/api/tags"))
                    .GET()
                    .timeout(Duration.ofSeconds(3))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && response.body().contains(MODEL);
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void ollamaRealEmbeddingReturns1536Dimensions() {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setApiKey("ollama-placeholder"); // Ollama does not enforce API key
        properties.setBaseUrl(OLLAMA_BASE_URL);
        properties.setModel(MODEL);
        properties.setDimension(DIMENSION);
        properties.setBatchSize(10);

        EmbeddingConfig config = new EmbeddingConfig(properties);
        EmbeddingModel model = config.embeddingModel();
        SpringAiEmbeddingService service = new SpringAiEmbeddingService(model, properties);

        float[] queryVector = service.embedQuery("IntelliDesk 报销流程");
        assertThat(queryVector).hasSize(DIMENSION);

        var batch = service.embedDocuments(List.of(
                "员工提交报销单。",
                "财务审核发票。"
        ));
        assertThat(batch.getEmbeddings()).hasSize(2);
        assertThat(batch.getEmbeddings().get(0)).hasSize(DIMENSION);
        assertThat(batch.getEmbeddings().get(1)).hasSize(DIMENSION);
        assertThat(batch.getModel()).isEqualTo(MODEL);
        assertThat(batch.getDimension()).isEqualTo(DIMENSION);

        System.out.println("OLLAMA REAL EMBEDDING SMOKE PASS: model=" + MODEL + ", dimension=" + DIMENSION);
    }
}
