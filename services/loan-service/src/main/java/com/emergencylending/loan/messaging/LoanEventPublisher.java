package com.emergencylending.loan.messaging;

import com.emergencylending.loan.config.RabbitMqConfig;
import com.emergencylending.loan.event.LoanApprovedEvent;
import com.emergencylending.loan.event.LoanItemReturnedEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LoanEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoanEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public void publishApproved(LoanApprovedEvent event) {
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.LOAN_EVENTS_EXCHANGE,
                "loan.approved",
                event
        );
        log.info("Published LoanApprovedEvent for loanId={} equipmentId={}",
                event.loanRequestId(), event.equipmentItemId());
    }

    public void publishReturned(LoanItemReturnedEvent event) {
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.LOAN_EVENTS_EXCHANGE,
                "loan.returned",
                event
        );
        log.info("Published LoanItemReturnedEvent for loanId={} equipmentId={}",
                event.loanRequestId(), event.equipmentItemId());
    }
}
