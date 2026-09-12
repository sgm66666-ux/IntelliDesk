package com.intellidesk.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Configuration
@Profile("!test")
public class RabbitMqConfig {

    private final RabbitMqProperties rabbitMqProperties;

    public RabbitMqConfig(RabbitMqProperties rabbitMqProperties) {
        this.rabbitMqProperties = rabbitMqProperties;
    }

    @Bean
    public DirectExchange documentExchange() {
        return ExchangeBuilder.directExchange(rabbitMqProperties.getDocumentExchange())
                .durable(true)
                .build();
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return ExchangeBuilder.directExchange(rabbitMqProperties.getDeadLetterExchange())
                .durable(true)
                .build();
    }

    @Bean
    public Queue documentQueue() {
        return QueueBuilder.durable(rabbitMqProperties.getDocumentQueue())
                .withArgument("x-dead-letter-exchange", rabbitMqProperties.getDeadLetterExchange())
                .withArgument("x-dead-letter-routing-key", rabbitMqProperties.getDeadLetterRoutingKey())
                .build();
    }

    @Bean
    public Queue retryQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", rabbitMqProperties.getDocumentExchange());
        args.put("x-dead-letter-routing-key", rabbitMqProperties.getDocumentRoutingKey());
        args.put("x-message-ttl", rabbitMqProperties.getRetryTtlMilliseconds());
        return new Queue(rabbitMqProperties.getRetryQueue(), true, false, false, args);
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(rabbitMqProperties.getDeadLetterQueue()).build();
    }

    @Bean
    public Binding documentBinding() {
        return BindingBuilder.bind(documentQueue())
                .to(documentExchange())
                .with(rabbitMqProperties.getDocumentRoutingKey());
    }

    @Bean
    public Binding retryBinding() {
        return BindingBuilder.bind(retryQueue())
                .to(deadLetterExchange())
                .with(rabbitMqProperties.getRetryRoutingKey());
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue())
                .to(deadLetterExchange())
                .with(rabbitMqProperties.getDeadLetterRoutingKey());
    }

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                          Jackson2JsonMessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        template.setMandatory(true);

        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (correlationData == null) {
                return;
            }
            if (ack) {
                log.debug("Message confirmed: {}", correlationData.getId());
            } else {
                log.warn("Message nacked: {}, cause: {}", correlationData.getId(), cause);
            }
        });

        template.setReturnsCallback(returned -> {
            // RabbitTemplate associates the ReturnedMessage with its originating
            // CorrelationData. DocumentTaskPublisher reads CorrelationData#getReturned.
            log.warn("Message returned: exchange={}, routingKey={}, replyCode={}, replyText={}",
                    returned.getExchange(), returned.getRoutingKey(),
                    returned.getReplyCode(), returned.getReplyText());
        });

        return template;
    }
}
