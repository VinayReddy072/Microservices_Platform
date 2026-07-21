package com.emergencylending.inventory.messaging;

import com.emergencylending.inventory.entity.EquipmentStatus;
import com.emergencylending.inventory.event.LoanApprovedEvent;
import com.emergencylending.inventory.event.LoanItemReturnedEvent;
import com.emergencylending.inventory.service.EquipmentItemService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Listens on the inventory.loan-events queue and updates equipment status
 * based on the routing key of each message.
 *
 * <p>The routing key (loan.approved / loan.returned) is used to determine
 * which event type to deserialise — this avoids any cross-service class
 * mapping dependency while keeping the deserialization explicit.
 */
@Component
@RequiredArgsConstructor
public class LoanEventListener {

    private static final Logger log = LoggerFactory.getLogger(LoanEventListener.class);

    private final ObjectMapper objectMapper;
    private final EquipmentItemService equipmentItemService;

    @RabbitListener(queues = "inventory.loan-events")
    public void handleLoanEvent(Message message) throws IOException {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();

        switch (routingKey) {
            case "loan.approved" -> {
                LoanApprovedEvent event = objectMapper.readValue(message.getBody(), LoanApprovedEvent.class);
                equipmentItemService.updateStatus(event.equipmentItemId(), EquipmentStatus.ON_LOAN);
                log.info("Equipment {} → ON_LOAN (loan request {})", event.equipmentItemId(), event.loanRequestId());
            }
            case "loan.returned" -> {
                LoanItemReturnedEvent event = objectMapper.readValue(message.getBody(), LoanItemReturnedEvent.class);
                equipmentItemService.updateStatus(event.equipmentItemId(), EquipmentStatus.AVAILABLE);
                log.info("Equipment {} → AVAILABLE (loan request {})", event.equipmentItemId(), event.loanRequestId());
            }
            default ->
                log.warn("Received unrecognised routing key '{}' on inventory.loan-events — ignored", routingKey);
        }
    }
}
