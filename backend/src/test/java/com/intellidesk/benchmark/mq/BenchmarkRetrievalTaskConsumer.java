package com.intellidesk.benchmark.mq;

import com.intellidesk.retrieval.indexing.RetrievalIndexingService;
import com.intellidesk.retrieval.indexing.mq.RetrievalIndexMessage;
import com.intellidesk.retrieval.task.DocumentRetrievalTask;
import com.intellidesk.retrieval.task.DocumentRetrievalTaskMapper;
import com.intellidesk.retrieval.task.RetrievalTaskStatus;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Benchmark-only retrieval indexing task consumer.
 *
 * <p>Active only under the {@code bench} profile. Mirrors the production
 * {@link com.intellidesk.retrieval.indexing.mq.RetrievalTaskConsumer} so that
 * document processing benchmarks exercise the full embedding/indexing path.
 */
@Slf4j
@Component
@Profile("bench & test")
public class BenchmarkRetrievalTaskConsumer {

    private final DocumentRetrievalTaskMapper taskMapper;
    private final RetrievalIndexingService indexingService;
    private final Jackson2JsonMessageConverter messageConverter;

    public BenchmarkRetrievalTaskConsumer(DocumentRetrievalTaskMapper taskMapper,
                                           RetrievalIndexingService indexingService,
                                           Jackson2JsonMessageConverter messageConverter) {
        this.taskMapper = taskMapper;
        this.indexingService = indexingService;
        this.messageConverter = messageConverter;
    }

    @RabbitListener(queues = "${intellidesk.retrieval.rabbitmq.queue:intellidesk.retrieval.index.q}")
    public void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        RetrievalIndexMessage indexMessage;
        try {
            indexMessage = (RetrievalIndexMessage) messageConverter.fromMessage(message);
            if (indexMessage == null) {
                channel.basicNack(deliveryTag, false, false);
                return;
            }
        } catch (Exception e) {
            log.warn("Benchmark retrieval consumer failed to deserialize message", e);
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        try {
            handleMessage(indexMessage, channel, deliveryTag);
        } catch (Exception e) {
            log.error("Benchmark retrieval consumer unexpected error for task {}", indexMessage.taskId(), e);
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (IOException ex) {
                log.error("Failed to nack retrieval message", ex);
            }
        }
    }

    private void handleMessage(RetrievalIndexMessage message, Channel channel, long deliveryTag) throws IOException {
        DocumentRetrievalTask task = taskMapper.selectById(message.taskId());
        if (task == null) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        if (message.generation() < task.getGeneration()) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        String taskStatus = task.getStatus();
        if (RetrievalTaskStatus.READY.getValue().equals(taskStatus)
                || RetrievalTaskStatus.CANCELLED.getValue().equals(taskStatus)
                || RetrievalTaskStatus.FAILED.getValue().equals(taskStatus)) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            indexingService.claimAndProcess(task);
        } catch (Exception e) {
            log.error("Benchmark retrieval indexing failed for task {}", task.getId(), e);
        }

        channel.basicAck(deliveryTag, false);
    }
}
