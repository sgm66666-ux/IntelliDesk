package com.intellidesk.document.mq;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.intellidesk.document.DocumentIndexTask;
import com.intellidesk.document.DocumentIndexTaskMapper;
import com.intellidesk.document.model.DocumentTaskStatus;
import com.intellidesk.infrastructure.config.DocumentIngestionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentTaskDispatcherTest {

    @Mock private DocumentIndexTaskMapper taskMapper;
    @Mock private DocumentTaskPublisher publisher;

    private DocumentTaskDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new DocumentTaskDispatcher(
                taskMapper, publisher, new DocumentIngestionProperties());
    }

    @Test
    void failedPendingPublishLeavesDatabaseStateUntouched() {
        DocumentIndexTask task = task(101L, DocumentTaskStatus.PENDING);
        when(taskMapper.selectList(any())).thenReturn(List.of(task), List.of(), List.of());
        when(publisher.publishMain(task)).thenReturn(DocumentTaskPublisher.PublishResult.timeout());

        dispatcher.dispatch();

        verify(taskMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void failedRetryWaitPublishLeavesDatabaseStateUntouched() {
        DocumentIndexTask task = task(102L, DocumentTaskStatus.RETRY_WAIT);
        when(taskMapper.selectList(any())).thenReturn(List.of(), List.of(task), List.of());
        when(publisher.publishMain(task)).thenReturn(DocumentTaskPublisher.PublishResult.nacked("broker nack"));

        dispatcher.dispatch();

        verify(taskMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void failedStaleQueuedPublishLeavesDatabaseStateUntouched() {
        DocumentIndexTask task = task(103L, DocumentTaskStatus.QUEUED);
        when(taskMapper.selectList(any())).thenReturn(List.of(), List.of(), List.of(task));
        when(publisher.publishMain(task)).thenReturn(DocumentTaskPublisher.PublishResult.returned());

        dispatcher.dispatch();

        verify(taskMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void acknowledgedPendingPublishUsesStatusGuardBeforeMarkingQueued() {
        DocumentIndexTask task = task(104L, DocumentTaskStatus.PENDING);
        when(taskMapper.selectList(any())).thenReturn(List.of(task), List.of(), List.of());
        when(publisher.publishMain(task)).thenReturn(DocumentTaskPublisher.PublishResult.acked());
        when(taskMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        dispatcher.dispatch();

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<UpdateWrapper> wrapperCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(taskMapper).update(isNull(), wrapperCaptor.capture());
        UpdateWrapper<?> wrapper = wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("id", "status");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(task.getId(), DocumentTaskStatus.PENDING.getValue(),
                        DocumentTaskStatus.QUEUED.getValue());
    }

    private DocumentIndexTask task(long id, DocumentTaskStatus status) {
        DocumentIndexTask task = new DocumentIndexTask();
        task.setId(id);
        task.setDocumentId(id + 1000);
        task.setMessageId("message-" + id);
        task.setStatus(status.getValue());
        return task;
    }
}
