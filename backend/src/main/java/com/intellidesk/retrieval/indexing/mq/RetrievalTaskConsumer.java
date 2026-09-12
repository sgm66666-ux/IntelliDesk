package com.intellidesk.retrieval.indexing.mq;

import com.intellidesk.retrieval.indexing.RetrievalIndexingService;
import com.intellidesk.retrieval.task.DocumentRetrievalTask;
import com.intellidesk.retrieval.task.DocumentRetrievalTaskMapper;
import com.intellidesk.retrieval.task.RetrievalTaskStatus;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;

@Slf4j
@Component
@Profile("!test")
@ConditionalOnProperty(name = "intellidesk.retrieval.retrieval-worker-enabled", havingValue = "true", matchIfMissing = false)
public class RetrievalTaskConsumer {

    private final DocumentRetrievalTaskMapper taskMapper;
    private final RetrievalIndexingService indexingService;
    private final Jackson2JsonMessageConverter messageConverter;

    public RetrievalTaskConsumer(DocumentRetrievalTaskMapper taskMapper,
                                  RetrievalIndexingService indexingService,
                                  Jackson2JsonMessageConverter messageConverter) {
        this.taskMapper = taskMapper;
        this.indexingService = indexingService;
        this.messageConverter = messageConverter;
    }

    @RabbitListener(queues = "${intellidesk.retrieval.rabbitmq.queue:intellidesk.retrieval.index.q}")
    public void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        RetrievalIndexMessage indexMessage = null;
        try {
            indexMessage = (RetrievalIndexMessage) messageConverter.fromMessage(message);
            if (indexMessage == null) {
                log.warn("Failed to deserialize retrieval message, nacking to DLQ");
                channel.basicNack(deliveryTag, false, false);
                return;
            }
        } catch (Exception e) {
            log.warn("Failed to deserialize retrieval message, nacking to DLQ", e);
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        log.info("Processing retrieval message: taskId={}, documentId={}, generation={}",
                indexMessage.taskId(), indexMessage.documentId(), indexMessage.generation());

        try {
            handleMessage(indexMessage, channel, deliveryTag);
        } catch (Exception e) {
            log.error("Unexpected error processing retrieval message taskId={}", indexMessage.taskId(), e);
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (IOException ex) {
                log.error("Failed to nack retrieval message", ex);
            }
        }
    }

    private void handleMessage(RetrievalIndexMessage message, Channel channel, long deliveryTag) throws IOException {
        // Load task
        DocumentRetrievalTask task = taskMapper.selectById(message.taskId());
        if (task == null) {
            log.info("Retrieval task {} not found, stale delivery, ACK", message.taskId());
            channel.basicAck(deliveryTag, false);
            return;
        }

        // Check generation: if message has old generation, skip
        if (message.generation() < task.getGeneration()) {
            log.info("Retrieval task {} message generation {} < task generation {}, stale delivery, ACK",
                    task.getId(), message.generation(), task.getGeneration());
            channel.basicAck(deliveryTag, false);
            return;
        }

        // Terminal states
        String taskStatus = task.getStatus();
        if (RetrievalTaskStatus.READY.getValue().equals(taskStatus)
                || RetrievalTaskStatus.CANCELLED.getValue().equals(taskStatus)) {
            log.debug("Retrieval task {} already terminal state {}, ACK", task.getId(), taskStatus);
            channel.basicAck(deliveryTag, false);
            return;
        }

        if (RetrievalTaskStatus.FAILED.getValue().equals(taskStatus)) {
            log.debug("Retrieval task {} already FAILED, ACK", task.getId());
            channel.basicAck(deliveryTag, false);
            return;
        }

        // Active PROCESSING with valid lease
        if (RetrievalTaskStatus.PROCESSING.getValue().equals(taskStatus)) {
            if (task.getLeaseUntil() != null && task.getLeaseUntil().isAfter(LocalDateTime.now())) {
                log.debug("Retrieval task {} already PROCESSING with active lease until {}, ACK",
                        task.getId(), task.getLeaseUntil());
                channel.basicAck(deliveryTag, false);
                return;
            }
        }

        // RETRY_WAIT with next_retry_at not yet due
        if (RetrievalTaskStatus.RETRY_WAIT.getValue().equals(taskStatus)) {
            if (task.getNextRetryAt() != null && task.getNextRetryAt().isAfter(LocalDateTime.now())) {
                log.debug("Retrieval task {} RETRY_WAIT, next_retry_at={} not yet due, ACK",
                        task.getId(), task.getNextRetryAt());
                channel.basicAck(deliveryTag, false);
                return;
            }
        }

        // Check allowed states for claim
        boolean claimable = RetrievalTaskStatus.PENDING.getValue().equals(taskStatus)
                || RetrievalTaskStatus.QUEUED.getValue().equals(taskStatus)
                || RetrievalTaskStatus.RETRY_WAIT.getValue().equals(taskStatus);

        if (!claimable) {
            log.warn("Retrieval task {} in unexpected state {} for claim, ACK", task.getId(), taskStatus);
            channel.basicAck(deliveryTag, false);
            return;
        }

        // Try claim and process
        indexingService.claimAndProcess(task);

        channel.basicAck(deliveryTag, false);
    }
}