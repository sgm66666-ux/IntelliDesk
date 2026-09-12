package com.intellidesk.evaluation;

import com.intellidesk.retrieval.RetrievalResult;
import com.intellidesk.retrieval.ScoreType;
import com.intellidesk.retrieval.rerank.DashScopeCompatibleRerankClient;
import com.intellidesk.retrieval.rerank.RerankClient;
import com.intellidesk.retrieval.rerank.RerankService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 8 Wave 1 — Production-path reranker smoke test.
 *
 * Verifies the real quality path:
 *   RerankService -> RerankClient -> DashScopeCompatibleRerankClient
 *   -> HTTP 127.0.0.1:18182
 *   -> local real reranker gateway
 *   -> BAAI/bge-reranker-v2-m3 via FlagEmbedding::FlagReranker
 *   -> finite relevance scores
 *   -> ranked result
 *
 * Skipped if the local gateway is not reachable.
 */
@EnabledIf("gatewayAvailable")
class RerankerProductionPathSmokeTest {

    private static final String GATEWAY_BASE_URL = "http://127.0.0.1:18182";
    private static final String API_KEY = "local-placeholder";
    private static final String MODEL = "BAAI/bge-reranker-v2-m3";

    static boolean gatewayAvailable() {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GATEWAY_BASE_URL + "/health"))
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
    void productionPathReranksRelevantAboveIrrelevant() {
        RerankClient client = new DashScopeCompatibleRerankClient(
                GATEWAY_BASE_URL, API_KEY, MODEL, Duration.ofSeconds(30));
        RerankService service = new RerankService(client);

        String query = "员工如何申请报销";
        RetrievalResult relevant = result(1L, "员工填写报销单并提交给直属主管审批。");
        RetrievalResult irrelevant = result(2L, "公司年会定于每年第一季度举行。");

        List<RetrievalResult> reranked = service.rerank(query, List.of(relevant, irrelevant), 2);

        assertThat(reranked).hasSize(2);
        assertThat(reranked.get(0).getChunkId()).isEqualTo(1L);
        assertThat(reranked.get(0).getScoreType()).isEqualTo(ScoreType.RERANKED);
        assertThat(reranked.get(0).getScore()).isFinite().isGreaterThan(reranked.get(1).getScore());
        assertThat(reranked.get(1).getChunkId()).isEqualTo(2L);

        System.out.println("RERANKER PRODUCTION PATH SMOKE PASS: model=" + MODEL
                + ", top_score=" + reranked.get(0).getScore()
                + ", bottom_score=" + reranked.get(1).getScore());
    }

    private RetrievalResult result(long chunkId, String content) {
        RetrievalResult r = new RetrievalResult(chunkId, null, null, content, 0.0f, ScoreType.RERANKED, 0, null);
        r.setContent(content);
        return r;
    }
}
