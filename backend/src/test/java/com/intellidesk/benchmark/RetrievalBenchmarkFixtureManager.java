package com.intellidesk.benchmark;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellidesk.embedding.EmbeddingBatchResult;
import com.intellidesk.embedding.EmbeddingService;
import com.intellidesk.evaluation.EvalHashing;
import com.intellidesk.retrieval.RetrievalScope;
import com.intellidesk.retrieval.keyword.ElasticsearchChunkDocument;
import com.intellidesk.retrieval.keyword.ElasticsearchChunkIndex;
import com.intellidesk.retrieval.keyword.ElasticsearchIndexManager;
import com.intellidesk.retrieval.rerank.RerankProperties;
import com.pgvector.PGvector;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Provisions and verifies the dedicated deterministic Retrieval benchmark fixture. */
public final class RetrievalBenchmarkFixtureManager {

    public static final String FIXTURE_VERSION = "retrieval-benchmark-fixture-v1";
    public static final String FIXTURE_HASH =
            "6528792f29b3c910d1a126fcae3f50b6a04b0d0c7b7d06a307dbcf9b23aa69a2";
    public static final String FIXTURE_MANIFEST =
            "docs/evaluation/bench/fixtures/retrieval-fixture-v1.json";
    public static final String OWNER_KEY = "intellidesk-retrieval-benchmark-owner-v1";
    public static final String WORKSPACE_KEY = "intellidesk-retrieval-benchmark-workspace-v1";
    public static final String KNOWLEDGE_BASE_KEY = "intellidesk-retrieval-benchmark-kb-v1";
    public static final String ES_INDEX = "intellidesk-benchmark-retrieval-v1";
    public static final String EMBEDDING_MODEL = "qwen3-embedding:8b";
    public static final int EMBEDDING_DIMENSION = 1536;
    public static final int GENERATION = 1;

    private static final ObjectMapper OM = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;
    private final ElasticsearchClient elasticsearchClient;
    private final ElasticsearchIndexManager indexManager;
    private final ElasticsearchChunkIndex chunkIndex;
    private final EmbeddingService embeddingService;
    private final RerankProperties rerankProperties;

    public RetrievalBenchmarkFixtureManager(
            JdbcTemplate jdbcTemplate,
            ElasticsearchClient elasticsearchClient,
            ElasticsearchIndexManager indexManager,
            ElasticsearchChunkIndex chunkIndex,
            EmbeddingService embeddingService,
            RerankProperties rerankProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.elasticsearchClient = elasticsearchClient;
        this.indexManager = indexManager;
        this.chunkIndex = chunkIndex;
        this.embeddingService = embeddingService;
        this.rerankProperties = rerankProperties;
    }

    public record QuerySpec(
            String id,
            String question,
            Set<String> expectedLogicalChunkIds,
            boolean qualifiedQualityQuestion) {
        public QuerySpec {
            expectedLogicalChunkIds = Set.copyOf(expectedLogicalChunkIds);
        }
    }

    public record FixtureContext(
            String fixtureHash,
            long ownerId,
            long workspaceId,
            long knowledgeBaseId,
            Map<String, Long> documentIds,
            Map<String, Long> chunkIds,
            Map<Long, String> logicalChunkIds,
            Map<Long, String> chunkContentHashes,
            List<QuerySpec> queries
    ) {
        public FixtureContext {
            documentIds = Map.copyOf(documentIds);
            chunkIds = Map.copyOf(chunkIds);
            logicalChunkIds = Map.copyOf(logicalChunkIds);
            chunkContentHashes = Map.copyOf(chunkContentHashes);
            queries = List.copyOf(queries);
        }

        public RetrievalScope scope() {
            return RetrievalScope.of(workspaceId, List.of(knowledgeBaseId));
        }
    }

