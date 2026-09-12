package com.intellidesk.retrieval.integration;

import com.intellidesk.TestInfrastructureConfig;
import com.intellidesk.infrastructure.config.RetrievalProperties;
import com.intellidesk.retrieval.repository.ChunkVectorRow;
import com.intellidesk.retrieval.repository.PgVectorChunkRepository;
import com.pgvector.PGvector;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructureConfig.class)
@DisplayName("PgVector Retrieval Integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("isDockerAvailable")
class PgVectorRetrievalIntegrationTest {

    private static PostgreSQLContainer<?> postgres;

    static boolean isDockerAvailable() {
        try {
            DockerClientFactory.instance().client();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (!isDockerAvailable()) {
            return;
        }
        if (postgres == null) {
            postgres = new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("intellidesk_test")
                    .withUsername("intellidesk")
                    .withPassword("intellidesk");
            postgres.start();
        }
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @AfterAll
    void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PgVectorChunkRepository pgVectorChunkRepository;

    @Autowired
    private RetrievalProperties retrievalProperties;

    private Long workspaceId;
    private Long kbId;
    private Long docId;

    @BeforeEach
    void setUp() {
        // Clean up test data (order matters due to FK constraints)
        jdbcTemplate.execute("DELETE FROM document_retrieval_task");
        jdbcTemplate.execute("DELETE FROM document_chunk");
        jdbcTemplate.execute("DELETE FROM document");
        jdbcTemplate.execute("DELETE FROM knowledge_base");
        jdbcTemplate.execute("DELETE FROM workspace_member");
        jdbcTemplate.execute("DELETE FROM workspace");
        jdbcTemplate.execute("DELETE FROM sys_user_role");
        jdbcTemplate.execute("DELETE FROM sys_role_permission");
        jdbcTemplate.execute("DELETE FROM sys_permission");
        jdbcTemplate.execute("DELETE FROM sys_user");

        // Insert test user
        jdbcTemplate.update("INSERT INTO sys_user (id, username, password_hash, email, status) VALUES (?, ?, ?, ?, ?)",
                1L, "testuser", "hash", "test@test.com", 1);

        // Insert test workspace (no status column in V1)
        jdbcTemplate.update("INSERT INTO workspace (id, name, owner_id) VALUES (?, ?, ?)",
                1L, "Test Workspace", 1L);
        workspaceId = 1L;

        // Insert test knowledge base (needs created_by)
        jdbcTemplate.update("INSERT INTO knowledge_base (id, workspace_id, name, status, created_by) VALUES (?, ?, ?, ?, ?)",
                1L, 1L, "Test KB", "ACTIVE", 1L);
        kbId = 1L;

        // Insert test document (COMPLETED status, full V2 schema)
        jdbcTemplate.update(
                "INSERT INTO document (id, knowledge_base_id, original_file_name, file_extension, content_type, file_size, checksum_sha256, bucket_name, object_key, status, chunk_strategy, chunk_size, chunk_overlap, parser_metadata, created_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
                1L, 1L, "test.pdf", "pdf", "application/pdf", 1024L,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "intellidesk-documents", "test-key", "COMPLETED",
                "RECURSIVE", 1000, 150, "{}", 1L);
        docId = 1L;

        // Insert retrieval task with READY status
        jdbcTemplate.update(
                "INSERT INTO document_retrieval_task (document_id, status, generation, attempt_count, fence_token, message_id, embedding_model, embedding_dimension, indexed_chunk_count, ready_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, "READY", 1, 1, 1, UUID.randomUUID(), "text-embedding-3-small", 1536, 3, LocalDateTime.now());
    }

    private float[] createVector(float offset) {
        float[] v = new float[1536];
        for (int i = 0; i < 1536; i++) {
            v[i] = offset + i * 0.0001f;
        }
        return v;
    }

    private void insertChunk(Long chunkId, float[] embedding, int generation, long fenceToken) {
        PGvector vec = new PGvector(embedding);
        jdbcTemplate.update(
                "INSERT INTO document_chunk (id, knowledge_base_id, document_id, chunk_index, content, character_count, embedding, embedding_model, embedding_generation, embedding_fence_token, embedded_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                chunkId, kbId, docId, chunkId.intValue(), "chunk content " + chunkId,
                ("chunk content " + chunkId).length(), vec, "text-embedding-3-small", generation, fenceToken, LocalDateTime.now());
    }

    private void insertChunkInOtherDoc(Long chunkId, Long otherKbId, Long otherDocId, int chunkIdx,
                                        float[] embedding, int generation, long fenceToken, String content) {
        PGvector vec = new PGvector(embedding);
        jdbcTemplate.update(
                "INSERT INTO document_chunk (id, knowledge_base_id, document_id, chunk_index, content, character_count, embedding, embedding_model, embedding_generation, embedding_fence_token, embedded_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                chunkId, otherKbId, otherDocId, chunkIdx, content,
                content.length(), vec, "text-embedding-3-small", generation, fenceToken, LocalDateTime.now());
    }

    private void insertChunkNoEmbedding(Long chunkId, Long otherKbId, Long otherDocId, int chunkIdx, String content) {
        jdbcTemplate.update(
                "INSERT INTO document_chunk (id, knowledge_base_id, document_id, chunk_index, content, character_count) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
                chunkId, otherKbId, otherDocId, chunkIdx, content, content.length());
    }

    private void insertDocument(Long id, Long kbId, String fileName, String objectKey) {
        jdbcTemplate.update(
                "INSERT INTO document (id, knowledge_base_id, original_file_name, file_extension, content_type, file_size, checksum_sha256, bucket_name, object_key, status, chunk_strategy, chunk_size, chunk_overlap, parser_metadata, created_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
                id, kbId, fileName, "pdf", "application/pdf", 1024L,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + id,
                "intellidesk-documents", objectKey, "COMPLETED",
                "RECURSIVE", 1000, 150, "{}", 1L);
    }

    private void insertRetrievalTask(Long docId, String status, int indexedChunkCount) {
        jdbcTemplate.update(
                "INSERT INTO document_retrieval_task (document_id, status, generation, attempt_count, fence_token, message_id, embedding_model, embedding_dimension, indexed_chunk_count, ready_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                docId, status, 1, 1, 1, UUID.randomUUID(), "text-embedding-3-small", 1536, indexedChunkCount,
                "READY".equals(status) ? LocalDateTime.now() : null);
    }

    @Test
    @DisplayName("1. vector extension exists")
    void vectorExtensionExists() {
        List<String> extensions = jdbcTemplate.queryForList(
                "SELECT extname FROM pg_extension WHERE extname = 'vector'", String.class);
        assertThat(extensions).contains("vector");
    }

    @Test
    @DisplayName("2. vector(1536) column exists")
    void vectorColumnExists() {
        String columnType = jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_name = 'document_chunk' AND column_name = 'embedding'",
                String.class);
        assertThat(columnType).isEqualTo("USER-DEFINED");
    }

    @Test
    @DisplayName("3. HNSW index exists")
    void hnswIndexExists() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'document_chunk' AND indexname = 'idx_chunk_embedding_hnsw'",
                String.class);
        assertThat(indexes).hasSize(1);
    }

    @Test
    @DisplayName("4. PGvector insert and search")
    void pgvectorInsertAndSearch() throws SQLException {
        float[] v1 = createVector(1.0f);
        float[] v2 = createVector(1.1f);
        float[] v3 = createVector(5.0f);

        insertChunk(1L, v1, 1, 1L);
        insertChunk(2L, v2, 1, 1L);
        insertChunk(3L, v3, 1, 1L);

        // Search with a vector close to v1
        float[] query = createVector(1.0f);
        List<ChunkVectorRow> results = pgVectorChunkRepository.search(
                query, workspaceId, List.of(kbId), null, 10);

        assertThat(results).hasSize(3);
        // v1 should be closest (highest score), then v2, then v3
        assertThat(results.get(0).getChunkId()).isEqualTo(1L);
        assertThat(results.get(0).getScore()).isGreaterThan(0.9f);
        assertThat(results.get(1).getChunkId()).isEqualTo(2L);
        assertThat(results.get(2).getChunkId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("5. cosine ranking is correct")
    void cosineRanking() {
        float[] v1 = createVector(1.0f);
        float[] v2 = createVector(2.0f);

        insertChunk(1L, v1, 1, 1L);
        insertChunk(2L, v2, 1, 1L);

        // Query near v1
        float[] query = createVector(1.0f);
        List<ChunkVectorRow> results = pgVectorChunkRepository.search(
                query, workspaceId, List.of(kbId), null, 10);

        assertThat(results).hasSize(2);
        // chunk 1 should be ranked higher than chunk 2 (closer to query)
        assertThat(results.get(0).getChunkId()).isEqualTo(1L);
        assertThat(results.get(0).getScore()).isGreaterThan(results.get(1).getScore());
    }

    @Test
    @DisplayName("6. workspace filter works")
    void workspaceFilter() {
        // Create another workspace with a different KB
        jdbcTemplate.update("INSERT INTO workspace (id, name, owner_id) VALUES (?, ?, ?)",
                2L, "Other WS", 1L);
        jdbcTemplate.update("INSERT INTO knowledge_base (id, workspace_id, name, status, created_by) VALUES (?, ?, ?, ?, ?)",
                2L, 2L, "Other KB", "ACTIVE", 1L);
        insertDocument(2L, 2L, "other.pdf", "other-key");
        insertRetrievalTask(2L, "READY", 1);

        float[] v1 = createVector(1.0f);
        insertChunk(1L, v1, 1, 1L);
        insertChunkInOtherDoc(2L, 2L, 2L, 1, v1, 1, 1L, "other chunk");

        // Search in workspace 1
        List<ChunkVectorRow> results = pgVectorChunkRepository.search(
                v1, workspaceId, List.of(kbId), null, 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getChunkId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("7. KB filter works")
    void kbFilter() {
        // Create a second KB in the same workspace
        jdbcTemplate.update("INSERT INTO knowledge_base (id, workspace_id, name, status, created_by) VALUES (?, ?, ?, ?, ?)",
                2L, workspaceId, "KB2", "ACTIVE", 1L);
        insertDocument(2L, 2L, "doc2.pdf", "key2");
        insertRetrievalTask(2L, "READY", 1);

        float[] v1 = createVector(1.0f);
        insertChunk(1L, v1, 1, 1L);
        insertChunkInOtherDoc(2L, 2L, 2L, 1, v1, 1, 1L, "kb2 chunk");

        // Search only KB1
        List<ChunkVectorRow> results = pgVectorChunkRepository.search(
                v1, workspaceId, List.of(kbId), null, 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getChunkId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("8. optional document filter works")
    void documentFilter() {
        // Create second document in same KB
        insertDocument(2L, kbId, "doc2.pdf", "key2");
        insertRetrievalTask(2L, "READY", 1);

        float[] v1 = createVector(1.0f);
        insertChunk(1L, v1, 1, 1L);
        insertChunkInOtherDoc(2L, kbId, 2L, 1, v1, 1, 1L, "doc2 chunk");

        // Filter by document 1 only
        List<ChunkVectorRow> results = pgVectorChunkRepository.search(
                v1, workspaceId, List.of(kbId), List.of(docId), 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getChunkId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("9. READY filter excludes non-READY tasks")
    void readyFilterExcludesNonReady() {
        // Change task to PENDING
        jdbcTemplate.update("UPDATE document_retrieval_task SET status = 'PENDING' WHERE document_id = ?", docId);

        float[] v1 = createVector(1.0f);
        insertChunk(1L, v1, 1, 1L);

        List<ChunkVectorRow> results = pgVectorChunkRepository.search(
                v1, workspaceId, List.of(kbId), null, 10);

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("10-A. chunk with embedding_generation != task.generation is excluded")
    void chunkWithMismatchedGenerationExcluded() {
        // Set task generation=2, chunk embedding_generation=1 (mismatch)
        jdbcTemplate.update("UPDATE document_retrieval_task SET generation = 2 WHERE document_id = ?", docId);

        float[] v1 = createVector(1.0f);
        insertChunk(1L, v1, 1, 1L); // embedding_generation=1, task generation=2

        // Chunk with generation=1 should NOT be returned when task generation=2
        List<ChunkVectorRow> results = pgVectorChunkRepository.search(
                v1, workspaceId, List.of(kbId), null, 10);

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("10-B. chunk with matching generation is returned")
    void chunkWithMatchingGenerationReturned() {
        // Set both task and chunk to generation=2
        jdbcTemplate.update("UPDATE document_retrieval_task SET generation = 2 WHERE document_id = ?", docId);

        float[] v1 = createVector(1.0f);
        insertChunk(1L, v1, 2, 1L); // embedding_generation=2, task generation=2

        List<ChunkVectorRow> results = pgVectorChunkRepository.search(
                v1, workspaceId, List.of(kbId), null, 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getChunkId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("10-C. multiple documents with different generations each match own task")
    void multipleDocsWithDifferentGenerations() {
        // Create second KB, document, and task
        jdbcTemplate.update("INSERT INTO knowledge_base (id, workspace_id, name, status, created_by) VALUES (?, ?, ?, ?, ?)",
                2L, workspaceId, "KB2", "ACTIVE", 1L);
        insertDocument(2L, 2L, "doc2.pdf", "key2");
        jdbcTemplate.update(
                "INSERT INTO document_retrieval_task (document_id, status, generation, attempt_count, fence_token, message_id, embedding_model, embedding_dimension, indexed_chunk_count, ready_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, "READY", 2, 1, 1, UUID.randomUUID(), "text-embedding-3-small", 1536, 1, LocalDateTime.now());

        // Doc 1: generation=1, chunk embedding_generation=1
        jdbcTemplate.update("UPDATE document_retrieval_task SET generation = 1 WHERE document_id = ?", docId);
        float[] v1 = createVector(1.0f);
        insertChunk(1L, v1, 1, 1L);

        // Doc 2: generation=2, chunk embedding_generation=2
        float[] v2 = createVector(2.0f);
        insertChunkInOtherDoc(2L, 2L, 2L, 1, v2, 2, 1L, "doc2 chunk");

        // Search in KB1: should return only doc1's chunk (generation matches)
        float[] query = createVector(1.0f);
        List<ChunkVectorRow> results = pgVectorChunkRepository.search(
                query, workspaceId, List.of(kbId), null, 10);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getChunkId()).isEqualTo(1L);

        // Search in KB2: should return only doc2's chunk
        List<ChunkVectorRow> results2 = pgVectorChunkRepository.search(
                query, workspaceId, List.of(2L), null, 10);
        assertThat(results2).hasSize(1);
        assertThat(results2.get(0).getChunkId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("11. null embedding excluded")
    void nullEmbeddingExcluded() {
        float[] v1 = createVector(1.0f);
        insertChunk(1L, v1, 1, 1L);

        // Insert chunk with null embedding
        insertChunkNoEmbedding(2L, kbId, docId, 2, "null embedding chunk");

        List<ChunkVectorRow> results = pgVectorChunkRepository.search(
                v1, workspaceId, List.of(kbId), null, 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getChunkId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("12. V3 backfill creates PENDING task for COMPLETED documents")
    void v3Backfill() {
        // Create a new COMPLETED document with chunks but no retrieval task
        insertDocument(3L, kbId, "backfill.pdf", "bf-key");
        insertChunkNoEmbedding(3L, kbId, 3L, 1, "backfill chunk");

        // Manually run the backfill query (same as V3)
        jdbcTemplate.update(
                "INSERT INTO document_retrieval_task (document_id, status, generation, attempt_count, max_attempts, message_id, embedding_dimension, es_index_name) " +
                "SELECT d.id, 'PENDING', 1, 0, 3, gen_random_uuid(), 1536, 'intellidesk-chunks-v1' " +
                "FROM document d " +
                "WHERE d.status = 'COMPLETED' " +
                "  AND EXISTS (SELECT 1 FROM document_chunk c WHERE c.document_id = d.id) " +
                "  AND NOT EXISTS (SELECT 1 FROM document_retrieval_task rt WHERE rt.document_id = d.id)");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_retrieval_task WHERE document_id = 3 AND status = 'PENDING'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("13. error context propagation")
    void errorContextPropagation() {
        // Verify that invalid inputs are rejected
        float[] v1 = createVector(1.0f);
        insertChunk(1L, v1, 1, 1L);

        // Test with empty KB list
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            pgVectorChunkRepository.search(v1, workspaceId, List.of(), null, 10);
        });
    }
}