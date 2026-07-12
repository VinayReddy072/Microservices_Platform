package com.emergencylending.loan.repository;

import com.emergencylending.loan.entity.LoanRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link LoanRequest}.
 *
 * <p>Inherits full CRUD from {@code JpaRepository}:
 * {@code findAll}, {@code findById}, {@code save}, {@code deleteById}, etc.
 */
@Repository
public interface LoanRequestRepository extends JpaRepository<LoanRequest, Long> {
    // Custom queries can be added here as the feature set grows.
    // Example: List<LoanRequest> findByStatus(LoanStatus status);
    // Example: List<LoanRequest> findByEquipmentItemId(Long equipmentItemId);
}
