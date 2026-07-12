package com.emergencylending.loan.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * JPA entity representing a loan request for a piece of emergency equipment.
 *
 * <p>Persisted to the {@code loan_db} PostgreSQL database.
 * Owned entirely by loan-service (database-per-service pattern).
 */
@Entity
@Table(name = "loan_requests")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Foreign key (by ID only — no JPA join) to the equipment item in inventory-service.
     * Not null: every loan request must reference a specific piece of equipment.
     */
    @NotNull(message = "Equipment item ID must not be null")
    @Column(name = "equipment_item_id", nullable = false)
    private Long equipmentItemId;

    /** Full name of the borrower. Must not be blank. */
    @NotBlank(message = "Borrower name must not be blank")
    @Column(nullable = false)
    private String borrowerName;

    /** Contact detail (email or phone) for the borrower. Must not be blank. */
    @NotBlank(message = "Borrower contact must not be blank")
    @Column(nullable = false)
    private String borrowerContact;

    /**
     * Current lifecycle status. Defaults to PENDING on creation.
     * Persisted as a string for human-readable DB values.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private LoanStatus status = LoanStatus.PENDING;

    /** Timestamp of when this loan request was submitted. */
    @Column(name = "requested_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant requestedAt = Instant.now();

    /** Timestamp of when the loan was approved. Null until approval. */
    @Column(name = "approved_at")
    private Instant approvedAt;

    /** Timestamp of when the equipment was returned. Null until return. */
    @Column(name = "returned_at")
    private Instant returnedAt;
}