    public FixtureContext provision(Path projectRoot) throws Exception {
        FixtureSources sources = loadAndVerifySources(projectRoot);
        resetOwnedFixture();
        try {
            long ownerId = insertReturningId(
                    "INSERT INTO sys_user (username, password_hash, email, status) VALUES (?, ?, ?, ?) RETURNING id",
                    OWNER_KEY, "benchmark-fixture-non-login", "retrieval-benchmark-fixture@invalid.local", 1);
            long workspaceId = insertReturningId(
                    "INSERT INTO workspace (name, description, owner_id) VALUES (?, ?, ?) RETURNING id",
                    WORKSPACE_KEY, "Deterministic Retrieval benchmark fixture v1", ownerId);
            jdbcTemplate.update(
                    "INSERT INTO workspace_member (workspace_id, user_id, role) VALUES (?, ?, 'OWNER')",
                    workspaceId, ownerId);
            long knowledgeBaseId = insertReturningId(
                    "INSERT INTO knowledge_base (workspace_id, name, description, chunk_strategy, chunk_size, chunk_overlap, status, created_by) "
                            + "VALUES (?, ?, ?, 'RECURSIVE', 1000, 150, 'ACTIVE', ?) RETURNING id",
                    workspaceId, KNOWLEDGE_BASE_KEY, "Deterministic Retrieval benchmark fixture v1", ownerId);

            List<ChunkSpec> allChunks = sources.documents().stream()
                    .flatMap(document -> document.chunks().stream()).toList();
            EmbeddingBatchResult embeddings = embeddingService.embedDocuments(
                    allChunks.stream().map(ChunkSpec::content).toList());
            if (embeddingService.dimension() != EMBEDDING_DIMENSION
                    || embeddings.getDimension() != EMBEDDING_DIMENSION
                    || embeddings.size() != allChunks.size()) {
                throw new IllegalStateException("PREFLIGHT_FAIL: real fixture embedding dimension/count mismatch");
            }

            Map<String, Long> documentIds = new LinkedHashMap<>();
            Map<String, Long> chunkIds = new LinkedHashMap<>();
            Map<Long, String> logicalChunkIds = new LinkedHashMap<>();
            Map<Long, String> chunkContentHashes = new LinkedHashMap<>();
            List<ElasticsearchChunkDocument> esDocuments = new ArrayList<>();
            int embeddingIndex = 0;

            for (DocumentSpec document : sources.documents()) {
                long documentId = insertReturningId(
                        "INSERT INTO document (knowledge_base_id, original_file_name, file_extension, content_type, file_size, "
                                + "checksum_sha256, bucket_name, object_key, status, chunk_strategy, chunk_size, chunk_overlap, "
                                + "parser_metadata, created_by, completed_at) "
                                + "VALUES (?, ?, 'md', 'text/markdown', ?, ?, 'intellidesk-documents', ?, 'COMPLETED', "
                                + "'RECURSIVE', 1000, 150, ?::jsonb, ?, NOW()) RETURNING id",
                        knowledgeBaseId, document.relativePath(), document.sizeBytes(), document.sourceHash(),
                        "benchmark-fixture/" + FIXTURE_VERSION + "/" + document.relativePath(),
                        OM.writeValueAsString(Map.of(
                                "fixture_version", FIXTURE_VERSION,
                                "logical_document_id", document.logicalId())), ownerId);
                documentIds.put(document.logicalId(), documentId);

                long firstChunkId = -1L;
                for (ChunkSpec chunk : document.chunks()) {
                    float[] vector = embeddings.getEmbeddings().get(embeddingIndex++);
                    if (vector.length != EMBEDDING_DIMENSION) {
                        throw new IllegalStateException("PREFLIGHT_FAIL: fixture embedding vector dimension mismatch");
                    }
                    long chunkId = insertReturningId(
                            "INSERT INTO document_chunk (knowledge_base_id, document_id, chunk_index, content, character_count, "
                                    + "section_path, start_offset, end_offset, source_metadata, embedding, embedding_model, "
                                    + "embedding_generation, embedding_fence_token, embedded_at) "
                                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, 1, NOW()) RETURNING id",
                            knowledgeBaseId, documentId, chunk.ordinal(), chunk.content(), chunk.characterCount(),
                            chunk.sectionPath(), chunk.startOffset(), chunk.endOffset(),
                            OM.writeValueAsString(Map.of(
                                    "fixture_version", FIXTURE_VERSION,
                                    "logical_document_id", document.logicalId(),
                                    "logical_chunk_id", chunk.logicalId(),
                                    "content_sha256", chunk.contentHash())),
                            new PGvector(vector), EMBEDDING_MODEL, GENERATION);
                    if (firstChunkId < 0) {
                        firstChunkId = chunkId;
                    }
                    chunkIds.put(chunk.logicalId(), chunkId);
                    logicalChunkIds.put(chunkId, chunk.logicalId());
                    chunkContentHashes.put(chunkId, chunk.contentHash());

                    ElasticsearchChunkDocument esDocument = new ElasticsearchChunkDocument();
                    esDocument.setChunkId(chunkId);
                    esDocument.setDocumentId(documentId);
                    esDocument.setKnowledgeBaseId(knowledgeBaseId);
                    esDocument.setWorkspaceId(workspaceId);
                    esDocument.setChunkIndex(chunk.ordinal());
                    esDocument.setContent(chunk.content());
                    esDocument.setMetadata(Map.of(
                            "fixture_version", FIXTURE_VERSION,
                            "logical_chunk_id", chunk.logicalId(),
                            "content_sha256", chunk.contentHash()));
                    esDocument.setIndexGeneration(GENERATION);
                    esDocument.setFenceToken(1L);
                    esDocument.setIndexedAt(Instant.now());
                    esDocuments.add(esDocument);
                }

                jdbcTemplate.update(
                        "INSERT INTO document_retrieval_task (document_id, status, generation, attempt_count, max_attempts, "
                                + "fence_token, message_id, embedding_model, embedding_dimension, es_index_name, "
                                + "indexed_chunk_count, requested_by, ready_at) "
                                + "VALUES (?, 'READY', 1, 1, 3, ?, CAST(? AS uuid), ?, 1536, ?, ?, ?, NOW())",
                        documentId, firstChunkId, UUID.randomUUID().toString(), EMBEDDING_MODEL,
                        ES_INDEX, document.chunks().size(), ownerId);
            }

            deleteFixtureIndexIfPresent();
            indexManager.ensureIndex();
            chunkIndex.bulkIndex(esDocuments, 1L);

            return new FixtureContext(
                    FIXTURE_HASH, ownerId, workspaceId, knowledgeBaseId,
                    documentIds, chunkIds, logicalChunkIds, chunkContentHashes, sources.queries());
        } catch (Exception error) {
            resetOwnedFixture();
            throw error;
        }
    }

    public RetrievalFixturePreflightValidator.FixtureFacts inspect(FixtureContext context, Path projectRoot)
            throws Exception {
        boolean fixtureHashMatches;
        try {
            loadAndVerifySources(projectRoot);
            fixtureHashMatches = true;
        } catch (Exception error) {
            fixtureHashMatches = false;
        }

        int workspaceCount = count("SELECT count(*) FROM workspace WHERE id=? AND name=?",
                context.workspaceId(), WORKSPACE_KEY);
        int kbCount = count("SELECT count(*) FROM knowledge_base WHERE id=? AND workspace_id=? AND name=? AND status='ACTIVE'",
                context.knowledgeBaseId(), context.workspaceId(), KNOWLEDGE_BASE_KEY);
        int documents = count("SELECT count(*) FROM document WHERE knowledge_base_id=? AND status='COMPLETED'",
                context.knowledgeBaseId());
        int chunks = count("SELECT count(*) FROM document_chunk WHERE knowledge_base_id=?",
                context.knowledgeBaseId());
        int embedded = count("SELECT count(*) FROM document_chunk WHERE knowledge_base_id=? AND embedding IS NOT NULL "
                        + "AND embedding_model=? AND embedding_fence_token>0",
                context.knowledgeBaseId(), EMBEDDING_MODEL);
        Integer minDimension = jdbcTemplate.queryForObject(
                "SELECT min(vector_dims(embedding)) FROM document_chunk WHERE knowledge_base_id=? AND embedding IS NOT NULL",
                Integer.class, context.knowledgeBaseId());
        Integer maxDimension = jdbcTemplate.queryForObject(
                "SELECT max(vector_dims(embedding)) FROM document_chunk WHERE knowledge_base_id=? AND embedding IS NOT NULL",
                Integer.class, context.knowledgeBaseId());
        int readyTasks = count("SELECT count(*) FROM document_retrieval_task rt JOIN document d ON d.id=rt.document_id "
                        + "WHERE d.knowledge_base_id=? AND rt.status='READY' AND rt.embedding_model=? "
                        + "AND rt.embedding_dimension=1536 AND rt.generation=1",
                context.knowledgeBaseId(), EMBEDDING_MODEL);
        int generationMatches = count("SELECT count(*) FROM document_chunk c JOIN document_retrieval_task rt "
                        + "ON rt.document_id=c.document_id WHERE c.knowledge_base_id=? "
                        + "AND c.embedding_generation=rt.generation AND rt.status='READY'",
                context.knowledgeBaseId());

        boolean esReachable = elasticsearchClient.ping().value();
        boolean esExists = elasticsearchClient.indices().exists(e -> e.index(ES_INDEX)).value();
        int esCount = esExists
                ? Math.toIntExact(elasticsearchClient.count(c -> c.index(ES_INDEX)).count()) : 0;
        boolean esIdentityMatches = false;
        boolean esContentMatches = false;
        if (esExists) {
            SearchResponse<ElasticsearchChunkDocument> response = elasticsearchClient.search(
                    search -> search.index(ES_INDEX).size(RetrievalFixturePreflightValidator.EXPECTED_CHUNKS)
                            .query(query -> query.matchAll(match -> match)),
                    ElasticsearchChunkDocument.class);
            Map<Long, ElasticsearchChunkDocument> observed = new LinkedHashMap<>();
            for (Hit<ElasticsearchChunkDocument> hit : response.hits().hits()) {
                if (hit.source() != null) {
                    observed.put(hit.source().getChunkId(), hit.source());
                }
            }
            esIdentityMatches = observed.keySet().equals(context.logicalChunkIds().keySet())
                    && observed.values().stream().allMatch(document ->
                    document.getWorkspaceId() == context.workspaceId()
                            && document.getKnowledgeBaseId() == context.knowledgeBaseId());
            esContentMatches = observed.size() == context.chunkContentHashes().size()
                    && observed.entrySet().stream().allMatch(entry ->
                    context.chunkContentHashes().get(entry.getKey()).equals(
                            EvalHashing.sha256Hex(entry.getValue().getContent().getBytes(StandardCharsets.UTF_8))));
        }

        Set<String> logicalChunks = context.chunkIds().keySet();
        boolean queryRelationships = context.queries().size() == 69
                && context.queries().stream().filter(QuerySpec::qualifiedQualityQuestion).count() == 60
                && context.queries().stream().filter(query -> !query.qualifiedQualityQuestion()).count() == 9
                && context.queries().stream().allMatch(query ->
                query.qualifiedQualityQuestion()
                        ? !query.expectedLogicalChunkIds().isEmpty()
                            && logicalChunks.containsAll(query.expectedLogicalChunkIds())
                        : query.expectedLogicalChunkIds().isEmpty());

        return new RetrievalFixturePreflightValidator.FixtureFacts(
                Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT 1=1", Boolean.class)),
                workspaceCount == 1,
                kbCount == 1,
                documents,
                chunks,
                embedded,
                minDimension == null ? 0 : minDimension,
                maxDimension == null ? 0 : maxDimension,
                readyTasks,
                generationMatches,
                fixtureHashMatches,
                context.workspaceId() > 0 && context.knowledgeBaseId() > 0,
                queryRelationships,
                esReachable,
                esExists,
                esCount,
                esIdentityMatches,
                esContentMatches);
    }

    public void cleanup() throws Exception {
        resetOwnedFixture();
    }

    /** Calls the real provider identity endpoint and compares it with the frozen quality contract. */
    public Map<String, Object> verifyRerankerProviderIdentity(Path projectRoot) throws Exception {
        Path frozenPath = projectRoot.resolve(
                "scripts/evaluation/local-reranker/identity/reranker_quality_config.json");
        Path frozenHashPath = projectRoot.resolve(
                "scripts/evaluation/local-reranker/identity/reranker_quality_config_hash");
        JsonNode frozen = OM.readTree(frozenPath.toFile());
        String expectedHash = Files.readString(frozenHashPath, StandardCharsets.UTF_8).trim();
        String actualFrozenHash = EvalHashing.sha256Hex(Files.readAllBytes(frozenPath));
        if (!RetrievalBenchmarkHarness.RERANKER_QUALITY_CONFIG_HASH.equals(expectedHash)
                || !expectedHash.equals(actualFrozenHash)) {
            throw new IllegalStateException("PREFLIGHT_FAIL: frozen reranker identity hash mismatch");
        }
        if (!rerankProperties.isEnabled()) {
            throw new IllegalStateException("PREFLIGHT_FAIL: reranker is disabled");
        }
        String baseUrl = rerankProperties.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("PREFLIGHT_FAIL: reranker base URL is missing");
        }
        URI infoUri = URI.create(baseUrl.replaceAll("/+$", "") + "/info");
        HttpRequest request = HttpRequest.newBuilder(infoUri)
                .timeout(java.time.Duration.ofSeconds(rerankProperties.getTimeoutSeconds()))
                .GET()
                .build();
        HttpResponse<byte[]> response = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(rerankProperties.getTimeoutSeconds()))
                .build()
                .send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "PREFLIGHT_FAIL: reranker /info returned HTTP " + response.statusCode());
        }
        JsonNode live = OM.readTree(response.body());
        RetrievalFixturePreflightValidator.validateRerankerIdentity(
                frozen, live, rerankProperties.getModel());

        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("provider_info_uri", infoUri.toString());
        identity.put("provider_info_sha256", EvalHashing.sha256Hex(response.body()));
        identity.put("model_id", live.path("model_id").asText());
        identity.put("reranker_quality_config_hash", expectedHash);
        identity.put("frozen_model_revision_identity",
                frozen.path("model_revision_identity").asText());
        identity.put("frozen_model_artifact_identity",
                frozen.path("model_artifact_identity").asText());
        identity.put("frozen_tokenizer_artifact_identity",
                frozen.path("tokenizer_artifact_identity").asText());
        identity.put("live_identity_fields_match", true);
        identity.put("runtime", OM.convertValue(live.path("runtime"), new TypeReference<Map<String, Object>>() { }));
        return Map.copyOf(identity);
    }

    private FixtureSources loadAndVerifySources(Path projectRoot) throws Exception {
        Path manifestPath = projectRoot.resolve(FIXTURE_MANIFEST);
        JsonNode wrapper = OM.readTree(manifestPath.toFile());
        if (!FIXTURE_VERSION.equals(wrapper.path("fixture_version").asText())
                || !FIXTURE_HASH.equals(wrapper.path("fixture_hash").asText())) {
            throw new IllegalStateException("PREFLIGHT_FAIL: fixture manifest identity mismatch");
        }
        String computedFixtureHash = EvalHashing.sha256Hex(
                EvalHashing.canonicalJson(OM.convertValue(
                        wrapper.path("fixture_definition"), new TypeReference<Map<String, Object>>() { })));
        if (!FIXTURE_HASH.equals(computedFixtureHash)) {
            throw new IllegalStateException("PREFLIGHT_FAIL: fixture canonical hash mismatch");
        }

        JsonNode sourceIdentity = wrapper.path("fixture_definition").path("content_sources");
        Path chunkManifestPath = projectRoot.resolve(sourceIdentity.path("indexed_chunk_manifest_path").asText());
        Path datasetPath = projectRoot.resolve(sourceIdentity.path("evaluation_dataset_path").asText());
        requireFileHash(chunkManifestPath, sourceIdentity.path("indexed_chunk_manifest_sha256").asText());
        requireFileHash(datasetPath, sourceIdentity.path("evaluation_dataset_sha256").asText());

        JsonNode chunkManifest = OM.readTree(chunkManifestPath.toFile());
        List<DocumentSpec> documents = new ArrayList<>();
        for (JsonNode document : chunkManifest.path("documents")) {
            String logicalDocumentId = document.path("logical_document_id").asText();
            List<ChunkSpec> chunks = new ArrayList<>();
            for (JsonNode chunk : document.path("chunks")) {
                int ordinal = chunk.path("chunk_ordinal").asInt();
                chunks.add(new ChunkSpec(
                        logicalDocumentId + "|" + ordinal,
                        ordinal,
                        chunk.path("content").asText(),
                        chunk.path("content_sha256").asText(),
                        chunk.path("character_count").asInt(),
                        chunk.path("section_path").asText(""),
                        chunk.path("start_offset").asInt(),
                        chunk.path("end_offset").asInt()));
            }
            documents.add(new DocumentSpec(
                    logicalDocumentId,
                    document.path("relative_path").asText(),
                    document.path("source_sha256").asText(),
                    Files.size(projectRoot.resolve("docs/evaluation/corpus").resolve(document.path("relative_path").asText())),
                    List.copyOf(chunks)));
        }

        List<Map<String, Object>> dataset = OM.readValue(datasetPath.toFile(), new TypeReference<>() { });
        List<QuerySpec> queries = new ArrayList<>();
        for (Map<String, Object> row : dataset) {
            Set<String> expected = new LinkedHashSet<>();
            Object relevant = row.get("relevant_chunks");
            if (relevant instanceof List<?> list) {
                list.stream().filter(value -> value != null).map(String::valueOf).forEach(expected::add);
            }
            queries.add(new QuerySpec(
                    String.valueOf(row.get("id")), String.valueOf(row.get("question")), expected,
                    Boolean.TRUE.equals(row.get("qualified_quality_question"))));
        }
        if (documents.size() != 14 || documents.stream().mapToInt(document -> document.chunks().size()).sum() != 42
                || queries.size() != 69) {
            throw new IllegalStateException("PREFLIGHT_FAIL: fixture source cardinality mismatch");
        }
        return new FixtureSources(List.copyOf(documents), List.copyOf(queries));
    }

    private void resetOwnedFixture() throws Exception {
        deleteFixtureIndexIfPresent();
        jdbcTemplate.update("DELETE FROM document WHERE knowledge_base_id IN (SELECT kb.id FROM knowledge_base kb "
                + "JOIN workspace w ON w.id=kb.workspace_id JOIN sys_user u ON u.id=w.owner_id "
                + "WHERE kb.name=? AND w.name=? AND u.username=?)",
                KNOWLEDGE_BASE_KEY, WORKSPACE_KEY, OWNER_KEY);
        jdbcTemplate.update("DELETE FROM knowledge_base WHERE name=? AND workspace_id IN (SELECT w.id FROM workspace w "
                + "JOIN sys_user u ON u.id=w.owner_id WHERE w.name=? AND u.username=?)",
                KNOWLEDGE_BASE_KEY, WORKSPACE_KEY, OWNER_KEY);
        jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id IN (SELECT w.id FROM workspace w "
                + "JOIN sys_user u ON u.id=w.owner_id WHERE w.name=? AND u.username=?)",
                WORKSPACE_KEY, OWNER_KEY);
        jdbcTemplate.update("DELETE FROM workspace WHERE name=? AND owner_id IN (SELECT id FROM sys_user WHERE username=?)",
                WORKSPACE_KEY, OWNER_KEY);
        jdbcTemplate.update("DELETE FROM sys_user WHERE username=?", OWNER_KEY);
    }

    private void deleteFixtureIndexIfPresent() throws Exception {
        boolean exists = elasticsearchClient.indices().exists(e -> e.index(ES_INDEX)).value();
        if (exists) {
            elasticsearchClient.indices().delete(delete -> delete.index(ES_INDEX));
        }
    }

    private long insertReturningId(String sql, Object... arguments) {
        Long id = jdbcTemplate.queryForObject(sql, Long.class, arguments);
        if (id == null || id < 1) {
            throw new IllegalStateException("PREFLIGHT_FAIL: fixture insert did not return an ID");
        }
        return id;
    }

    private int count(String sql, Object... arguments) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, arguments);
        return count == null ? 0 : count;
    }

    private static void requireFileHash(Path path, String expected) throws Exception {
        String actual = EvalHashing.sha256Hex(Files.readAllBytes(path));
        if (!expected.equals(actual)) {
            throw new IllegalStateException("PREFLIGHT_FAIL: source hash mismatch: " + path.getFileName());
        }
    }

    private record FixtureSources(List<DocumentSpec> documents, List<QuerySpec> queries) {
    }

    private record DocumentSpec(
            String logicalId, String relativePath, String sourceHash, long sizeBytes, List<ChunkSpec> chunks) {
    }

    private record ChunkSpec(
            String logicalId,
            int ordinal,
            String content,
            String contentHash,
            int characterCount,
            String sectionPath,
            int startOffset,
            int endOffset) {
    }
}
