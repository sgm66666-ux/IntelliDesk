package com.intellidesk.retrieval.repository;

import com.intellidesk.infrastructure.config.RetrievalProperties;
import com.pgvector.PGvector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Repository
public class PgVectorChunkRepository {

    private static final Logger log = LoggerFactory.getLogger(PgVectorChunkRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final RetrievalProperties properties;

    public PgVectorChunkRepository(JdbcTemplate jdbcTemplate, RetrievalProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    /**
     * Search chunks by cosine similarity with scope and readiness filters.
     * All scope filtering is done in SQL, not in Java.
     * Generation correctness is enforced via c.embedding_generation = rt.generation
     * so each chunk matches its own document's current retrieval task generation.
     */
    @Transactional(readOnly = true)
    public List<ChunkVectorRow> search(
            float[] queryVector,
            Long workspaceId,
            List<Long> knowledgeBaseIds,
            List<Long> documentIds,
            int limit) {

        if (queryVector == null || queryVector.length == 0) {
            throw new IllegalArgumentException("queryVector must not be empty");
        }
        if (workspaceId == null) {
            throw new IllegalArgumentException("workspaceId must not be null");
        }
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            throw new IllegalArgumentException("knowledgeBaseIds must not be empty");
        }

        // Build dynamic SQL with parameterized filters
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();

        sql.append("SELECT ");
        sql.append("  c.id AS chunk_id, ");
        sql.append("  c.document_id, ");
        sql.append("  c.knowledge_base_id, ");
        sql.append("  c.content, ");
        sql.append("  c.chunk_index, ");
        sql.append("  c.section_path, ");
        sql.append("  1.0 - (c.embedding <=> ?::vector) AS score ");
        params.add(new PGvector(queryVector));

        sql.append("FROM document_chunk c ");
        sql.append("JOIN document d ON d.id = c.document_id ");
        sql.append("JOIN document_retrieval_task rt ON rt.document_id = c.document_id ");

        sql.append("WHERE c.embedding IS NOT NULL ");
        sql.append("AND c.embedding_model IS NOT NULL ");
        sql.append("AND c.embedding_fence_token > 0 ");
        sql.append("AND c.embedding_generation = rt.generation ");

        sql.append("AND rt.status = 'READY' ");
        sql.append("AND rt.embedding_model IS NOT NULL ");
        sql.append("AND rt.embedding_dimension = 1536 ");

        // Workspace scope — via knowledge_base -> workspace FK chain
        sql.append("AND d.knowledge_base_id IN (");
        sql.append("  SELECT kb.id FROM knowledge_base kb WHERE kb.workspace_id = ?) ");
        params.add(workspaceId);

        sql.append("AND d.status = 'COMPLETED' ");

        // KnowledgeBase filter
        sql.append("AND c.knowledge_base_id = ANY(?::bigint[]) ");
        params.add(createLongArray(knowledgeBaseIds));

        // Optional document filter
        if (documentIds != null && !documentIds.isEmpty()) {
            sql.append("AND c.document_id = ANY(?::bigint[]) ");
            params.add(createLongArray(documentIds));
        }

        sql.append("ORDER BY score DESC ");
        sql.append("LIMIT ?");
        params.add(limit);

        String query = sql.toString();
        log.debug("Vector search SQL: {}", query);

        // Set HNSW ef_search for this session (must be separate from the SELECT)
        jdbcTemplate.execute("SET LOCAL hnsw.ef_search = " + properties.getHnswEfSearch());

        return jdbcTemplate.query(query, ps -> {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
        }, this::mapRow);
    }

    private ChunkVectorRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ChunkVectorRow(
                rs.getLong("chunk_id"),
                rs.getLong("document_id"),
                rs.getLong("knowledge_base_id"),
                rs.getString("content"),
                rs.getFloat("score"),
                rs.getInt("chunk_index"),
                rs.getString("section_path")
        );
    }

    private java.sql.Array createLongArray(List<Long> values) {
        Long[] arr = values.toArray(new Long[0]);
        return jdbcTemplate.execute((java.sql.Connection con) -> con.createArrayOf("bigint", arr));
    }
}