package com.intellidesk.document.mq;

import com.intellidesk.document.DocumentIndexTask;
import com.intellidesk.infrastructure.config.RabbitMqProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
public class DocumentTaskPublisher {

    private static final long CONFIRM_TIMEOUT_SECONDS = 5;

    private final RabbitTemplate rabbitTemplate;
    private final RabbitMqProperties rabbitMqProperties;

    public DocumentTaskPublisher(RabbitTemplate rabbitTemplate,
                                 RabbitMqProperties rabbitMqProperties) {
        this.rabbitTemplate = rabbitTemplate;
        this.rabbitMqProperties = rabbitMqProperties;
    }

    public PublishResult publishMain(DocumentIndexTask task) {
        DocumentProcessMessage message = DocumentProcessMessage.create(
                task.getId(), task.getDocumentId(), task.getMessageId());
        return publish(
                rabbitMqProperties.getDocumentExchange(),
                rabbitMqProperties.getDocumentRoutingKey(),
                message);
    }

    public PublishResult publishRetry(DocumentIndexTask task) {
        DocumentProcessMessage message = DocumentProcessMessage.create(
                task.getId(), task.getDocumentId(), task.getMessageId());
        return publish(
                rabbitMqProperties.getDeadLetterExchange(),
                rabbitMqProperties.getRetryRoutingKey(),
                message);
    }

    public PublishResult publishDlq(DocumentIndexTask task) {
        DocumentProcessMessage message = DocumentProcessMessage.create(
                task.getId(), task.getDocumentId(), task.getMessageId());
        return publish(
                rabbitMqProperties.getDeadLetterExchange(),
                rabbitMqProperties.getDeadLetterRoutingKey(),
                message);
    }

    private PublishResult publish(String exchange, String routingKey, DocumentProcessMessage message) {
        String correlationId = UUID.randomUUID().toString();
        CorrelationData correlationData = new CorrelationData(correlationId);

        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, message, correlationData);
        } catch (Exception e) {
            log.warn("Failed to publish message to exchange={}, routingKey={}", exchange, routingKey, e);
            return PublishResult.failed("Publish exception: " + e.getMessage());
        }

        try {
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!confirm.isAck()) {
                return PublishResult.nacked(confirm.getReason());
            }

            // RabbitTemplate uses its internal return-correlation header to attach
            // an unroutable mandatory message to this exact CorrelationData. Spring
            // guarantees that getReturned() is populated before an ACK confirm
            // future completes, so ACK alone never means routing succeeded.
            if (correlationData.getReturned() != null) {
                return PublishResult.returned();
            }

            return PublishResult.acked();
        } catch (TimeoutException e) {
            log.warn("Publish confirm timeout for correlationId={}", correlationId);
            return PublishResult.timeout();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Publish confirm interrupted for correlationId={}", correlationId, e);
            return PublishResult.failed("Confirm interrupted");
        } catch (Exception e) {
            log.warn("Failed while awaiting publish confirm for correlationId={}", correlationId, e);
            return PublishResult.failed("Confirm failed: " + e.getMessage());
        }
    }

    public record PublishResult(boolean isAcked, boolean isReturned, boolean isTimeout, String errorMessage) {
        public boolean isSuccess() {
            return isAcked && !isReturned && !isTimeout;
        }

        public static PublishResult acked() {
            return new PublishResult(true, false, false, null);
        }

        public static PublishResult nacked(String cause) {
            return new PublishResult(false, false, false, cause);
        }

        public static PublishResult returned() {
            return new PublishResult(true, true, false, null);
        }

        public static PublishResult timeout() {
            return new PublishResult(false, false, true, "confirm timeout");
        }

        public static PublishResult failed(String error) {
            return new PublishResult(false, false, false, error);
        }
    }
}
