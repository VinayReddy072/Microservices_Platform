package com.emergencylending.inventory.service;

import com.emergencylending.inventory.dto.EquipmentAvailabilityDto;
import com.emergencylending.inventory.dto.EquipmentItemCreateDto;
import com.emergencylending.inventory.entity.EquipmentItem;
import com.emergencylending.inventory.entity.EquipmentStatus;
import com.emergencylending.inventory.repository.EquipmentItemRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class EquipmentItemService {

    private static final Logger log = LoggerFactory.getLogger(EquipmentItemService.class);

    private final EquipmentItemRepository repository;

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

    public EquipmentItem create(EquipmentItemCreateDto dto) {
        EquipmentItem item = EquipmentItem.builder()
                .name(dto.getName())
                .category(dto.getCategory())
                .location(dto.getLocation())
                .conditionNotes(dto.getConditionNotes())
                .build();
        return repository.save(item);
    }

    public EquipmentItem update(Long id, EquipmentItemCreateDto dto) {
        EquipmentItem existing = findById(id);
        existing.setName(dto.getName());
        existing.setCategory(dto.getCategory());
        existing.setLocation(dto.getLocation());
        existing.setConditionNotes(dto.getConditionNotes());
        return repository.save(existing);
    }

    /**
     * Transitions an equipment item to a new status. Called exclusively by
     * {@link com.emergencylending.inventory.messaging.LoanEventListener} — status
     * changes are driven by RabbitMQ events from loan-service, not by direct REST calls.
     *
     * @param id        equipment item ID
     * @param newStatus target status
     * @throws EntityNotFoundException if no item with the given ID exists
     */
    public void updateStatus(Long id, EquipmentStatus newStatus) {
        EquipmentItem item = findById(id);
        EquipmentStatus previous = item.getStatus();
        item.setStatus(newStatus);
        repository.save(item);
        log.info("Equipment id={} status {} → {}", id, previous, newStatus);
    }

    public void delete(Long id) {
        findById(id);
        repository.deleteById(id);
    }
}
