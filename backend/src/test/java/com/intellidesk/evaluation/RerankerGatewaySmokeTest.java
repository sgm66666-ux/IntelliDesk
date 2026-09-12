package com.intellidesk.evaluation;

import com.intellidesk.retrieval.RetrievalResult;
import com.intellidesk.retrieval.ScoreType;
import com.intellidesk.retrieval.rerank.DashScopeCompatibleRerankClient;
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
 * Real local reranker production-path smoke test for Phase 8 Wave 1 AMENDMENT-B.
 *
 * Verifies the production path:
 *   RerankService -> RerankClient -> DashScopeCompatibleRerankClient
 *   -> HTTP 127.0.0.1:18182
 *   -> local FlagEmbedding::FlagReranker gateway
 *   -> BAAI/bge-reranker-v2-m3
 *   -> finite relevance scores
 *   -> ranked result
 *
 * Skipped if the local reranker gateway is not reachable at 127.0.0.1:18182.
 */
@EnabledIf("gatewayAvailable")
class RerankerGatewaySmokeTest {

    private static final String GATEWAY_BASE_URL = "http://127.0.0.1:18182";
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
    void productionPathRerankerReturnsRealScores() {
        DashScopeCompatibleRerankClient client = new DashScopeCompatibleRerankClient(
                GATEWAY_BASE_URL,
                "local-placeholder",
                MODEL,
                Duration.ofSeconds(30));
        RerankService service = new RerankService(client);

        String query = "员工报销需要提交哪些材料";
        List<RetrievalResult> candidates = List.of(
                retrievalResult(0L, "公司团建通知", 0.5f),
                retrievalResult(1L, "报销流程：员工提交报销单、发票和审批单，财务审核后付款。", 0.4f),
                retrievalResult(2L, "差旅政策：国内出差住宿标准每晚不超过500元。", 0.3f)
        );

        List<RetrievalResult> ranked = service.rerank(query, candidates, 3);

        assertThat(ranked).hasSize(3);
        assertThat(ranked.get(0).getContent()).contains("报销");
        assertThat(ranked.get(0).getScore()).isGreaterThan(ranked.get(1).getScore());
        assertThat(ranked.get(1).getScore()).isGreaterThan(ranked.get(2).getScore());
        assertThat(ranked).allMatch(r -> !Double.isNaN(r.getScore()) && !Double.isInfinite(r.getScore()));
        assertThat(ranked).allMatch(r -> r.getScoreType() == ScoreType.RERANKED);

        System.out.println("RERANKER PRODUCTION PATH SMOKE PASS: model=" + MODEL + ", gateway=" + GATEWAY_BASE_URL);
    }

    private RetrievalResult retrievalResult(Long chunkId, String content, float rrfScore) {
        RetrievalResult r = new RetrievalResult(chunkId, null, null, content, rrfScore, ScoreType.RRF, 0, null,
                com.intellidesk.retrieval.RetrievalSource.HYBRID, List.of(), java.util.Collections.emptyMap());
        return r;
    }
}
