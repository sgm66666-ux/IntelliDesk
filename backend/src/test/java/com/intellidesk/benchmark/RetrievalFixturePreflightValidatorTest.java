package com.intellidesk.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetrievalFixturePreflightValidatorTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @Test
    void validFixturePasses() {
        assertDoesNotThrow(() -> RetrievalFixturePreflightValidator.validateFixture(validFixture()));
    }

    @Test
    void emptyPostgresFixtureFailsClosed() {
        var valid = validFixture();
        assertFixtureFails(new RetrievalFixturePreflightValidator.FixtureFacts(
                true, false, false, 0, 0, 0, 0, 0, 0, 0,
                true, true, true, true, true, 42, true, true));
    }

    @Test
    void chunksWithoutEmbeddingsFailClosed() {
        var valid = validFixture();
        assertFixtureFails(copy(valid, 0, 1536, 1536, true, 42));
    }

    @Test
    void wrongEmbeddingDimensionFailsClosed() {
        var valid = validFixture();
        assertFixtureFails(copy(valid, 42, 768, 768, true, 42));
    }

    @Test
    void validPostgresButEmptyElasticsearchFailsClosed() {
        var valid = validFixture();
        assertFixtureFails(copy(valid, 42, 1536, 1536, false, 0));
    }

    @Test
    void fixtureHashMismatchFailsClosed() {
        var f = validFixture();
        assertFixtureFails(new RetrievalFixturePreflightValidator.FixtureFacts(
                f.databaseReachable(), f.workspaceExists(), f.knowledgeBaseExists(),
                f.completedDocumentCount(), f.chunkCount(), f.embeddedChunkCount(),
                f.minEmbeddingDimension(), f.maxEmbeddingDimension(), f.readyTaskCount(),
                f.generationMatchedChunkCount(), false, f.scopeMatches(),
                f.queryRelationshipsComplete(), f.elasticsearchReachable(),
                f.elasticsearchIndexExists(), f.elasticsearchDocumentCount(),
                f.elasticsearchIdentityMatchesPostgres(), f.elasticsearchContentHashesMatch()));
    }

    @Test
    void validAllFourProviderPathsPass() {
        assertDoesNotThrow(() -> RetrievalFixturePreflightValidator.validatePath(
                RetrievalBenchmarkHarness.BenchmarkMode.VECTOR, vector(), 1));
        assertDoesNotThrow(() -> RetrievalFixturePreflightValidator.validatePath(
                RetrievalBenchmarkHarness.BenchmarkMode.BM25, bm25(), 1));
        assertDoesNotThrow(() -> RetrievalFixturePreflightValidator.validatePath(
                RetrievalBenchmarkHarness.BenchmarkMode.HYBRID, hybrid(), 1));
        assertDoesNotThrow(() -> RetrievalFixturePreflightValidator.validatePath(
                RetrievalBenchmarkHarness.BenchmarkMode.RERANK, rerank(), 1));
    }

    @Test
    void vectorWithoutCandidatesFailsClosed() {
        assertPathFails(RetrievalBenchmarkHarness.BenchmarkMode.VECTOR,
                evidence(0, 0, 0, 0, 0, 0, false, 0, 0), 0);
    }

    @Test
    void elasticsearchCandidatesButHydrationZeroFailsClosed() {
        assertPathFails(RetrievalBenchmarkHarness.BenchmarkMode.BM25,
                evidence(0, 2, 0, 2, 0, 0, false, 0, 0), 0);
    }

    @Test
    void hybridMissingEitherInputFailsClosed() {
        assertPathFails(RetrievalBenchmarkHarness.BenchmarkMode.HYBRID,
                evidence(2, 0, 2, 2, 2, 0, false, 0, 0), 2);
        assertPathFails(RetrievalBenchmarkHarness.BenchmarkMode.HYBRID,
                evidence(0, 2, 2, 2, 2, 0, false, 0, 0), 2);
    }

    @Test
    void rerankEmptyCandidateInputFailsClosed() {
        assertPathFails(RetrievalBenchmarkHarness.BenchmarkMode.RERANK,
                evidence(2, 2, 0, 0, 0, 0, false, 0, 0), 0);
    }

    @Test
    void rerankEnabledButProviderNotCalledFailsClosed() {
        assertPathFails(RetrievalBenchmarkHarness.BenchmarkMode.RERANK,
                evidence(2, 2, 2, 2, 2, 2, false, 0, 0), 2);
    }

    @Test
    void providerCalledButFinalHydrationEmptyFailsClosed() {
        assertPathFails(RetrievalBenchmarkHarness.BenchmarkMode.RERANK,
                evidence(2, 2, 2, 2, 2, 2, true, 1, 0), 0);
    }

    @Test
    void liveRerankerIdentityMustMatchFrozenContract() throws Exception {
        JsonNode frozen = OM.readTree("""
                {"model_id":"m","inference_stack":"s","max_length":512,
                "truncation_policy":"t","normalization":false,"score_semantics":"x",
                "ranking_direction":"DESCENDING","tie_break_policy":"p","precision":"FP32",
                "inference_mode":"i","input_pair_semantics":"pair"}
                """);
        JsonNode live = OM.readTree("""
                {"model_id":"m","inference_stack":"s","max_length":512,
                "truncation_policy":"t","normalization":false,"score_semantics":"x",
                "ranking_direction":"DESCENDING","tie_break_policy":"p","precision":"FP32",
                "inference_mode":"i","input_pair_semantics":"pair","runtime":{
                "model_training_mode":"eval","model_dtype":"torch.float32","torch_version":"2",
                "transformers_version":"4","flagembedding_version":"1"}}
                """);
        assertDoesNotThrow(() -> RetrievalFixturePreflightValidator.validateRerankerIdentity(
                frozen, live, "m"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) live).put("model_id", "wrong");
        assertThrows(IllegalStateException.class, () ->
                RetrievalFixturePreflightValidator.validateRerankerIdentity(frozen, live, "m"));
    }

    private static RetrievalFixturePreflightValidator.FixtureFacts validFixture() {
        return new RetrievalFixturePreflightValidator.FixtureFacts(
                true, true, true, 14, 42, 42, 1536, 1536, 14, 42,
                true, true, true, true, true, 42, true, true);
    }

    private static RetrievalFixturePreflightValidator.FixtureFacts copy(
            RetrievalFixturePreflightValidator.FixtureFacts f, int embedded,
            int minDimension, int maxDimension, boolean esExists, int esCount) {
        return new RetrievalFixturePreflightValidator.FixtureFacts(
                f.databaseReachable(), f.workspaceExists(), f.knowledgeBaseExists(),
                f.completedDocumentCount(), f.chunkCount(), embedded, minDimension, maxDimension,
                f.readyTaskCount(), f.generationMatchedChunkCount(), f.fixtureHashMatches(),
                f.scopeMatches(), f.queryRelationshipsComplete(), f.elasticsearchReachable(),
                esExists, esCount, f.elasticsearchIdentityMatchesPostgres(),
                f.elasticsearchContentHashesMatch());
    }

    private static RetrievalBenchmarkPathEvidence vector() {
        return evidence(2, 0, 0, 2, 2, 0, false, 0, 0);
    }

    private static RetrievalBenchmarkPathEvidence bm25() {
        return evidence(0, 2, 0, 2, 2, 0, false, 0, 0);
    }

    private static RetrievalBenchmarkPathEvidence hybrid() {
        return evidence(2, 2, 2, 2, 2, 0, false, 0, 0);
    }

    private static RetrievalBenchmarkPathEvidence rerank() {
        return evidence(2, 2, 2, 2, 2, 2, true, 1, 2);
    }

    private static RetrievalBenchmarkPathEvidence evidence(
            int vector, int bm25, int hybrid, int beforeHydration, int hydrated,
            int rerankCandidates, boolean rerankExecuted, int providerCalls, int postRerankHydrated) {
        return new RetrievalBenchmarkPathEvidence(
                true, vector, bm25, hybrid, beforeHydration, hydrated,
                rerankCandidates, rerankExecuted, providerCalls, postRerankHydrated,
                vector > 0 ? List.of(1L) : List.of(),
                bm25 > 0 ? List.of(1L) : List.of(),
                hybrid > 0 ? List.of(1L) : List.of());
    }

    private static void assertFixtureFails(RetrievalFixturePreflightValidator.FixtureFacts facts) {
        assertThrows(IllegalStateException.class,
                () -> RetrievalFixturePreflightValidator.validateFixture(facts));
    }

    private static void assertPathFails(
            RetrievalBenchmarkHarness.BenchmarkMode mode,
            RetrievalBenchmarkPathEvidence evidence,
            int finalCount) {
        assertThrows(IllegalStateException.class,
                () -> RetrievalFixturePreflightValidator.validatePath(mode, evidence, finalCount));
    }
}
