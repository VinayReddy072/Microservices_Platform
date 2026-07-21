package com.emergencylending.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Inventory Service — Service B
 *
 * <p>Owns the equipment catalogue for the Emergency Equipment Lending Platform.
 * Provides CRUD management of {@link com.emergencylending.inventory.entity.EquipmentItem}
 * records and an availability check endpoint used by loan-service.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET    /equipment              — list all items</li>
 *   <li>GET    /equipment/{id}         — get single item</li>
 *   <li>POST   /equipment              — create item</li>
 *   <li>PUT    /equipment/{id}         — update item</li>
 *   <li>DELETE /equipment/{id}         — delete item</li>
 *   <li>GET    /equipment/{id}/availability — availability check (called by loan-service Feign)</li>
 * </ul>
 *
 * <p>Status transitions (AVAILABLE ↔ ON_LOAN) are driven by RabbitMQ events from
 * loan-service via {@link com.emergencylending.inventory.messaging.LoanEventListener}.
 */
@SpringBootApplication
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
