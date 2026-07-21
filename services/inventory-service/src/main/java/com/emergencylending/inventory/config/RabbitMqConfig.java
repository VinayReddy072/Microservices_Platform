package com.emergencylending.inventory.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    public static final String LOAN_EVENTS_EXCHANGE = "loan.events";
    public static final String INVENTORY_QUEUE = "inventory.loan-events";
    public static final String ROUTING_KEY_APPROVED = "loan.approved";
    public static final String ROUTING_KEY_RETURNED = "loan.returned";

    @Bean
    public TopicExchange loanEventsExchange() {
        return new TopicExchange(LOAN_EVENTS_EXCHANGE);
    }

    /**
     * Durable queue so messages survive a broker restart.
     * Declared here because inventory-service owns the consumer.
     */
    @Bean
    public Queue inventoryLoanEventsQueue() {
        return new Queue(INVENTORY_QUEUE, true);
    }

    @Bean
    public Binding bindingApproved(Queue inventoryLoanEventsQueue, TopicExchange loanEventsExchange) {
        return BindingBuilder.bind(inventoryLoanEventsQueue)
                .to(loanEventsExchange)
                .with(ROUTING_KEY_APPROVED);
    }

    @Bean
    public Binding bindingReturned(Queue inventoryLoanEventsQueue, TopicExchange loanEventsExchange) {
        return BindingBuilder.bind(inventoryLoanEventsQueue)
                .to(loanEventsExchange)
                .with(ROUTING_KEY_RETURNED);
    }

    /**
     * No MessageConverter on the listener factory — LoanEventListener receives the raw
     * org.springframework.amqp.core.Message and deserialises with ObjectMapper itself.
     *
     * If a Jackson converter were set here, it would try to resolve the __TypeId__ header
     * (com.emergencylending.loan.event.*) which does not exist in this service's classpath.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        return factory;
    }
}
