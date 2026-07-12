package com.emergencylending.inventory.controller;

import com.emergencylending.inventory.dto.EquipmentAvailabilityDto;
import com.emergencylending.inventory.dto.EquipmentItemCreateDto;
import com.emergencylending.inventory.entity.EquipmentItem;
import com.emergencylending.inventory.service.EquipmentItemService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for equipment item management.
 *
 * <p>Base path: {@code /equipment}
 *
 * <p>Reachable:
 * <ul>
 *   <li>Direct:        {@code http://localhost:8082/equipment}</li>
 *   <li>Via Gateway:   {@code http://localhost:8080/api/equipment}</li>
 * </ul>
 */
@RestController
@RequestMapping("/equipment")
@RequiredArgsConstructor
public class EquipmentItemController {

    private final EquipmentItemService service;

    /** GET /equipment — list all equipment items */
    @GetMapping
    public ResponseEntity<List<EquipmentItem>> getAll() {
        return ResponseEntity.ok(service.findAll());
    }

    /** GET /equipment/{id} — get a single equipment item */
    @GetMapping("/{id}")
    public ResponseEntity<EquipmentItem> getById(@PathVariable Long id) {
        return ResponseEntity.ok(service.findById(id));
    }

    /**
     * GET /equipment/{id}/availability
     *
     * <p>Key endpoint consumed by loan-service via Feign to determine whether
     * an item can be lent before approving a loan request.
     *
     * @return {@link EquipmentAvailabilityDto} with {@code available=true} iff status=AVAILABLE
     */
    @GetMapping("/{id}/availability")
    public ResponseEntity<EquipmentAvailabilityDto> checkAvailability(@PathVariable Long id) {
        return ResponseEntity.ok(service.checkAvailability(id));
    }

    /**
     * POST /equipment — create a new equipment item.
     * Returns HTTP 201 Created with the persisted entity (including generated id).
     */
    @PostMapping
    public ResponseEntity<EquipmentItem> create(@Valid @RequestBody EquipmentItemCreateDto dto) {
        EquipmentItem created = service.create(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * PUT /equipment/{id} — update an existing equipment item.
     * Status field is intentionally not updated via this endpoint.
     */
    @PutMapping("/{id}")
    public ResponseEntity<EquipmentItem> update(
            @PathVariable Long id,
            @Valid @RequestBody EquipmentItemCreateDto dto) {
        return ResponseEntity.ok(service.update(id, dto));
    }

    /** DELETE /equipment/{id} — remove an equipment item from the catalogue. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
