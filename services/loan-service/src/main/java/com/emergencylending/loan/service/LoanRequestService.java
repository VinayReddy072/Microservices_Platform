package com.emergencylending.loan.service;

import com.emergencylending.loan.client.InventoryAvailabilityAdapter;
import com.emergencylending.loan.dto.EquipmentAvailabilityDto;
import com.emergencylending.loan.dto.LoanRequestCreateDto;
import com.emergencylending.loan.entity.LoanRequest;
import com.emergencylending.loan.entity.LoanStatus;
import com.emergencylending.loan.repository.LoanRequestRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Business logic layer for loan request management.
 *
 * <p>The {@link #approve(Long)} method is the key integration point in this phase:
 * it calls inventory-service synchronously via {@link InventoryAvailabilityAdapter}
 * (which adds the four-layer resilience stack) before transitioning the loan to APPROVED.
 *
 * <p>TODO (Days 7-8): Publish a {@code LoanApprovedEvent} to RabbitMQ inside
 *   {@link #approve(Long)} after persisting the APPROVED status, so inventory-service
 *   can asynchronously transition the equipment to ON_LOAN status.
 *   Similarly publish a {@code LoanReturnedEvent} inside {@link #returnLoan(Long)}.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class LoanRequestService {

    private static final Logger log = LoggerFactory.getLogger(LoanRequestService.class);

    private final LoanRequestRepository repository;
    private final InventoryAvailabilityAdapter inventoryAdapter;

    // ── Read operations ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<LoanRequest> findAll() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public LoanRequest findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("LoanRequest not found: " + id));
    }

    // ── Write operations ─────────────────────────────────────────────────────

    public LoanRequest create(LoanRequestCreateDto dto) {
        LoanRequest request = LoanRequest.builder()
                .equipmentItemId(dto.getEquipmentItemId())
                .borrowerName(dto.getBorrowerName())
                .borrowerContact(dto.getBorrowerContact())
                // status defaults to PENDING via @Builder.Default in entity
                .build();
        LoanRequest saved = repository.save(request);
        log.info("Created LoanRequest id={} for equipmentItemId={} by borrower={}",
                saved.getId(), saved.getEquipmentItemId(), saved.getBorrowerName());
        return saved;
    }

    /**
     * Approve a pending loan request.
     *
     * <p>Before approving, calls inventory-service via the resilience adapter to
     * confirm the equipment is available. If the equipment is NOT available, the
     * loan is rejected. If the adapter's fallback fires (inventory-service unreachable),
     * the loan is provisionally approved and a warning is logged.
     *
     * @param id loan request ID
     * @return updated LoanRequest with APPROVED or REJECTED status
     * @throws EntityNotFoundException if no loan with the given ID exists
     * @throws IllegalStateException   if the loan is not in PENDING status
     */
    public LoanRequest approve(Long id) {
        LoanRequest loan = findById(id);

        if (loan.getStatus() != LoanStatus.PENDING) {
            throw new IllegalStateException(
                    "Cannot approve loan " + id + " — current status is " + loan.getStatus());
        }

        // Call inventory-service (with retry + circuit-breaker + fallback)
        EquipmentAvailabilityDto availability =
                inventoryAdapter.checkAvailability(loan.getEquipmentItemId());

        log.info("Availability check result for equipmentId={}: available={}, status={}",
                loan.getEquipmentItemId(), availability.isAvailable(), availability.getStatus());

        if (availability.isAvailable()) {
            loan.setStatus(LoanStatus.APPROVED);
            loan.setApprovedAt(Instant.now());
            log.info("LoanRequest id={} APPROVED", id);

            // TODO (Days 7-8): publish LoanApprovedEvent to RabbitMQ here
            // rabbitTemplate.convertAndSend("loan.exchange", "loan.approved",
            //     new LoanApprovedEvent(loan.getId(), loan.getEquipmentItemId()));
        } else {
            loan.setStatus(LoanStatus.REJECTED);
            log.info("LoanRequest id={} REJECTED — equipment {} is not available (status: {})",
                    id, loan.getEquipmentItemId(), availability.getStatus());
        }

        return repository.save(loan);
    }

    /**
     * Mark a loan as returned.
     *
     * @param id loan request ID
     * @return updated LoanRequest with RETURNED status
     * @throws EntityNotFoundException if no loan with the given ID exists
     * @throws IllegalStateException   if the loan is not in APPROVED status
     */
    public LoanRequest returnLoan(Long id) {
        LoanRequest loan = findById(id);

        if (loan.getStatus() != LoanStatus.APPROVED) {
            throw new IllegalStateException(
                    "Cannot return loan " + id + " — current status is " + loan.getStatus());
        }

        loan.setStatus(LoanStatus.RETURNED);
        loan.setReturnedAt(Instant.now());
        log.info("LoanRequest id={} RETURNED", id);

        // TODO (Days 7-8): publish LoanReturnedEvent to RabbitMQ here
        // rabbitTemplate.convertAndSend("loan.exchange", "loan.returned",
        //     new LoanReturnedEvent(loan.getId(), loan.getEquipmentItemId()));

        return repository.save(loan);
    }

    public void delete(Long id) {
        findById(id); // Verify existence before deleting
        repository.deleteById(id);
        log.info("LoanRequest id={} deleted", id);
    }
}
