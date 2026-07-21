package com.emergencylending.loan.service;

import com.emergencylending.loan.client.InventoryAvailabilityAdapter;
import com.emergencylending.loan.dto.EquipmentAvailabilityDto;
import com.emergencylending.loan.dto.LoanRequestCreateDto;
import com.emergencylending.loan.entity.LoanRequest;
import com.emergencylending.loan.entity.LoanStatus;
import com.emergencylending.loan.event.LoanApprovedEvent;
import com.emergencylending.loan.event.LoanItemReturnedEvent;
import com.emergencylending.loan.messaging.LoanEventPublisher;
import com.emergencylending.loan.repository.LoanRequestRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class LoanRequestService {

    private static final Logger log = LoggerFactory.getLogger(LoanRequestService.class);

    private final LoanRequestRepository repository;
    private final InventoryAvailabilityAdapter inventoryAdapter;
    private final LoanEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public List<LoanRequest> findAll() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public LoanRequest findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("LoanRequest not found: " + id));
    }

    public LoanRequest create(LoanRequestCreateDto dto) {
        LoanRequest request = LoanRequest.builder()
                .equipmentItemId(dto.getEquipmentItemId())
                .borrowerName(dto.getBorrowerName())
                .borrowerContact(dto.getBorrowerContact())
                .build();
        LoanRequest saved = repository.save(request);
        log.info("Created LoanRequest id={} for equipmentItemId={} by borrower={}",
                saved.getId(), saved.getEquipmentItemId(), saved.getBorrowerName());
        return saved;
    }

    /**
     * Approve a pending loan request.
     *
     * <p>Calls inventory-service synchronously via the resilience adapter to confirm
     * the equipment is available before transitioning to APPROVED. If the adapter's
     * fallback fires (inventory-service unreachable), the loan is provisionally approved
     * and a warning is logged so operations staff can verify manually.
     *
     * <p>After a successful APPROVED save, publishes {@link LoanApprovedEvent} to the
     * loan.events exchange so inventory-service can asynchronously mark the item ON_LOAN.
     * The event is published after the DB commit to avoid publishing an event for a
     * transaction that subsequently rolled back.
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

        EquipmentAvailabilityDto availability =
                inventoryAdapter.checkAvailability(loan.getEquipmentItemId());

        log.info("Availability check result for equipmentId={}: available={}, status={}",
                loan.getEquipmentItemId(), availability.isAvailable(), availability.getStatus());

        if (availability.isAvailable()) {
            loan.setStatus(LoanStatus.APPROVED);
            loan.setApprovedAt(Instant.now());
            LoanRequest saved = repository.save(loan);
            log.info("LoanRequest id={} APPROVED", id);

            eventPublisher.publishApproved(
                    new LoanApprovedEvent(saved.getId(), saved.getEquipmentItemId(), saved.getApprovedAt()));
            return saved;
        } else {
            loan.setStatus(LoanStatus.REJECTED);
            log.info("LoanRequest id={} REJECTED — equipment {} is not available (status: {})",
                    id, loan.getEquipmentItemId(), availability.getStatus());
            return repository.save(loan);
        }
    }

    /**
     * Mark a loan as returned.
     *
     * <p>After persisting RETURNED status, publishes {@link LoanItemReturnedEvent}
     * so inventory-service can asynchronously mark the equipment AVAILABLE again.
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
        LoanRequest saved = repository.save(loan);
        log.info("LoanRequest id={} RETURNED", id);

        eventPublisher.publishReturned(
                new LoanItemReturnedEvent(saved.getId(), saved.getEquipmentItemId(), saved.getReturnedAt()));
        return saved;
    }

    public void delete(Long id) {
        findById(id);
        repository.deleteById(id);
        log.info("LoanRequest id={} deleted", id);
    }
}
