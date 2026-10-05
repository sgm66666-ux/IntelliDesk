package com.intellidesk.document.mq;

import com.intellidesk.document.DocumentIndexTask;
import com.intellidesk.infrastructure.config.RabbitMqConfig;
import com.intellidesk.infrastructure.config.RabbitMqProperties;
import org.junit.jupiter.api.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Testcontainers
class DocumentRabbitTopologyIntegrationTest {
    @Container static final RabbitMQContainer broker = new RabbitMQContainer("rabbitmq:3.13-management");
    private CachingConnectionFactory connection;
    private RabbitMqProperties properties;
    private RabbitTemplate rabbit;
    private DocumentTaskPublisher publisher;
    @BeforeEach void setup() {
        connection = new CachingConnectionFactory(broker.getHost(),broker.getAmqpPort());
        connection.setUsername(broker.getAdminUsername()); connection.setPassword(broker.getAdminPassword());
        connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connection.setPublisherReturns(true);
        String prefix = "document-test-" + UUID.randomUUID();
        properties = new RabbitMqProperties();
        properties.setDocumentExchange(prefix+".main.x"); properties.setDocumentQueue(prefix+".main.q");
        properties.setRetryExchange(prefix+".retry.x"); properties.setRetryQueue(prefix+".retry.q");
        properties.setDeadLetterExchange(prefix+".dead.x"); properties.setDeadLetterQueue(prefix+".dead.q");
        properties.validate();
        var config = new RabbitMqConfig(properties); var admin = new RabbitAdmin(connection);
        admin.declareExchange(config.documentExchange()); admin.declareExchange(config.retryExchange()); admin.declareExchange(config.deadLetterExchange());
        admin.declareQueue(config.documentQueue()); admin.declareQueue(config.retryQueue()); admin.declareQueue(config.deadLetterQueue());
        admin.declareBinding(config.documentBinding()); admin.declareBinding(config.retryBinding()); admin.declareBinding(config.deadLetterBinding());
        rabbit = config.rabbitTemplate(connection,new Jackson2JsonMessageConverter());
        publisher = new DocumentTaskPublisher(rabbit,properties);
    }
    @AfterEach void close() { connection.destroy(); }
    private DocumentIndexTask task() {
        var task = new DocumentIndexTask(); task.setId(10L); task.setDocumentId(20L);
        task.setMessageId(UUID.randomUUID().toString()); task.setTraceId("broker-origin-trace"); return task;
    }
    @Test void retryUsesDistinctExchangeAndRealThirtySecondTtl() {
        assertThat(properties.getRetryTtlMilliseconds()).isEqualTo(30000);
        assertThat(publisher.publishRetry(task()).isSuccess()).isTrue();
        assertThat(rabbit.receive(properties.getDocumentQueue(),100)).isNull();
        await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            var message = rabbit.receive(properties.getDocumentQueue(),100);
            assertThat(message).isNotNull();
            assertThat(new String(message.getBody(),java.nio.charset.StandardCharsets.UTF_8)).contains("broker-origin-trace","messageId","documentId");
        });
    }
    @Test void rejectedMainDeliveryActuallyRoutesToDlq() throws Exception {
        assertThat(publisher.publishMain(task()).isSuccess()).isTrue();
        var channel = connection.createConnection().createChannel(false);
        try {
            var delivery = channel.basicGet(properties.getDocumentQueue(),false); assertThat(delivery).isNotNull();
            channel.basicNack(delivery.getEnvelope().getDeliveryTag(),false,false);
            assertThat(rabbit.receive(properties.getDeadLetterQueue(),3000)).isNotNull();
        } finally { channel.close(); }
    }
    @Test void exhaustedTaskDiagnosticCanBePublishedToDlq() {
        var task = task(); task.setStatus("DEAD"); task.setAttemptCount(3); task.setMaxAttempts(3);
        assertThat(publisher.publishDlq(task).isSuccess()).isTrue();
        assertThat(rabbit.receive(properties.getDeadLetterQueue(),3000)).isNotNull();
    }
}
