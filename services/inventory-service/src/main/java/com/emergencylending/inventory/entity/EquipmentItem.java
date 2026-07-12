package com.emergencylending.inventory.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * JPA entity representing a single piece of emergency equipment.
 *
 * <p>Persisted to the {@code inventory_db} PostgreSQL database.
 * Owned entirely by inventory-service (database-per-service pattern).
 */
@Entity
@Table(name = "equipment_items")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EquipmentItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human-readable name, e.g. "Portable Defibrillator". Must not be blank. */
    @NotBlank(message = "Equipment name must not be blank")
    @Column(nullable = false)
    private String name;

    /** Equipment category, e.g. "Cardiac", "Respiratory". Must not be blank. */
    @NotBlank(message = "Category must not be blank")
    @Column(nullable = false)
    private String category;

    /**
     * Lifecycle status — persisted as a string for human-readable DB values.
     * Defaults to AVAILABLE on creation.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private EquipmentStatus status = EquipmentStatus.AVAILABLE;

    /** Physical location, e.g. "Station A", "Ambulance Bay 3". */
    private String location;

    /** Free-text notes on the item's physical condition. */
    @Column(name = "condition_notes")
    private String conditionNotes;
}
