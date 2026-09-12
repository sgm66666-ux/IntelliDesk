package com.intellidesk.retrieval.indexing;

import com.intellidesk.common.BusinessException;
import com.intellidesk.common.ErrorCode;
import com.intellidesk.embedding.EmbeddingService;
import com.intellidesk.infrastructure.config.RetrievalProperties;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RetrievalTaskService")
class RetrievalTaskServiceTest {

    @Mock
    private DocumentRetrievalTaskMapper taskMapper;

    @Mock
    private EmbeddingService embeddingService;

    private RetrievalProperties properties;
    private RetrievalTaskService service;

    @BeforeEach
    void setUp() {
        properties = new RetrievalProperties();
        properties.setMaxAttempts(3);
        properties.setEsIndexName("intellidesk-chunks-v1");
        when(embeddingService.model()).thenReturn("text-embedding-3-small");
        when(embeddingService.dimension()).thenReturn(1536);
        service = new RetrievalTaskService(taskMapper, properties, embeddingService);
    }

    private DocumentRetrievalTask createTask(long id, long documentId, String status, int generation) {
        DocumentRetrievalTask task = new DocumentRetrievalTask();
        task.setId(id);
        task.setDocumentId(documentId);
        task.setStatus(status);
        task.setGeneration(generation);
        task.setAttemptCount(0);
        task.setMaxAttempts(3);
        task.setFenceToken(0L);
        task.setMessageId(UUID.randomUUID().toString());
        return task;
    }

    @Nested
    @DisplayName("createPendingTask")
    class CreatePendingTaskTests {

        @Test
        @DisplayName("should create pending task")
        void shouldCreatePendingTask() {
            when(taskMapper.insert(any(DocumentRetrievalTask.class))).thenAnswer(inv -> {
                DocumentRetrievalTask task = inv.getArgument(0);
                task.setId(100L);
                return 1;
            });

            DocumentRetrievalTask result = service.createPendingTask(200L, 1000L, 10L, 42L);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(100L);
            assertThat(result.getDocumentId()).isEqualTo(200L);
            assertThat(result.getStatus()).isEqualTo(RetrievalTaskStatus.PENDING.getValue());
            assertThat(result.getGeneration()).isEqualTo(1);
            assertThat(result.getAttemptCount()).isEqualTo(0);
            assertThat(result.getMaxAttempts()).isEqualTo(3);
            assertThat(result.getEmbeddingModel()).isEqualTo("text-embedding-3-small");
            assertThat(result.getEmbeddingDimension()).isEqualTo(1536);
            assertThat(result.getEsIndexName()).isEqualTo("intellidesk-chunks-v1");
            assertThat(result.getRequestedBy()).isEqualTo(42L);
            assertThat(result.getMessageId()).isNotNull();

            verify(taskMapper).insert(any(DocumentRetrievalTask.class));
        }
    }

    @Nested
    @DisplayName("cancelTask")
    class CancelTaskTests {

        @Test
        @DisplayName("should cancel task in non-terminal state")
        void shouldCancelTask() {
            when(taskMapper.update(isNull(), any())).thenReturn(1);

            service.cancelTask(200L);

            verify(taskMapper).update(isNull(), any());
        }

        @Test
        @DisplayName("should not update when no task matches")
        void shouldNotUpdateWhenNoTaskMatches() {
            when(taskMapper.update(isNull(), any())).thenReturn(0);

            service.cancelTask(200L);

            verify(taskMapper).update(isNull(), any());
        }
    }

    @Nested
    @DisplayName("reindex")
    class ReindexTests {

        @Test
        @DisplayName("should reindex from ready state")
        void shouldReindexFromReady() {
            DocumentRetrievalTask task = createTask(100L, 200L,
                    RetrievalTaskStatus.READY.getValue(), 1);
            when(taskMapper.selectOne(any())).thenReturn(task);
            when(taskMapper.update(isNull(), any())).thenReturn(1);

            DocumentRetrievalTask updatedTask = createTask(100L, 200L,
                    RetrievalTaskStatus.PENDING.getValue(), 2);
            when(taskMapper.selectById(100L)).thenReturn(updatedTask);

            DocumentRetrievalTask result = service.reindex(200L, 42L);

            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo(RetrievalTaskStatus.PENDING.getValue());
            assertThat(result.getGeneration()).isEqualTo(2);
            verify(taskMapper).update(isNull(), any());
        }

        @Test
        @DisplayName("should reindex from failed state")
        void shouldReindexFromFailed() {
            DocumentRetrievalTask task = createTask(100L, 200L,
                    RetrievalTaskStatus.FAILED.getValue(), 1);
            when(taskMapper.selectOne(any())).thenReturn(task);
            when(taskMapper.update(isNull(), any())).thenReturn(1);

            DocumentRetrievalTask updatedTask = createTask(100L, 200L,
                    RetrievalTaskStatus.PENDING.getValue(), 2);
            when(taskMapper.selectById(100L)).thenReturn(updatedTask);

            DocumentRetrievalTask result = service.reindex(200L, 42L);

            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo(RetrievalTaskStatus.PENDING.getValue());
            verify(taskMapper).update(isNull(), any());
        }

        @Test
        @DisplayName("should reject reindex from processing state")
        void shouldRejectReindexFromProcessing() {
            DocumentRetrievalTask task = createTask(100L, 200L,
                    RetrievalTaskStatus.PROCESSING.getValue(), 1);
            when(taskMapper.selectOne(any())).thenReturn(task);

            assertThatThrownBy(() -> service.reindex(200L, 42L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(ErrorCode.RETRIEVAL_REINDEX_NOT_ALLOWED.getCode());

            verify(taskMapper, never()).update(isNull(), any());
        }

        @Test
        @DisplayName("should throw when task not found")
        void shouldThrowWhenTaskNotFound() {
            when(taskMapper.selectOne(any())).thenReturn(null);

            assertThatThrownBy(() -> service.reindex(200L, 42L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(ErrorCode.RETRIEVAL_TASK_NOT_FOUND.getCode());
        }
    }
}