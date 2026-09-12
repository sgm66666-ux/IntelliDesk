package com.intellidesk.benchmark;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** Fail-closed rules shared by real preflight and measured-operation validation. */
public final class RetrievalFixturePreflightValidator {

    public static final int EXPECTED_DOCUMENTS = 14;
    public static final int EXPECTED_CHUNKS = 42;
    public static final int EXPECTED_EMBEDDING_DIMENSION = 1536;

    private RetrievalFixturePreflightValidator() {
    }

    public record FixtureFacts(
            boolean databaseReachable,
            boolean workspaceExists,
            boolean knowledgeBaseExists,
            int completedDocumentCount,
            int chunkCount,
            int embeddedChunkCount,
            int minEmbeddingDimension,
            int maxEmbeddingDimension,
            int readyTaskCount,
            int generationMatchedChunkCount,
            boolean fixtureHashMatches,
            boolean scopeMatches,
            boolean queryRelationshipsComplete,
            boolean elasticsearchReachable,
            boolean elasticsearchIndexExists,
            int elasticsearchDocumentCount,
            boolean elasticsearchIdentityMatchesPostgres,
            boolean elasticsearchContentHashesMatch
    ) {
    }

    public static void validateFixture(FixtureFacts facts) {
        require(facts.databaseReachable(), "PostgreSQL benchmark DB unreachable");
        require(facts.workspaceExists(), "expected fixture workspace missing");
        require(facts.knowledgeBaseExists(), "expected fixture knowledge base missing");
        require(facts.completedDocumentCount() == EXPECTED_DOCUMENTS,
                "fixture COMPLETED document count mismatch");
        require(facts.chunkCount() == EXPECTED_CHUNKS, "fixture chunk count mismatch");
        require(facts.embeddedChunkCount() == EXPECTED_CHUNKS, "fixture embeddings missing");
        require(facts.minEmbeddingDimension() == EXPECTED_EMBEDDING_DIMENSION
                        && facts.maxEmbeddingDimension() == EXPECTED_EMBEDDING_DIMENSION,
                "fixture embedding dimension mismatch");
        require(facts.readyTaskCount() == EXPECTED_DOCUMENTS, "fixture READY task count mismatch");
        require(facts.generationMatchedChunkCount() == EXPECTED_CHUNKS,
                "fixture embedding/task generation mismatch");
        require(facts.fixtureHashMatches(), "fixture hash mismatch");
        require(facts.scopeMatches(), "benchmark scope does not match fixture scope");
        require(facts.queryRelationshipsComplete(), "query-to-expected-candidate relationship incomplete");
        require(facts.elasticsearchReachable(), "Elasticsearch unreachable");
        require(facts.elasticsearchIndexExists(), "fixture Elasticsearch index missing");
        require(facts.elasticsearchDocumentCount() == EXPECTED_CHUNKS,
                "fixture Elasticsearch document count mismatch");
        require(facts.elasticsearchIdentityMatchesPostgres(),
                "Elasticsearch candidate identity does not match PostgreSQL authority");
        require(facts.elasticsearchContentHashesMatch(),
                "Elasticsearch content does not match frozen fixture");
    }

    public static void validatePath(
            RetrievalBenchmarkHarness.BenchmarkMode mode,
            RetrievalBenchmarkPathEvidence evidence,
            int finalResultCount) {
        require(evidence != null && evidence.observed(), "provider path probe did not observe operation");
        require(evidence.candidateCountBeforeHydration() > 0, "candidate count before hydration is zero");
        require(evidence.hydratedResultCount() > 0, "authoritative hydration returned zero results");
        require(finalResultCount > 0, "final result count is zero");

        switch (mode) {
            case VECTOR -> require(evidence.vectorCandidateCount() > 0, "VECTOR returned no candidates");
            case BM25 -> require(evidence.bm25CandidateCount() > 0, "BM25 returned no candidates");
            case HYBRID -> {
                require(evidence.vectorCandidateCount() > 0, "HYBRID vector input is empty");
                require(evidence.bm25CandidateCount() > 0, "HYBRID BM25 input is empty");
                require(evidence.hybridCandidateCount() > 0, "HYBRID fusion output is empty");
            }
            case RERANK -> {
                require(evidence.vectorCandidateCount() > 0, "RERANK vector input is empty");
                require(evidence.bm25CandidateCount() > 0, "RERANK BM25 input is empty");
                require(evidence.hybridCandidateCount() > 0, "RERANK hybrid input is empty");
                require(evidence.rerankCandidateCount() > 0, "RERANK candidate input is empty");
                require(evidence.rerankExecuted(), "rerank enabled but provider was not called");
                require(evidence.rerankProviderCallCount() == 1,
                        "reranker provider call count must equal one per operation");
                require(evidence.postRerankHydratedResultCount() > 0,
                        "reranker provider called but post-rerank hydration is empty");
            }
        }
    }

    /** Validates every identity field that the real local gateway exposes at runtime. */
    public static void validateRerankerIdentity(JsonNode frozen, JsonNode live, String configuredModel) {
        require(frozen != null && frozen.isObject(), "frozen reranker identity is missing");
        require(live != null && live.isObject(), "live reranker identity is missing");
        require(configuredModel != null && !configuredModel.isBlank(), "configured reranker model is missing");
        require(configuredModel.equals(frozen.path("model_id").asText()),
                "configured reranker model does not match frozen identity");
        for (String field : List.of(
                "model_id", "inference_stack", "max_length", "truncation_policy",
                "normalization", "score_semantics", "ranking_direction", "tie_break_policy",
                "precision", "inference_mode", "input_pair_semantics")) {
            require(frozen.has(field), "frozen reranker identity field missing: " + field);
            require(live.has(field), "live reranker identity field missing: " + field);
            require(frozen.get(field).equals(live.get(field)),
                    "live reranker identity mismatch: " + field);
        }
        JsonNode runtime = live.path("runtime");
        require(runtime.isObject(), "live reranker runtime identity is missing");
        require("eval".equals(runtime.path("model_training_mode").asText()),
                "live reranker model is not in eval mode");
        require(runtime.path("model_dtype").asText().contains("float32"),
                "live reranker precision is not FP32");
        require(!runtime.path("torch_version").asText().isBlank(),
                "live reranker torch identity is missing");
        require(!runtime.path("transformers_version").asText().isBlank(),
                "live reranker transformers identity is missing");
        require(!runtime.path("flagembedding_version").asText().isBlank(),
                "live reranker FlagEmbedding identity is missing");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("PROVIDER_PATH_CONFORMANCE_FAIL: " + message);
        }
    }
}
