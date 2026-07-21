package com.emergencylending.loan.client;

import com.emergencylending.loan.dto.EquipmentAvailabilityDto;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adapter that wraps {@link InventoryClient} with a four-layer resilience stack.
 *
 * <p>Why an adapter, not annotations on the Feign interface directly?
 * Resilience4J annotations ({@code @Retry}, {@code @CircuitBreaker}) require a
 * Spring-managed concrete bean method — they do not work on interface default
 * methods or Feign proxy interfaces. This adapter is that concrete bean.
 *
 * <h2>Resilience Layers (applied inside-out — Retry wraps CircuitBreaker)</h2>
 * <ol>
 *   <li><strong>Feign timeouts</strong> — configured in application.yml under
 *       {@code feign.client.config.inventory-service}. Prevents the calling
 *       thread from blocking indefinitely on a slow downstream.</li>
 *
 *   <li><strong>@Retry</strong> — retries up to {@code max-attempts} times
 *       (default: 3). Restricted to {@code IOException} and
 *       {@code feign.RetryableException} so transient network blips are retried
 *       but "unavailable" business responses are NOT retried (avoids phantom
 *       double-approvals from a service that responded correctly but negatively).
 *       </li>
 *
 *   <li><strong>@CircuitBreaker</strong> — monitors failure rate across a sliding
 *       window. When the failure rate exceeds the configured threshold, the circuit
 *       opens and calls immediately invoke the fallback without reaching
 *       inventory-service at all — protecting it from repeated hammering during a
 *       genuine outage. The circuit half-opens after a configured wait to let a
 *       probe request through.</li>
 *
 *   <li><strong>Fallback ({@link #checkAvailabilityFallback})</strong> — when the
 *       circuit is open or all retries are exhausted, this method provisionally
 *       approves the loan and logs a warning so operations staff can verify manually.
 *       This is a deliberate business decision: in an emergency-lending context,
 *       blocking all loans during a transient inventory outage is worse than
 *       provisionally approving a few extra loans.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class InventoryAvailabilityAdapter {

    private static final Logger log = LoggerFactory.getLogger(InventoryAvailabilityAdapter.class);

    private final InventoryClient inventoryClient;

    /**
     * Check equipment availability with full resilience stack.
     *
     * <p>Annotation order matters: @Retry is the outer decorator, @CircuitBreaker
     * is the inner one. The retry will attempt the circuit-breaker-guarded call
     * multiple times. If the circuit is already open, the circuit breaker
     * immediately throws and the retry does NOT re-attempt (CB exception is not
     * in the retry-on list).
     *
     * @param equipmentItemId ID of the equipment to check
     * @return availability DTO, or a provisional "available=true" DTO if the
     *         downstream is unreachable
     */
    @Retry(name = "inventoryService")
    @CircuitBreaker(name = "inventoryService", fallbackMethod = "checkAvailabilityFallback")
    public EquipmentAvailabilityDto checkAvailability(Long equipmentItemId) {
        log.debug("Calling inventory-service to check availability for equipmentId={}", equipmentItemId);
        return inventoryClient.checkAvailability(equipmentItemId);
    }

    /**
     * Fallback invoked when the circuit is open or all retries are exhausted.
     *
     * <p>Provisionally returns {@code available=true} so the loan approval can
     * proceed rather than blocking all lending during a transient outage.
     * Operations staff must verify equipment status manually when this fires.
     *
     * @param equipmentItemId the equipment ID that was being checked
     * @param throwable       the exception that triggered the fallback
     * @return a synthetic "available=true" DTO marked as a fallback response
     */
    public EquipmentAvailabilityDto checkAvailabilityFallback(Long equipmentItemId, Throwable throwable) {
        log.warn(
            "inventory-service unreachable for equipmentId={}; provisionally approving — " +
            "verify equipment status manually. Cause: {} — {}",
            equipmentItemId,
            throwable.getClass().getSimpleName(),
            throwable.getMessage()
        );
        // Return a provisional "available" response so the loan is not blocked
        // during a transient inventory-service outage.
        return new EquipmentAvailabilityDto(equipmentItemId, true, "UNKNOWN_FALLBACK");
    }
}
