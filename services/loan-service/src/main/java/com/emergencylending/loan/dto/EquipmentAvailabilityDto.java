package com.emergencylending.loan.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO that mirrors the response from inventory-service's
 * {@code GET /equipment/{id}/availability} endpoint.
 *
 * <p>Must match the structure of
 * {@code com.emergencylending.inventory.dto.EquipmentAvailabilityDto}
 * in inventory-service. Kept as a separate class (not shared library)
 * to maintain the loose coupling principle — services should not share
 * domain types across module boundaries.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EquipmentAvailabilityDto {

    /** Equipment item ID as returned by inventory-service. */
    private Long equipmentItemId;

    /**
     * True if the item is available for loan, false if ON_LOAN or MAINTENANCE.
     */
    private boolean available;

    /**
     * Raw status string from inventory-service.
     * Using String rather than importing the inventory-service enum
     * keeps the services decoupled at the type level.
     */
    private String status;
}
