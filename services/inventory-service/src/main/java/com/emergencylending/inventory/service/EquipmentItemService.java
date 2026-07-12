package com.emergencylending.inventory.service;

import com.emergencylending.inventory.dto.EquipmentAvailabilityDto;
import com.emergencylending.inventory.dto.EquipmentItemCreateDto;
import com.emergencylending.inventory.entity.EquipmentItem;
import com.emergencylending.inventory.entity.EquipmentStatus;
import com.emergencylending.inventory.repository.EquipmentItemRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Business logic layer for {@link EquipmentItem} management.
 *
 * <p>Keeps all persistence logic here and out of the controller,
 * following the standard service/controller/repository layering.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class EquipmentItemService {

    private final EquipmentItemRepository repository;

    // ── Read operations ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<EquipmentItem> findAll() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public EquipmentItem findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("EquipmentItem not found: " + id));
    }

    /**
     * Returns availability information for a given equipment item.
     * This is the endpoint consumed by loan-service via Feign.
     *
     * @param id equipment item ID
     * @return DTO with {@code available=true} iff the item's status is AVAILABLE
     * @throws EntityNotFoundException if the item does not exist
     */
    @Transactional(readOnly = true)
    public EquipmentAvailabilityDto checkAvailability(Long id) {
        EquipmentItem item = findById(id);
        boolean available = item.getStatus() == EquipmentStatus.AVAILABLE;
        return new EquipmentAvailabilityDto(item.getId(), available, item.getStatus());
    }

    // ── Write operations ─────────────────────────────────────────────────────

    public EquipmentItem create(EquipmentItemCreateDto dto) {
        EquipmentItem item = EquipmentItem.builder()
                .name(dto.getName())
                .category(dto.getCategory())
                .location(dto.getLocation())
                .conditionNotes(dto.getConditionNotes())
                // status defaults to AVAILABLE via @Builder.Default in entity
                .build();
        return repository.save(item);
    }

    public EquipmentItem update(Long id, EquipmentItemCreateDto dto) {
        EquipmentItem existing = findById(id);
        existing.setName(dto.getName());
        existing.setCategory(dto.getCategory());
        existing.setLocation(dto.getLocation());
        existing.setConditionNotes(dto.getConditionNotes());
        // Note: status is NOT updated via this endpoint — use a dedicated
        // status-transition endpoint or (Days 7-8) a RabbitMQ event.
        return repository.save(existing);
    }

    public void delete(Long id) {
        // Verify existence before deleting so we return 404 for unknown IDs
        findById(id);
        repository.deleteById(id);
    }
}
