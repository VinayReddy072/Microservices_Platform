package com.emergencylending.inventory.repository;

import com.emergencylending.inventory.entity.EquipmentItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link EquipmentItem}.
 *
 * <p>Inherits full CRUD from {@code JpaRepository}:
 * {@code findAll}, {@code findById}, {@code save}, {@code deleteById}, etc.
 */
@Repository
public interface EquipmentItemRepository extends JpaRepository<EquipmentItem, Long> {
    // Custom query methods can be added here as needed.
    // Example: List<EquipmentItem> findByStatus(EquipmentStatus status);
}
