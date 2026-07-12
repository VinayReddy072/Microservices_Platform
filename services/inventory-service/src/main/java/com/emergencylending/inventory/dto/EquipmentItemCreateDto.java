package com.emergencylending.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request DTO for creating or updating an EquipmentItem.
 *
 * <p>Status is intentionally excluded — the lifecycle state is managed
 * internally by the service (and later by RabbitMQ events).
 */
@Data
public class EquipmentItemCreateDto {

    @NotBlank(message = "Equipment name must not be blank")
    private String name;

    @NotBlank(message = "Category must not be blank")
    private String category;

    /** Optional — nullable in the database. */
    private String location;

    /** Optional — free-text condition description. */
    private String conditionNotes;
}
