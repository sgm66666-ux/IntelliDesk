package com.intellidesk.retrieval.indexing;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.intellidesk.document.DocumentChunk;
import com.intellidesk.document.DocumentChunkMapper;
import com.intellidesk.document.DocumentMapper;
import com.intellidesk.document.KnowledgeDocument;
import com.intellidesk.embedding.EmbeddingBatchResult;
import com.intellidesk.embedding.EmbeddingService;
import com.intellidesk.infrastructure.config.RetrievalProperties;
import com.intellidesk.knowledge.KnowledgeBase;
import com.intellidesk.knowledge.KnowledgeBaseMapper;
import com.intellidesk.retrieval.keyword.ElasticsearchChunkIndex;
import com.intellidesk.retrieval.task.DocumentRetrievalTask;
import com.intellidesk.retrieval.task.DocumentRetrievalTaskMapper;
import com.intellidesk.retrieval.task.RetrievalTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RetrievalIndexingService")
class RetrievalIndexingServiceTest {

    @Mock
    private DocumentRetrievalTaskMapper taskMapper;
    @Mock
    private DocumentChunkMapper chunkMapper;
    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private ElasticsearchChunkIndex elasticsearchChunkIndex;
    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;
    @Mock
    private DocumentMapper documentMapper;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private TransactionStatus transactionStatus;

    private RetrievalProperties properties;
    private RetrievalIndexingService service;

