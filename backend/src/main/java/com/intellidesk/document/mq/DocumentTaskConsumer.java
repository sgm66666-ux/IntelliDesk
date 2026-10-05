package com.intellidesk.document.mq;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.intellidesk.document.DocumentIndexTask;
import com.intellidesk.document.DocumentIndexTaskMapper;
import com.intellidesk.document.DocumentMapper;
import com.intellidesk.document.DocumentProcessingService;
import com.intellidesk.document.KnowledgeDocument;
import com.intellidesk.document.model.DocumentStatus;
import com.intellidesk.document.model.DocumentTaskStatus;
import com.intellidesk.knowledge.KnowledgeBase;
import com.intellidesk.knowledge.KnowledgeBaseMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Objects;
import com.intellidesk.common.TraceContext;
import org.slf4j.MDC;

@Slf4j
@Component
@Profile("!test")
public class DocumentTaskConsumer {

    private final DocumentIndexTaskMapper taskMapper;
    private final DocumentMapper documentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentProcessingService processingService;
    private final DocumentTaskPublisher publisher;
    private final Jackson2JsonMessageConverter messageConverter;

    public DocumentTaskConsumer(DocumentIndexTaskMapper taskMapper,
                                 DocumentMapper documentMapper,
                                 KnowledgeBaseMapper knowledgeBaseMapper,
                                 DocumentProcessingService processingService,
                                 DocumentTaskPublisher publisher,
                                 Jackson2JsonMessageConverter messageConverter) {
        this.taskMapper = taskMapper;
        this.documentMapper = documentMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.processingService = processingService;
        this.publisher = publisher;
        this.messageConverter = messageConverter;
    }

    @RabbitListener(queues = "${intellidesk.rabbitmq.document-queue:intellidesk.document.process.q}")
    public void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        DocumentProcessMessage processMessage = null;
        try {
            processMessage = (DocumentProcessMessage) messageConverter.fromMessage(message);
            if (processMessage == null) {
                log.warn("Failed to deserialize message, nacking to DLQ");
                channel.basicNack(deliveryTag, false, false);
                return;
            }
        } catch (Exception e) {
            log.warn("Failed to deserialize message, nacking to DLQ: {}", e.getClass().getSimpleName());
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        try (TraceContext.Scope ignored = TraceContext.open(processMessage.getTraceId(),
                processMessage.getTaskId(), processMessage.getMessageId())) {
            MDC.put("documentId", String.valueOf(processMessage.getDocumentId()));
            log.info("Processing message: taskId={}, documentId={}, messageId={}",
                    processMessage.getTaskId(), processMessage.getDocumentId(), processMessage.getMessageId());
            try {
                handleMessage(processMessage, channel, deliveryTag);
            } catch (Exception e) {
                log.error("Unexpected consumer error: taskId={}, type={}", processMessage.getTaskId(), e.getClass().getSimpleName());
                // Keep the trace scope active until handling and nack have finished.
                try {
                    channel.basicNack(deliveryTag, false, false);
                } catch (IOException ex) {
                    log.error("Failed to nack message: {}", ex.getClass().getSimpleName());
                }
            }
        }
    }

