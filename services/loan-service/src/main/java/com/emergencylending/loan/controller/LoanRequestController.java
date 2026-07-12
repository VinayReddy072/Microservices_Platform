package com.emergencylending.loan.controller;

import com.emergencylending.loan.dto.LoanRequestCreateDto;
import com.emergencylending.loan.entity.LoanRequest;
import com.emergencylending.loan.service.LoanRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for loan request management.
 *
 * <p>Base path: {@code /loans}
 *
 * <p>Reachable:
 * <ul>
 *   <li>Direct:        {@code http://localhost:8081/loans}</li>
 *   <li>Via Gateway:   {@code http://localhost:8080/api/loans}</li>
 * </ul>
 */
@RestController
@RequestMapping("/loans")
@RequiredArgsConstructor
public class LoanRequestController {

    private final LoanRequestService service;

    /** GET /loans — list all loan requests */
    @GetMapping
    public ResponseEntity<List<LoanRequest>> getAll() {
        return ResponseEntity.ok(service.findAll());
    }

    /** GET /loans/{id} — get a single loan request */
    @GetMapping("/{id}")
    public ResponseEntity<LoanRequest> getById(@PathVariable Long id) {
        return ResponseEntity.ok(service.findById(id));
    }

    /**
     * POST /loans — create a new loan request (status starts as PENDING).
     * Returns HTTP 201 Created with the persisted entity (including generated id).
     */
    @PostMapping
    public ResponseEntity<LoanRequest> create(@Valid @RequestBody LoanRequestCreateDto dto) {
        LoanRequest created = service.create(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * PUT /loans/{id}/approve — approve a pending loan request.
     *
     * <p>Triggers a synchronous call to inventory-service via Feign (with
     * Resilience4J retry + circuit-breaker + fallback).
     *
     * @return updated loan request with APPROVED or REJECTED status
     */
    @PutMapping("/{id}/approve")
    public ResponseEntity<LoanRequest> approve(@PathVariable Long id) {
        return ResponseEntity.ok(service.approve(id));
    }

    /**
     * PUT /loans/{id}/return — mark an approved loan as returned.
     *
     * @return updated loan request with RETURNED status
     */
    @PutMapping("/{id}/return")
    public ResponseEntity<LoanRequest> returnLoan(@PathVariable Long id) {
        return ResponseEntity.ok(service.returnLoan(id));
    }

    /** DELETE /loans/{id} — remove a loan request record. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
