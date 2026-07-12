package com.emergencylending.inventory.entity;

/**
 * Lifecycle states of an equipment item.
 *
 * <ul>
 *   <li>AVAILABLE   — item is in the inventory and can be lent out.</li>
 *   <li>ON_LOAN     — item is currently lent to a borrower (set when loan approved).</li>
 *   <li>MAINTENANCE — item is undergoing inspection or repair and cannot be lent.</li>
 * </ul>
 *
 * TODO (Days 7-8): transitions AVAILABLE → ON_LOAN and ON_LOAN → AVAILABLE will
 * be driven by RabbitMQ events (LoanApprovedEvent / LoanReturnedEvent) rather than
 * direct REST calls, to decouple the two services.
 */
public enum EquipmentStatus {
    AVAILABLE,
    ON_LOAN,
    MAINTENANCE
}
