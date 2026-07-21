package com.emergencylending.loan.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    public static final String LOAN_EVENTS_EXCHANGE = "loan.events";

    @Bean
    public TopicExchange loanEventsExchange() {
        return new TopicExchange(LOAN_EVENTS_EXCHANGE);
    }

    @Bean
    public JacksonJsonMessageConverter jacksonJsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    /**
     * Override the default RabbitTemplate to use JSON serialisation.
     * Without this, the default SimpleMessageConverter sends Java-serialised bytes
     * which inventory-service cannot deserialise without sharing classpath types.
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         JacksonJsonMessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        return template;
    }
}
