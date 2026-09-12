package com.intellidesk.retrieval.indexing.mq;

import com.intellidesk.retrieval.indexing.RetrievalIndexingService;
import com.intellidesk.retrieval.task.DocumentRetrievalTask;
import com.intellidesk.retrieval.task.DocumentRetrievalTaskMapper;
import com.intellidesk.retrieval.task.RetrievalTaskStatus;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RetrievalTaskConsumer")
class RetrievalTaskConsumerTest {

    @Mock
    private DocumentRetrievalTaskMapper taskMapper;

    @Mock
    private RetrievalIndexingService indexingService;

    @Mock
    private Jackson2JsonMessageConverter messageConverter;

    @Mock
    private Channel channel;

    private RetrievalTaskConsumer consumer;

    private static final long DELIVERY_TAG = 12345L;

    @BeforeEach
    void setUp() {
        consumer = new RetrievalTaskConsumer(taskMapper, indexingService, messageConverter);
    }

    private Message createMessage(RetrievalIndexMessage indexMessage) {
        MessageProperties props = new MessageProperties();
        props.setDeliveryTag(DELIVERY_TAG);
        Message message = new Message(new byte[0], props);
        when(messageConverter.fromMessage(message)).thenReturn(indexMessage);
        return message;
    }

    private DocumentRetrievalTask createTask(long id, String status, int generation) {
        DocumentRetrievalTask task = new DocumentRetrievalTask();
        task.setId(id);
        task.setDocumentId(200L);
        task.setStatus(status);
        task.setGeneration(generation);
        task.setMessageId(UUID.randomUUID().toString());
        task.setAttemptCount(0);
        task.setMaxAttempts(3);
        task.setFenceToken(0L);
        return task;
    }

    @Nested
    @DisplayName("stale task handling")
    class StaleTaskHandlingTests {

        @Test
        @DisplayName("should ack on stale task not found")
        void shouldAckOnStaleTaskNotFound() throws Exception {
            RetrievalIndexMessage indexMessage = RetrievalIndexMessage.create(
                    UUID.randomUUID().toString(), 999L, 200L, 1);
            Message message = createMessage(indexMessage);
            when(taskMapper.selectById(999L)).thenReturn(null);

            consumer.onMessage(message, channel);

            verify(channel).basicAck(DELIVERY_TAG, false);
            verify(indexingService, never()).claimAndProcess(any());
        }

        @Test
        @DisplayName("should ack on old generation message")
        void shouldAckOnOldGenerationMessage() throws Exception {
            RetrievalIndexMessage indexMessage = RetrievalIndexMessage.create(
                    UUID.randomUUID().toString(), 100L, 200L, 3);
            Message message = createMessage(indexMessage);
            DocumentRetrievalTask task = createTask(100L, RetrievalTaskStatus.PENDING.getValue(), 5);
            when(taskMapper.selectById(100L)).thenReturn(task);

            consumer.onMessage(message, channel);

            verify(channel).basicAck(DELIVERY_TAG, false);
            verify(indexingService, never()).claimAndProcess(any());
        }
    }

    @Nested
    @DisplayName("terminal state handling")
    class TerminalStateHandlingTests {

        @Test
        @DisplayName("should ack on already ready task")
        void shouldAckOnAlreadyReadyTask() throws Exception {
            RetrievalIndexMessage indexMessage = RetrievalIndexMessage.create(
                    UUID.randomUUID().toString(), 100L, 200L, 1);
            Message message = createMessage(indexMessage);
            DocumentRetrievalTask task = createTask(100L, RetrievalTaskStatus.READY.getValue(), 1);
            when(taskMapper.selectById(100L)).thenReturn(task);

            consumer.onMessage(message, channel);

            verify(channel).basicAck(DELIVERY_TAG, false);
            verify(indexingService, never()).claimAndProcess(any());
        }

