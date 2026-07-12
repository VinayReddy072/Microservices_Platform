package com.emergencylending.loan.entity;

/**
 * Lifecycle states of a loan request.
 *
 * <ul>
 *   <li>PENDING  — request created, awaiting approval decision.</li>
 *   <li>APPROVED — equipment availability confirmed; loan is active.</li>
 *   <li>REJECTED — request denied (equipment unavailable or policy violation).</li>
 *   <li>RETURNED — equipment has been returned; loan is closed.</li>
 * </ul>
 *
 * <p>State transitions:
 * <pre>
 *   PENDING → APPROVED  (via PUT /loans/{id}/approve)
 *   PENDING → REJECTED  (future: automated or manual rejection)
 *   APPROVED → RETURNED (via PUT /loans/{id}/return)
 * </pre>
 */
public enum LoanStatus {
    PENDING,
    APPROVED,
    REJECTED,
    RETURNED
}
