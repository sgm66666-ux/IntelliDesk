package com.intellidesk.document.mq;

import com.intellidesk.document.DocumentIndexTask;
import com.intellidesk.document.DocumentIndexTaskMapper;
import com.intellidesk.document.DocumentMapper;
import com.intellidesk.document.DocumentProcessingService;
import com.intellidesk.document.KnowledgeDocument;
import com.intellidesk.document.model.DocumentTaskStatus;
import com.intellidesk.knowledge.KnowledgeBase;
import com.intellidesk.knowledge.KnowledgeBaseMapper;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentTaskConsumer failure and idempotency")
class DocumentTaskConsumerTest {

    private static final long DELIVERY_TAG = 4242L;
    private static final long TASK_ID = 100L;
    private static final long DOCUMENT_ID = 200L;

    @Mock
    private DocumentIndexTaskMapper taskMapper;
    @Mock
    private DocumentMapper documentMapper;
    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;
    @Mock
    private DocumentProcessingService processingService;
    @Mock
    private DocumentTaskPublisher publisher;
    @Mock
    private Jackson2JsonMessageConverter messageConverter;
    @Mock
    private Channel channel;

    private DocumentTaskConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new DocumentTaskConsumer(taskMapper, documentMapper, knowledgeBaseMapper,
                processingService, publisher, messageConverter);
    }

    @Test
    @DisplayName("malformed payload is nacked to DLQ without touching state")
    void malformedPayloadIsNackedWithoutStateMutation() throws Exception {
        Message message = rawMessage();
        when(messageConverter.fromMessage(message)).thenThrow(new IllegalArgumentException("bad payload"));

        consumer.onMessage(message, channel);

        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verify(taskMapper, never()).selectById(any());
        verify(processingService, never()).claimTask(any(), any());
    }

    @Test
    @DisplayName("duplicate delivery for a succeeded task is acked without reprocessing")
    void duplicateSucceededDeliveryIsHarmless() throws Exception {
        Message message = validMessage();
        DocumentIndexTask task = task(DocumentTaskStatus.SUCCEEDED.getValue());
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(document());

        consumer.onMessage(message, channel);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(processingService, never()).claimTask(any(), any());
        verify(processingService, never()).process(any(), any(), any(), any(Integer.class));
        verify(publisher, never()).publishRetry(any());
    }

    @Test
    @DisplayName("active processing lease rejects duplicate worker")
    void activeLeaseRejectsDuplicateWorker() throws Exception {
        Message message = validMessage();
        DocumentIndexTask task = task(DocumentTaskStatus.PROCESSING.getValue());
        task.setLeaseUntil(LocalDateTime.now().plusMinutes(5));
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(document());

        consumer.onMessage(message, channel);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(processingService, never()).claimTask(any(), any());
        verify(processingService, never()).process(any(), any(), any(), any(Integer.class));
    }

    @Test
    @DisplayName("atomic claim loser is acked without processing")
    void claimLoserIsAckedWithoutProcessing() throws Exception {
        Message message = validMessage();
        DocumentIndexTask task = task(DocumentTaskStatus.PENDING.getValue());
        KnowledgeDocument document = document();
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(document);
        when(processingService.claimTask(task, document)).thenReturn(null);

        consumer.onMessage(message, channel);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(processingService, never()).process(any(), any(), any(), any(Integer.class));
        verify(publisher, never()).publishRetry(any());
    }

    @Test
    @DisplayName("missing knowledge base is persisted as permanent failure and acked")
    void missingKnowledgeBaseIsPermanentFailure() throws Exception {
        Message message = validMessage();
        DocumentIndexTask task = task(DocumentTaskStatus.PENDING.getValue());
        KnowledgeDocument document = document();
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(document);
        when(processingService.claimTask(task, document))
                .thenReturn(new DocumentProcessingService.ClaimResult(1, LocalDateTime.now().plusMinutes(5)));
        when(knowledgeBaseMapper.selectById(document.getKnowledgeBaseId())).thenReturn(null);

        consumer.onMessage(message, channel);

        verify(processingService).markPermanentFailure(task, document, "KB_NOT_FOUND",
                "Knowledge base not found", 1);
        verify(processingService, never()).process(any(), any(), any(), any(Integer.class));
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("retry-wait terminal result publishes one retry and acknowledges original")
    void retryWaitPublishesExactlyOneRetry() throws Exception {
        Message message = validMessage();
        DocumentIndexTask initial = task(DocumentTaskStatus.PENDING.getValue());
        DocumentIndexTask retryWait = task(DocumentTaskStatus.RETRY_WAIT.getValue());
        KnowledgeDocument document = document();
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(document.getKnowledgeBaseId());
        when(taskMapper.selectById(TASK_ID)).thenReturn(initial, retryWait);
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(document);
        when(processingService.claimTask(initial, document))
                .thenReturn(new DocumentProcessingService.ClaimResult(1, LocalDateTime.now().plusMinutes(5)));
        when(knowledgeBaseMapper.selectById(document.getKnowledgeBaseId())).thenReturn(knowledgeBase);

        consumer.onMessage(message, channel);

        verify(processingService).process(initial, document, knowledgeBase, 1);
        verify(publisher).publishRetry(retryWait);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("unexpected processing exception is bounded and left for lease recovery")
    void unexpectedProcessingExceptionLeavesClaimForRecovery() throws Exception {
        Message message = validMessage();
        DocumentIndexTask initial = task(DocumentTaskStatus.PENDING.getValue());
        DocumentIndexTask stillProcessing = task(DocumentTaskStatus.PROCESSING.getValue());
        KnowledgeDocument document = document();
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(document.getKnowledgeBaseId());
        when(taskMapper.selectById(TASK_ID)).thenReturn(initial, stillProcessing);
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(document);
        when(processingService.claimTask(initial, document))
                .thenReturn(new DocumentProcessingService.ClaimResult(1, LocalDateTime.now().plusMinutes(5)));
        when(knowledgeBaseMapper.selectById(document.getKnowledgeBaseId())).thenReturn(knowledgeBase);
        org.mockito.Mockito.doThrow(new RuntimeException("unexpected consumer boundary failure"))
                .when(processingService).process(initial, document, knowledgeBase, 1);

        consumer.onMessage(message, channel);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(publisher, never()).publishRetry(any());
    }

    private Message rawMessage() {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(DELIVERY_TAG);
        return new Message(new byte[0], properties);
    }

    private Message validMessage() {
        Message message = rawMessage();
        DocumentProcessMessage payload = DocumentProcessMessage.create(
                TASK_ID, DOCUMENT_ID, UUID.randomUUID().toString());
        when(messageConverter.fromMessage(message)).thenReturn(payload);
        return message;
    }

    private DocumentIndexTask task(String status) {
        DocumentIndexTask task = new DocumentIndexTask();
        task.setId(TASK_ID);
        task.setDocumentId(DOCUMENT_ID);
        task.setStatus(status);
        task.setAttemptCount(0);
        task.setMaxAttempts(3);
        task.setVersion(0);
        return task;
    }

    private KnowledgeDocument document() {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(DOCUMENT_ID);
        document.setKnowledgeBaseId(300L);
        document.setVersion(0);
        return document;
    }
}
