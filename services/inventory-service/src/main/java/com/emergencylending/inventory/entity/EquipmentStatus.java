package com.emergencylending.inventory.entity;

/**
 * Lifecycle states of an equipment item.
 *
 * <ul>
 *   <li>AVAILABLE   — item is in the inventory and can be lent out.</li>
 *   <li>ON_LOAN     — item is currently lent to a borrower.</li>
 *   <li>MAINTENANCE — item is undergoing inspection or repair and cannot be lent.</li>
 * </ul>
 *
 * <p>Status transitions AVAILABLE → ON_LOAN and ON_LOAN → AVAILABLE are
 * driven by RabbitMQ events from loan-service via {@link com.emergencylending.inventory.messaging.LoanEventListener}.
 */
public enum EquipmentStatus {
    AVAILABLE,
    ON_LOAN,
    MAINTENANCE
}
