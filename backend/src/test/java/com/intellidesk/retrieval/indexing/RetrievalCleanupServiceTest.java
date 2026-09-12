package com.intellidesk.retrieval.indexing;

import com.intellidesk.infrastructure.config.RetrievalProperties;
import com.intellidesk.retrieval.keyword.ElasticsearchChunkIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RetrievalCleanupService")
class RetrievalCleanupServiceTest {

    @Mock
    private RetrievalCleanupTaskMapper cleanupTaskMapper;

    @Mock
    private ElasticsearchChunkIndex elasticsearchChunkIndex;

    private RetrievalProperties properties;
    private RetrievalCleanupService service;

    @BeforeEach
    void setUp() {
        properties = new RetrievalProperties();
        properties.setProcessingLeaseSeconds(300);
        properties.setRetryDelaySeconds(30);
        properties.setMaxAttempts(3);
        service = new RetrievalCleanupService(cleanupTaskMapper, elasticsearchChunkIndex, properties);
    }

    private RetrievalCleanupTask createCleanupTask(long id, long documentId, String status, int attemptCount, int maxAttempts) {
        RetrievalCleanupTask task = new RetrievalCleanupTask();
        task.setId(id);
        task.setDocumentId(documentId);
        task.setWorkspaceId(1000L);
        task.setKnowledgeBaseId(10L);
        task.setEsIndexName("intellidesk-chunks-v1");
        task.setStatus(status);
        task.setAttemptCount(attemptCount);
        task.setMaxAttempts(maxAttempts);
        task.setNotBefore(LocalDateTime.now().minusMinutes(10));
        return task;
    }

    @Nested
    @DisplayName("createCleanupTask")
    class CreateCleanupTaskTests {

        @Test
        @DisplayName("should create cleanup task")
        void shouldCreateCleanupTask() {
            when(cleanupTaskMapper.insert(any(RetrievalCleanupTask.class))).thenAnswer(inv -> {
                RetrievalCleanupTask task = inv.getArgument(0);
                task.setId(1L);
                return 1;
            });

            service.createCleanupTask(200L, 1000L, 10L, "intellidesk-chunks-v1");

            ArgumentCaptor<RetrievalCleanupTask> captor = ArgumentCaptor.forClass(RetrievalCleanupTask.class);
            verify(cleanupTaskMapper).insert(captor.capture());
            RetrievalCleanupTask captured = captor.getValue();
            assertThat(captured.getDocumentId()).isEqualTo(200L);
            assertThat(captured.getWorkspaceId()).isEqualTo(1000L);
            assertThat(captured.getKnowledgeBaseId()).isEqualTo(10L);
            assertThat(captured.getEsIndexName()).isEqualTo("intellidesk-chunks-v1");
            assertThat(captured.getStatus()).isEqualTo("PENDING");
            assertThat(captured.getNotBefore()).isNotNull();
            assertThat(captured.getAttemptCount()).isEqualTo(0);
            assertThat(captured.getMaxAttempts()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("processCleanup")
    class ProcessCleanupTests {

        @Test
        @DisplayName("should process and succeed cleanup")
        void shouldProcessAndSucceedCleanup() {
            RetrievalCleanupTask task = createCleanupTask(1L, 200L, "PENDING", 0, 3);
            when(cleanupTaskMapper.selectList(any())).thenReturn(java.util.List.of(task), java.util.List.of());
            doNothing().when(elasticsearchChunkIndex).deleteByDocumentId(anyString(), anyLong());
            when(cleanupTaskMapper.update(isNull(), any())).thenReturn(1);

            service.processCleanup();

            verify(elasticsearchChunkIndex).deleteByDocumentId("intellidesk-chunks-v1", 200L);
            verify(cleanupTaskMapper).update(isNull(), any());
        }

        @Test
        @DisplayName("should retry on transient failure")
        void shouldRetryOnTransientFailure() {
            RetrievalCleanupTask task = createCleanupTask(1L, 200L, "PENDING", 0, 3);
            when(cleanupTaskMapper.selectList(any())).thenReturn(java.util.List.of(task), java.util.List.of());
            doThrow(new RuntimeException("ES temporarily unavailable"))
                    .when(elasticsearchChunkIndex).deleteByDocumentId(anyString(), anyLong());
            when(cleanupTaskMapper.update(isNull(), any())).thenReturn(1);

            service.processCleanup();

            ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper> captor =
                    ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
            verify(cleanupTaskMapper, atLeastOnce()).update(isNull(), captor.capture());
        }

        @Test
        @DisplayName("should mark dead on max attempts exhausted")
        void shouldMarkDeadOnMaxAttemptsExhausted() {
            RetrievalCleanupTask task = createCleanupTask(1L, 200L, "PENDING", 2, 3);
            when(cleanupTaskMapper.selectList(any())).thenReturn(java.util.List.of(task), java.util.List.of());
            doThrow(new RuntimeException("ES permanently unavailable"))
                    .when(elasticsearchChunkIndex).deleteByDocumentId(anyString(), anyLong());
            when(cleanupTaskMapper.update(isNull(), any())).thenReturn(1);

            service.processCleanup();

            verify(cleanupTaskMapper, atLeastOnce()).update(isNull(), any());
        }

        @Test
        @DisplayName("should process retry wait tasks")
        void shouldProcessRetryWaitTasks() {
            RetrievalCleanupTask pendingTask = createCleanupTask(1L, 200L, "PENDING", 0, 3);
            RetrievalCleanupTask retryTask = createCleanupTask(2L, 300L, "RETRY_WAIT", 1, 3);
            retryTask.setNextRetryAt(LocalDateTime.now().minusMinutes(5));

            when(cleanupTaskMapper.selectList(any()))
                    .thenReturn(java.util.List.of(pendingTask))
                    .thenReturn(java.util.List.of(retryTask));
            doNothing().when(elasticsearchChunkIndex).deleteByDocumentId(anyString(), anyLong());
            when(cleanupTaskMapper.update(isNull(), any())).thenReturn(1);

            service.processCleanup();

            verify(elasticsearchChunkIndex).deleteByDocumentId("intellidesk-chunks-v1", 200L);
            verify(elasticsearchChunkIndex).deleteByDocumentId("intellidesk-chunks-v1", 300L);
        }
    }
}