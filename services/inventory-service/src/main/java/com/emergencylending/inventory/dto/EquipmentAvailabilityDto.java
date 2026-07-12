package com.emergencylending.inventory.dto;

import com.emergencylending.inventory.entity.EquipmentStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO returned by {@code GET /equipment/{id}/availability}.
 *
 * <p>Consumed by loan-service via Feign. The {@code available} boolean
 * is derived from the item's current status ({@code AVAILABLE} → true,
 * any other status → false).
 *
 * <p>This DTO is deliberately minimal — only the fields loan-service
 * needs to make an approval decision are included.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EquipmentAvailabilityDto {

    /** The equipment item's database ID. */
    private Long equipmentItemId;

    /**
     * True if and only if the item's status is AVAILABLE.
     * False if the item is ON_LOAN or under MAINTENANCE.
     */
    private boolean available;

    /** Raw status — returned so clients can distinguish ON_LOAN from MAINTENANCE. */
    private EquipmentStatus status;
}