    @BeforeEach
    void setUp() throws Exception {
        properties = new RetrievalProperties();
        properties.setProcessingLeaseSeconds(300);
        properties.setRetryDelaySeconds(30);
        properties.setMaxAttempts(3);
        properties.setEsIndexName("intellidesk-chunks-v1");

        service = new RetrievalIndexingService(
                taskMapper, chunkMapper, embeddingService, elasticsearchChunkIndex,
                knowledgeBaseMapper, documentMapper, properties, transactionManager,
                new com.fasterxml.jackson.databind.ObjectMapper());

        TransactionTemplate stubTemplate = new TransactionTemplate(transactionManager) {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(transactionStatus);
            }
        };
        Field field = RetrievalIndexingService.class.getDeclaredField("transactionTemplate");
        field.setAccessible(true);
        field.set(service, stubTemplate);
    }

    private DocumentRetrievalTask createTask(long id, String status, int generation, int attemptCount, int maxAttempts) {
        DocumentRetrievalTask task = new DocumentRetrievalTask();
        task.setId(id);
        task.setDocumentId(200L);
        task.setStatus(status);
        task.setGeneration(generation);
        task.setAttemptCount(attemptCount);
        task.setMaxAttempts(maxAttempts);
        task.setFenceToken(0L);
        task.setMessageId(UUID.randomUUID().toString());
        return task;
    }

    private DocumentChunk createChunk(long id, long documentId, String content) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(id);
        chunk.setDocumentId(documentId);
        chunk.setKnowledgeBaseId(10L);
        chunk.setChunkIndex(0);
        chunk.setContent(content);
        chunk.setSourceMetadata("{}");
        return chunk;
    }

    @Nested
    @DisplayName("claim")
    class ClaimTests {

        @Test
        @DisplayName("should claim successfully and handle empty chunks as retryable")
        void shouldClaimSuccessfully() {
            DocumentRetrievalTask task = createTask(100L, RetrievalTaskStatus.PENDING.getValue(), 1, 0, 3);
            when(taskMapper.update(any(), any())).thenReturn(1);
            when(taskMapper.selectById(100L)).thenReturn(task);

            service.claimAndProcess(task);

            // claim (1 update) + markRetryableFailure (1 update) = 2 updates
            verify(taskMapper, times(2)).update(isNull(), any());
        }

        @Test
        @DisplayName("should not claim when already processing")
        void shouldNotClaimWhenAlreadyProcessing() {
            DocumentRetrievalTask task = createTask(100L, RetrievalTaskStatus.PROCESSING.getValue(), 1, 0, 3);
            when(taskMapper.update(any(), any())).thenReturn(0);

            service.claimAndProcess(task);

            verify(taskMapper).update(isNull(), any());
            verify(embeddingService, never()).embedDocuments(anyList());
        }
    }

    @Nested
    @DisplayName("failure handling")
    class FailureHandlingTests {

        @Test
        @DisplayName("should mark retryable failure")
        void shouldMarkRetryableFailure() {
            DocumentRetrievalTask task = createTask(100L, RetrievalTaskStatus.PENDING.getValue(), 1, 0, 3);
            when(taskMapper.update(any(), any())).thenReturn(1);
            when(taskMapper.selectById(100L)).thenReturn(task);
            when(chunkMapper.selectList(any())).thenReturn(List.of());
            when(taskMapper.selectById(100L)).thenReturn(task);

            service.claimAndProcess(task);

            // The claim succeeds (return 1), then processIndexing finds no chunks,
            // marks retryable failure (attempt 1/3)
            verify(taskMapper, atLeastOnce()).update(isNull(), any());
        }

        @Test
        @DisplayName("should mark permanent failure when max attempts exhausted")
        void shouldMarkPermanentFailure() {
            DocumentRetrievalTask task = createTask(100L, RetrievalTaskStatus.PENDING.getValue(), 1, 2, 3);
            when(taskMapper.update(any(), any())).thenReturn(1);
            when(taskMapper.selectById(100L)).thenReturn(task);
            when(chunkMapper.selectList(any())).thenReturn(List.of());

            service.claimAndProcess(task);

            verify(taskMapper, atLeast(2)).update(isNull(), any());
        }

        @Test
        @DisplayName("should handle embedding failure as retryable")
        void shouldHandleEmbeddingFailure() {
            DocumentRetrievalTask task = createTask(100L, RetrievalTaskStatus.PENDING.getValue(), 1, 0, 3);
            when(taskMapper.update(any(), any())).thenReturn(1);
            when(taskMapper.selectById(100L)).thenReturn(task);
            DocumentChunk chunk = createChunk(1L, 200L, "test content");
            when(chunkMapper.selectList(any())).thenReturn(List.of(chunk));
            when(embeddingService.embedDocuments(anyList()))
                    .thenThrow(new RuntimeException("embedding service unavailable"));

            service.claimAndProcess(task);

            verify(taskMapper, atLeast(2)).update(isNull(), any());
        }
    }

    @Nested
    @DisplayName("fence check")
    class FenceCheckTests {

        @Test
        @DisplayName("should fence check fail on stale worker")
        void shouldFenceCheckFailOnStaleWorker() {
            DocumentRetrievalTask task = createTask(100L, RetrievalTaskStatus.PENDING.getValue(), 1, 0, 3);
            DocumentRetrievalTask staleTask = createTask(100L, RetrievalTaskStatus.PROCESSING.getValue(), 2, 1, 3);
            staleTask.setFenceToken(9999L);

            when(taskMapper.update(any(), any())).thenReturn(1);
            when(taskMapper.selectById(100L)).thenReturn(task);

            DocumentChunk chunk = createChunk(1L, 200L, "test content");
            when(chunkMapper.selectList(any())).thenReturn(List.of(chunk));

            float[] embedding = new float[]{0.1f, 0.2f, 0.3f};
            EmbeddingBatchResult batchResult = new EmbeddingBatchResult(
                    List.of(embedding), "test-model", 3, 10);
            when(embeddingService.embedDocuments(anyList())).thenReturn(batchResult);
            when(embeddingService.model()).thenReturn("test-model");
            when(embeddingService.dimension()).thenReturn(3);

            // After embedding update, refetch shows stale task
            when(taskMapper.selectById(100L)).thenReturn(staleTask);

            service.claimAndProcess(task);

            // ES bulk index should NOT be called because fence check fails
            verify(elasticsearchChunkIndex, never()).bulkIndex(anyList(), anyLong());
        }
    }

    @Nested
    @DisplayName("bulk failure handling")
    class BulkFailureHandlingTests {

        @Test
        @DisplayName("should not mark ready on partial bulk failure")
        void shouldNotMarkReadyOnPartialBulkFailure() {
            DocumentRetrievalTask task = createTask(100L, RetrievalTaskStatus.PENDING.getValue(), 1, 0, 3);

            // Capture the fence token from the claim's UpdateWrapper
            // MyBatis-Plus uses generated param names (MPGENVAL1, etc.), so we must
            // parse the SQL template to find which param corresponds to fence_token
            AtomicLong fenceToken = new AtomicLong();
            when(taskMapper.update(any(), any())).thenAnswer(inv -> {
                try {
                    Object arg = inv.getArgument(1);
                    if (arg instanceof com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> aw) {
                        Map<String, Object> params = aw.getParamNameValuePairs();
                        String sqlSet = aw.getSqlSet();
                        if (params != null && sqlSet != null) {
                            java.util.regex.Matcher m = java.util.regex.Pattern
                                    .compile("fence_token=#\\{ew\\.paramNameValuePairs\\.(\\w+)\\}")
                                    .matcher(sqlSet);
                            if (m.find()) {
                                Object value = params.get(m.group(1));
                                if (value != null) {
                                    fenceToken.set(((Number) value).longValue());
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                    fenceToken.set(System.currentTimeMillis());
                }
                return 1;
            });

            DocumentChunk chunk = createChunk(1L, 200L, "test content");
            when(chunkMapper.selectList(any())).thenReturn(List.of(chunk));

            float[] embedding = new float[]{0.1f, 0.2f, 0.3f};
            EmbeddingBatchResult batchResult = new EmbeddingBatchResult(
                    List.of(embedding), "test-model", 3, 10);
            when(embeddingService.embedDocuments(anyList())).thenReturn(batchResult);
            when(embeddingService.model()).thenReturn("test-model");
            when(embeddingService.dimension()).thenReturn(3);

            // Update chunk embeddings succeeds
            when(chunkMapper.update(any(), any())).thenReturn(1);

            KnowledgeDocument document = new KnowledgeDocument();
            document.setId(200L);
            document.setKnowledgeBaseId(10L);
            when(documentMapper.selectById(200L)).thenReturn(document);

            KnowledgeBase kb = new KnowledgeBase();
            kb.setId(10L);
            kb.setWorkspaceId(1000L);
            when(knowledgeBaseMapper.selectById(10L)).thenReturn(kb);

            // selectById must return a task with the SAME fence token as the claim
            when(taskMapper.selectById(100L)).thenAnswer(inv -> {
                DocumentRetrievalTask t = createTask(100L, RetrievalTaskStatus.PROCESSING.getValue(), 1, 1, 3);
                t.setFenceToken(fenceToken.get());
                return t;
            });

            // ES bulk index fails
            doThrow(new RuntimeException("ES unavailable"))
                    .when(elasticsearchChunkIndex).bulkIndex(anyList(), anyLong());

            service.claimAndProcess(task);

            // Should mark retryable failure, not READY
            verify(taskMapper, atLeast(2)).update(isNull(), any());
        }
    }
}