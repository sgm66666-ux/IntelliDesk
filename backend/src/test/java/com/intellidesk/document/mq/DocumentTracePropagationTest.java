package com.intellidesk.document.mq;

import com.intellidesk.common.TraceContext;
import com.intellidesk.document.*;
import com.intellidesk.infrastructure.config.*;
import com.intellidesk.knowledge.KnowledgeBaseMapper;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentTracePropagationTest {
    @AfterEach void clean() { TraceContext.clear(); MDC.clear(); }
    @Test void createdTaskStoresRequestTrace() {
        var mapper = mock(DocumentIndexTaskMapper.class);
        try (var scope = TraceContext.open("origin-request",null,null)) {
            var task = new DocumentTaskService(mapper,new DocumentIngestionProperties()).createPendingTask(2L,3L);
            assertThat(task.getTraceId()).isEqualTo("origin-request");
            assertThat(DocumentProcessMessage.create(1L,2L,"message-1").getTraceId()).isEqualTo("origin-request");
        }
    }
    @Test void schedulerPublisherRetainsStoredTraceAcrossAllQueues() {
        var rabbit = mock(RabbitTemplate.class);
        doAnswer(inv -> { ((CorrelationData)inv.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(true,null)); return null; })
                .when(rabbit).convertAndSend(anyString(),anyString(),any(Object.class),any(CorrelationData.class));
        var publisher = new DocumentTaskPublisher(rabbit,new RabbitMqProperties());
        var task = new DocumentIndexTask(); task.setId(1L); task.setDocumentId(2L); task.setMessageId("message-1"); task.setTraceId("origin-request");
        assertThat(publisher.publishMain(task).isSuccess()).isTrue(); assertThat(publisher.publishRetry(task).isSuccess()).isTrue(); assertThat(publisher.publishDlq(task).isSuccess()).isTrue();
        var payload = ArgumentCaptor.forClass(Object.class);
        verify(rabbit,times(3)).convertAndSend(anyString(),anyString(),payload.capture(),any(CorrelationData.class));
        payload.getAllValues().forEach(value -> assertThat(((DocumentProcessMessage)value).getTraceId()).isEqualTo("origin-request"));
        assertThat(TraceContext.peekTraceId()).isNull();
    }
    @Test void consumerScopesTraceTaskAndMessageThenCleansWorker() throws Exception {
        var mapper = mock(DocumentIndexTaskMapper.class); var converter = mock(Jackson2JsonMessageConverter.class);
        var channel = mock(Channel.class);
        var consumer = new DocumentTaskConsumer(mapper,mock(DocumentMapper.class),mock(KnowledgeBaseMapper.class),mock(DocumentProcessingService.class),mock(DocumentTaskPublisher.class),converter);
        var props = new MessageProperties(); props.setDeliveryTag(1L); var raw = new Message(new byte[0],props);
        var msg = DocumentProcessMessage.create(1L,2L,"message-1"); msg.setTraceId("origin-request");
        when(converter.fromMessage(raw)).thenReturn(msg);
        when(mapper.selectById(1L)).thenAnswer(inv -> {
            assertThat(TraceContext.getTraceId()).isEqualTo("origin-request"); assertThat(MDC.get("taskId")).isEqualTo("1");
            assertThat(MDC.get("messageId")).isEqualTo("message-1"); assertThat(MDC.get("documentId")).isEqualTo("2"); return null;
        });
        consumer.onMessage(raw,channel); verify(channel).basicAck(1L,false);
        assertThat(TraceContext.peekTraceId()).isNull(); assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }
}