        @Test
        @DisplayName("should ack on already cancelled task")
        void shouldAckOnAlreadyCancelledTask() throws Exception {
            RetrievalIndexMessage indexMessage = RetrievalIndexMessage.create(
                    UUID.randomUUID().toString(), 100L, 200L, 1);
            Message message = createMessage(indexMessage);
            DocumentRetrievalTask task = createTask(100L, RetrievalTaskStatus.CANCELLED.getValue(), 1);
            when(taskMapper.selectById(100L)).thenReturn(task);

            consumer.onMessage(message, channel);

            verify(channel).basicAck(DELIVERY_TAG, false);
            verify(indexingService, never()).claimAndProcess(any());
        }

        @Test
        @DisplayName("should ack on already failed task")
        void shouldAckOnAlreadyFailedTask() throws Exception {
            RetrievalIndexMessage indexMessage = RetrievalIndexMessage.create(
                    UUID.randomUUID().toString(), 100L, 200L, 1);
            Message message = createMessage(indexMessage);
            DocumentRetrievalTask task = createTask(100L, RetrievalTaskStatus.FAILED.getValue(), 1);
            when(taskMapper.selectById(100L)).thenReturn(task);

            consumer.onMessage(message, channel);

            verify(channel).basicAck(DELIVERY_TAG, false);
            verify(indexingService, never()).claimAndProcess(any());
        }
    }

    @Nested
    @DisplayName("processing handling")
    class ProcessingHandlingTests {

        @Test
        @DisplayName("should process on pending task")
        void shouldProcessOnPendingTask() throws Exception {
            RetrievalIndexMessage indexMessage = RetrievalIndexMessage.create(
                    UUID.randomUUID().toString(), 100L, 200L, 1);
            Message message = createMessage(indexMessage);
            DocumentRetrievalTask task = createTask(100L, RetrievalTaskStatus.PENDING.getValue(), 1);
            when(taskMapper.selectById(100L)).thenReturn(task);

            consumer.onMessage(message, channel);

            verify(channel).basicAck(DELIVERY_TAG, false);
            verify(indexingService).claimAndProcess(task);
        }

        @Test
        @DisplayName("should ack on queued task with active lease")
        void shouldAckOnQueuedTaskWithActiveLease() throws Exception {
            RetrievalIndexMessage indexMessage = RetrievalIndexMessage.create(
                    UUID.randomUUID().toString(), 100L, 200L, 1);
            Message message = createMessage(indexMessage);
            DocumentRetrievalTask task = createTask(100L, RetrievalTaskStatus.PROCESSING.getValue(), 1);
            task.setLeaseUntil(LocalDateTime.now().plusMinutes(10));
            when(taskMapper.selectById(100L)).thenReturn(task);

            consumer.onMessage(message, channel);

            verify(channel).basicAck(DELIVERY_TAG, false);
            verify(indexingService, never()).claimAndProcess(any());
        }
    }

    @Nested
    @DisplayName("deserialization handling")
    class DeserializationHandlingTests {

        @Test
        @DisplayName("should nack on null deserialized message")
        void shouldNackOnNullDeserializedMessage() throws Exception {
            MessageProperties props = new MessageProperties();
            props.setDeliveryTag(DELIVERY_TAG);
            Message message = new Message(new byte[0], props);
            when(messageConverter.fromMessage(message)).thenReturn(null);

            consumer.onMessage(message, channel);

            verify(channel).basicNack(DELIVERY_TAG, false, false);
        }

        @Test
        @DisplayName("should nack on deserialization exception")
        void shouldNackOnDeserializationException() throws Exception {
            MessageProperties props = new MessageProperties();
            props.setDeliveryTag(DELIVERY_TAG);
            Message message = new Message(new byte[0], props);
            when(messageConverter.fromMessage(message))
                    .thenThrow(new RuntimeException("deserialization failed"));

            consumer.onMessage(message, channel);

            verify(channel).basicNack(DELIVERY_TAG, false, false);
        }
    }
}