package com.intellidesk.retrieval.indexing.mq;

import com.intellidesk.infrastructure.config.RetrievalRabbitMqProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
@Profile("!test")
public class RetrievalTaskPublisher {

    private static final long CONFIRM_TIMEOUT_SECONDS = 5;

    private final RabbitTemplate rabbitTemplate;
    private final RetrievalRabbitMqProperties rabbitMqProperties;

    public RetrievalTaskPublisher(RabbitTemplate rabbitTemplate,
                                  RetrievalRabbitMqProperties rabbitMqProperties) {
        this.rabbitTemplate = rabbitTemplate;
        this.rabbitMqProperties = rabbitMqProperties;
    }

    public PublishResult publish(RetrievalIndexMessage message) {
        return publish(
                rabbitMqProperties.getExchange(),
                rabbitMqProperties.getRoutingKey(),
                message);
    }

    public PublishResult publishRetry(RetrievalIndexMessage message) {
        return publish(
                rabbitMqProperties.getRetryExchange(),
                rabbitMqProperties.getRetryRoutingKey(),
                message);
    }

    private PublishResult publish(String exchange, String routingKey, RetrievalIndexMessage message) {
        String correlationId = UUID.randomUUID().toString();
        CorrelationData correlationData = new CorrelationData(correlationId);

        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, message, correlationData);
        } catch (Exception e) {
            log.warn("Failed to publish retrieval message to exchange={}, routingKey={}", exchange, routingKey, e);
            return PublishResult.failed("Publish exception: " + e.getMessage());
        }

        try {
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!confirm.isAck()) {
                return PublishResult.nacked(confirm.getReason());
            }

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