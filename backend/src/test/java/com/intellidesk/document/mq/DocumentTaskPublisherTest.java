package com.intellidesk.document.mq;

import com.intellidesk.document.DocumentIndexTask;
import com.intellidesk.infrastructure.config.RabbitMqProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class DocumentTaskPublisherTest {

    private RabbitTemplate rabbitTemplate;
    private DocumentTaskPublisher publisher;
    private DocumentIndexTask task;

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        publisher = new DocumentTaskPublisher(rabbitTemplate, new RabbitMqProperties());

        task = new DocumentIndexTask();
        task.setId(101L);
        task.setDocumentId(202L);
        task.setMessageId(UUID.randomUUID().toString());
    }

    @Test
    void routedAckIsSuccess() {
        completePublishWith(correlationData ->
                correlationData.getFuture().complete(new CorrelationData.Confirm(true, null)));

        DocumentTaskPublisher.PublishResult result = publisher.publishMain(task);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isAcked()).isTrue();
        assertThat(result.isReturned()).isFalse();
    }

    @Test
    void unroutableAckWithReturnedMessageIsFailure() {
        completePublishWith(correlationData -> {
            correlationData.setReturned(returnedMessage());
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
        });

        DocumentTaskPublisher.PublishResult result = publisher.publishMain(task);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isAcked()).isTrue();
        assertThat(result.isReturned()).isTrue();
    }

    @Test
    void nackIsFailure() {
        completePublishWith(correlationData ->
                correlationData.getFuture().complete(new CorrelationData.Confirm(false, "broker nack")));

        DocumentTaskPublisher.PublishResult result = publisher.publishMain(task);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isAcked()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("broker nack");
    }

    @Test
    void returnAndConfirmFromDifferentCallbackThreadsRemainCorrelated() {
        completePublishWith(correlationData -> {
            CountDownLatch returnAttached = new CountDownLatch(1);
            CompletableFuture<Void> returnCallback = CompletableFuture.runAsync(() -> {
                correlationData.setReturned(returnedMessage());
                returnAttached.countDown();
            });
            CompletableFuture<Void> confirmCallback = CompletableFuture.runAsync(() -> {
                try {
                    assertThat(returnAttached.await(1, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            });
            CompletableFuture.allOf(returnCallback, confirmCallback).join();
        });

        DocumentTaskPublisher.PublishResult result = publisher.publishMain(task);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isReturned()).isTrue();
    }

    @Test
    void duplicateAndUnknownConfirmDoNotAffectAnotherPublish() {
        CorrelationData unknown = new CorrelationData("unknown");
        unknown.setReturned(returnedMessage());
        unknown.getFuture().complete(new CorrelationData.Confirm(true, null));

        AtomicInteger invocation = new AtomicInteger();
        completePublishWith(correlationData -> {
            int current = invocation.incrementAndGet();
            boolean firstCompletion = correlationData.getFuture()
                    .complete(new CorrelationData.Confirm(true, null));
            boolean duplicateCompletion = correlationData.getFuture()
                    .complete(new CorrelationData.Confirm(false, "duplicate"));
            assertThat(firstCompletion).isTrue();
            assertThat(duplicateCompletion).isFalse();
            assertThat(current).isBetween(1, 2);
        });

        DocumentTaskPublisher.PublishResult first = publisher.publishMain(task);
        DocumentTaskPublisher.PublishResult second = publisher.publishMain(task);

        assertThat(first.isSuccess()).isTrue();
        assertThat(second.isSuccess()).isTrue();
        assertThat(invocation).hasValue(2);
    }

    private void completePublishWith(java.util.function.Consumer<CorrelationData> callback) {
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            callback.accept(correlationData);
            return null;
        }).when(rabbitTemplate).convertAndSend(
                anyString(), anyString(), any(DocumentProcessMessage.class), any(CorrelationData.class));
    }

    private ReturnedMessage returnedMessage() {
        return new ReturnedMessage(
                new Message(new byte[0], new MessageProperties()),
                312,
                "NO_ROUTE",
                "test.exchange",
                "missing.route");
    }
}