    private void handleMessage(DocumentProcessMessage message, Channel channel, long deliveryTag) throws IOException {
        if (message.getSchemaVersion() != 1 || message.getTaskId() == null
                || message.getDocumentId() == null || message.getMessageId() == null) {
            channel.basicNack(deliveryTag, false, false);
            return;
        }
        // Load task
        DocumentIndexTask task = taskMapper.selectById(message.getTaskId());
        if (task == null) {
            log.info("Task {} not found, stale delivery, ACK", message.getTaskId());
            channel.basicAck(deliveryTag, false);
            return;
        }

        // Never process a payload whose identity disagrees with the durable task.
        if (!Objects.equals(task.getDocumentId(), message.getDocumentId())
                || !Objects.equals(task.getMessageId(), message.getMessageId())) {
            log.warn("Task {} payload identity mismatch, nack to DLQ", task.getId());
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        // Load document
        KnowledgeDocument document = documentMapper.selectById(task.getDocumentId());
        if (document == null) {
            log.info("Document {} not found for task {}, stale delivery, ACK",
                    task.getDocumentId(), task.getId());
            channel.basicAck(deliveryTag, false);
            return;
        }

        // Terminal states
        String taskStatus = task.getStatus();
        if (DocumentTaskStatus.SUCCEEDED.getValue().equals(taskStatus)
                || DocumentTaskStatus.CANCELLED.getValue().equals(taskStatus)) {
            log.debug("Task {} already terminal state {}, ACK", task.getId(), taskStatus);
            channel.basicAck(deliveryTag, false);
            return;
        }

        if (DocumentTaskStatus.DEAD.getValue().equals(taskStatus)) {
            log.debug("Task {} already DEAD, ACK (RecoveryScheduler handles DLQ)", task.getId());
            channel.basicAck(deliveryTag, false);
            return;
        }

        // Active PROCESSING with valid lease
        if (DocumentTaskStatus.PROCESSING.getValue().equals(taskStatus)) {
            if (task.getLeaseUntil() != null && task.getLeaseUntil().isAfter(LocalDateTime.now())) {
                log.debug("Task {} already PROCESSING with active lease until {}, ACK",
                        task.getId(), task.getLeaseUntil());
                channel.basicAck(deliveryTag, false);
                return;
            }
        }

        // RETRY_WAIT with next_retry_at not yet due
        if (DocumentTaskStatus.RETRY_WAIT.getValue().equals(taskStatus)) {
            if (task.getNextRetryAt() != null && task.getNextRetryAt().isAfter(LocalDateTime.now())) {
                log.debug("Task {} RETRY_WAIT, next_retry_at={} not yet due, ACK",
                        task.getId(), task.getNextRetryAt());
                channel.basicAck(deliveryTag, false);
                return;
            }
        }

        // Check allowed states for claim
        boolean claimable = DocumentTaskStatus.claimableValues().contains(taskStatus);

        if (!claimable) {
            log.warn("Task {} in unexpected state {} for claim, ACK", task.getId(), taskStatus);
            channel.basicAck(deliveryTag, false);
            return;
        }

        // Try atomic claim
        DocumentProcessingService.ClaimResult claimResult = processingService.claimTask(task, document);
        if (claimResult == null) {
            log.debug("Task {} claim failed (already claimed by another consumer), ACK", task.getId());
            channel.basicAck(deliveryTag, false);
            return;
        }

        int fenceAttempt = claimResult.attemptNumber();
        log.info("Task {} claimed successfully, attempt={}", task.getId(), fenceAttempt);

        // Load knowledge base
        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectById(document.getKnowledgeBaseId());
        if (knowledgeBase == null) {
            log.warn("KnowledgeBase {} not found for document {}", document.getKnowledgeBaseId(), document.getId());
            processingService.markPermanentFailure(task, document, "KB_NOT_FOUND",
                    "Knowledge base not found", fenceAttempt);
            channel.basicAck(deliveryTag, false);
            return;
        }

        // Process (parse + chunk + persist)
        try {
            processingService.process(task, document, knowledgeBase, fenceAttempt);
        } catch (Exception e) {
            log.error("Processing failed for task {}: type={}", task.getId(), e.getClass().getSimpleName());
            // DocumentProcessingService already handles marking failures internally
        }

        // Reload task to check final status
        DocumentIndexTask finalTask = taskMapper.selectById(task.getId());
        if (finalTask == null) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        String finalStatus = finalTask.getStatus();
        if (DocumentTaskStatus.RETRY_WAIT.getValue().equals(finalStatus)) {
            // Publish retry message (best effort)
            publisher.publishRetry(finalTask);
            // Keep RETRY_WAIT state (not QUEUED)
        }

        channel.basicAck(deliveryTag, false);
    }
}
