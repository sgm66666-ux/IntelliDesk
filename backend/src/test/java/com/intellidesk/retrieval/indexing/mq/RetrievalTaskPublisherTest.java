package com.intellidesk.retrieval.indexing.mq;

import com.intellidesk.infrastructure.config.RetrievalRabbitMqProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RetrievalTaskPublisher")
class RetrievalTaskPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    private RetrievalRabbitMqProperties rabbitMqProperties;
    private RetrievalTaskPublisher publisher;

    private RetrievalIndexMessage message;

    @BeforeEach
    void setUp() {
        rabbitMqProperties = new RetrievalRabbitMqProperties();
        publisher = new RetrievalTaskPublisher(rabbitTemplate, rabbitMqProperties);

        message = RetrievalIndexMessage.create(
                UUID.randomUUID().toString(), 100L, 200L, 1);
    }

    @Nested
    @DisplayName("publish")
    class PublishTests {

        @Test
        @DisplayName("should return success on ack and no return")
        void shouldReturnSuccessOnAckAndNoReturn() {
            completePublishWith(correlationData ->
                    correlationData.getFuture().complete(new CorrelationData.Confirm(true, null)));

            RetrievalTaskPublisher.PublishResult result = publisher.publish(message);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isAcked()).isTrue();
            assertThat(result.isReturned()).isFalse();
            assertThat(result.isTimeout()).isFalse();
        }

        @Test
        @DisplayName("should return nacked on nack")
        void shouldReturnNackedOnNack() {
            completePublishWith(correlationData ->
                    correlationData.getFuture().complete(
                            new CorrelationData.Confirm(false, "broker nack")));

            RetrievalTaskPublisher.PublishResult result = publisher.publish(message);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.isAcked()).isFalse();
            assertThat(result.errorMessage()).isEqualTo("broker nack");
        }

        @Test
        @DisplayName("should return returned on return")
        void shouldReturnReturnedOnReturn() {
            completePublishWith(correlationData -> {
                correlationData.setReturned(new ReturnedMessage(
                        new Message(new byte[0], new MessageProperties()),
                        312, "NO_ROUTE", "test.exchange", "missing.route"));
                correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            });

            RetrievalTaskPublisher.PublishResult result = publisher.publish(message);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.isAcked()).isTrue();
            assertThat(result.isReturned()).isTrue();
        }

        @Test
        @DisplayName("should return timeout on timeout")
        void shouldReturnTimeoutOnTimeout() {
            doAnswer(invocation -> null).when(rabbitTemplate).convertAndSend(
                    anyString(), anyString(), any(RetrievalIndexMessage.class), any(CorrelationData.class));

            RetrievalTaskPublisher.PublishResult result = publisher.publish(message);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.isTimeout()).isTrue();
            assertThat(result.errorMessage()).contains("confirm timeout");
        }

        @Test
        @DisplayName("should return failed on publish exception")
        void shouldReturnFailedOnException() {
            doThrow(new RuntimeException("connection lost"))
                    .when(rabbitTemplate).convertAndSend(
                            anyString(), anyString(), any(RetrievalIndexMessage.class), any(CorrelationData.class));

            RetrievalTaskPublisher.PublishResult result = publisher.publish(message);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.errorMessage()).contains("Publish exception");
        }
    }

    @Nested
    @DisplayName("publishRetry")
    class PublishRetryTests {

        @Test
        @DisplayName("should publish to retry exchange and routing key")
        void shouldPublishToRetryExchangeAndRoutingKey() {
            completePublishWith(correlationData ->
                    correlationData.getFuture().complete(new CorrelationData.Confirm(true, null)));

            RetrievalTaskPublisher.PublishResult result = publisher.publishRetry(message);

            assertThat(result.isSuccess()).isTrue();
            verify(rabbitTemplate).convertAndSend(
                    eq(rabbitMqProperties.getRetryExchange()),
                    eq(rabbitMqProperties.getRetryRoutingKey()),
                    eq(message),
                    any(CorrelationData.class));
        }
    }

    private void completePublishWith(java.util.function.Consumer<CorrelationData> callback) {
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            callback.accept(correlationData);
            return null;
        }).when(rabbitTemplate).convertAndSend(
                anyString(), anyString(), any(RetrievalIndexMessage.class), any(CorrelationData.class));
    }
}