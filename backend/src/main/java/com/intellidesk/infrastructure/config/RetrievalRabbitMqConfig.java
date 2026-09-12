package com.intellidesk.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Configuration
@Profile("!test")
public class RetrievalRabbitMqConfig {

    private final RetrievalRabbitMqProperties properties;

    public RetrievalRabbitMqConfig(RetrievalRabbitMqProperties properties) {
        this.properties = properties;
    }

    // --- Exchanges ---

    @Bean
    public DirectExchange retrievalExchange() {
        return ExchangeBuilder.directExchange(properties.getExchange())
                .durable(true)
                .build();
    }

    @Bean
    public DirectExchange retrievalDeadLetterExchange() {
        return ExchangeBuilder.directExchange(properties.getRetryExchange())
                .durable(true)
                .build();
    }

    // --- Queues ---

    @Bean
    public Queue retrievalQueue() {
        return QueueBuilder.durable(properties.getQueue())
                .withArgument("x-dead-letter-exchange", properties.getRetryExchange())
                .withArgument("x-dead-letter-routing-key", properties.getRetryRoutingKey())
                .build();
    }

    @Bean
    public Queue retrievalRetryQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", properties.getExchange());
        args.put("x-dead-letter-routing-key", properties.getRoutingKey());
        args.put("x-message-ttl", properties.getRetryTtlMilliseconds());
        return new Queue(properties.getRetryQueue(), true, false, false, args);
    }

    @Bean
    public Queue retrievalDeadLetterQueue() {
        return QueueBuilder.durable(properties.getDeadLetterQueue()).build();
    }

    // --- Bindings ---

    @Bean
    public Binding retrievalBinding() {
        return BindingBuilder.bind(retrievalQueue())
                .to(retrievalExchange())
                .with(properties.getRoutingKey());
    }

    @Bean
    public Binding retrievalRetryBinding() {
        return BindingBuilder.bind(retrievalRetryQueue())
                .to(retrievalDeadLetterExchange())
                .with(properties.getRetryRoutingKey());
    }

    @Bean
    public Binding retrievalDeadLetterBinding() {
        return BindingBuilder.bind(retrievalDeadLetterQueue())
                .to(retrievalDeadLetterExchange())
                .with(properties.getDeadLetterRoutingKey());
    }
}