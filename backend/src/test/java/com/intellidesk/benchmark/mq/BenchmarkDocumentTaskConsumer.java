package com.intellidesk.benchmark.mq;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.intellidesk.document.DocumentIndexTask;
import com.intellidesk.document.DocumentIndexTaskMapper;
import com.intellidesk.document.DocumentMapper;
import com.intellidesk.document.DocumentProcessingService;
import com.intellidesk.document.KnowledgeDocument;
import com.intellidesk.document.model.DocumentStatus;
import com.intellidesk.document.model.DocumentTaskStatus;
import com.intellidesk.document.mq.DocumentProcessMessage;
import com.intellidesk.document.mq.DocumentTaskPublisher;
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

/**
 * Benchmark-only document task consumer.
 *
 * <p>Active only under the {@code bench} profile. Mirrors the production
 * {@link com.intellidesk.document.mq.DocumentTaskConsumer} but is not excluded
 * by the {@code test} profile, so B-class document-processing benchmarks can
 * exercise the real backend → PostgreSQL → MinIO → RabbitMQ → consumer path
 * without modifying production code.
 */
@Slf4j
@Component
@Profile("bench & test")
public class BenchmarkDocumentTaskConsumer {

    private final DocumentIndexTaskMapper taskMapper;
    private final DocumentMapper documentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentProcessingService processingService;
    private final DocumentTaskPublisher publisher;
    private final Jackson2JsonMessageConverter messageConverter;

    public BenchmarkDocumentTaskConsumer(DocumentIndexTaskMapper taskMapper,
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
        DocumentProcessMessage processMessage;
        try {
            processMessage = (DocumentProcessMessage) messageConverter.fromMessage(message);
            if (processMessage == null) {
                channel.basicNack(deliveryTag, false, false);
                return;
            }
        } catch (Exception e) {
            log.warn("Benchmark consumer failed to deserialize message", e);
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        try {
            handleMessage(processMessage, channel, deliveryTag);
        } catch (Exception e) {
            log.error("Benchmark consumer unexpected error for task {}", processMessage.getTaskId(), e);
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (IOException ex) {
                log.error("Failed to nack message", ex);
            }
        }
    }

    private void handleMessage(DocumentProcessMessage message, Channel channel, long deliveryTag) throws IOException {
        DocumentIndexTask task = taskMapper.selectById(message.getTaskId());
        if (task == null) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        KnowledgeDocument document = documentMapper.selectById(task.getDocumentId());
        if (document == null) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        String taskStatus = task.getStatus();
        if (DocumentTaskStatus.SUCCEEDED.getValue().equals(taskStatus)
                || DocumentTaskStatus.CANCELLED.getValue().equals(taskStatus)
                || DocumentTaskStatus.DEAD.getValue().equals(taskStatus)) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        if (DocumentTaskStatus.PROCESSING.getValue().equals(taskStatus)) {
            if (task.getLeaseUntil() != null && task.getLeaseUntil().isAfter(LocalDateTime.now())) {
                channel.basicAck(deliveryTag, false);
                return;
            }
        }

        if (DocumentTaskStatus.RETRY_WAIT.getValue().equals(taskStatus)) {
            if (task.getNextRetryAt() != null && task.getNextRetryAt().isAfter(LocalDateTime.now())) {
                channel.basicAck(deliveryTag, false);
                return;
            }
        }

        boolean claimable = DocumentTaskStatus.PENDING.getValue().equals(taskStatus)
                || DocumentTaskStatus.QUEUED.getValue().equals(taskStatus)
                || DocumentTaskStatus.RETRY_WAIT.getValue().equals(taskStatus);
        if (!claimable) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        DocumentProcessingService.ClaimResult claimResult = processingService.claimTask(task, document);
        if (claimResult == null) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        int fenceAttempt = claimResult.attemptNumber();
        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectById(document.getKnowledgeBaseId());
        if (knowledgeBase == null) {
            processingService.markPermanentFailure(task, document, "KB_NOT_FOUND",
                    "Knowledge base not found", fenceAttempt);
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            processingService.process(task, document, knowledgeBase, fenceAttempt);
        } catch (Exception e) {
            log.error("Benchmark consumer processing failed for task {}", task.getId(), e);
        }

        DocumentIndexTask finalTask = taskMapper.selectById(task.getId());
        if (finalTask != null
                && DocumentTaskStatus.RETRY_WAIT.getValue().equals(finalTask.getStatus())) {
            publisher.publishRetry(finalTask);
        }

        channel.basicAck(deliveryTag, false);
    }
}
