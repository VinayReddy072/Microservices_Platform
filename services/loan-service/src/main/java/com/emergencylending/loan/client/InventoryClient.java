package com.emergencylending.loan.client;

import com.emergencylending.loan.dto.EquipmentAvailabilityDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * OpenFeign declarative HTTP client for inventory-service.
 *
 * <p>The {@code name} attribute matches the {@code spring.application.name} of
 * inventory-service exactly so Eureka + Spring Cloud LoadBalancer can resolve the
 * logical name {@code lb://inventory-service} to a live instance IP:port.
 *
 * <p>Timeout configuration (connect/read) is in application.yml under:
 * {@code feign.client.config.inventory-service.*}
 *
 * <p>This interface is used directly by {@link InventoryAvailabilityAdapter},
 * which wraps it with the Resilience4J annotations. Do NOT add @Retry or
 * @CircuitBreaker directly to a Feign interface — annotations must be on a
 * Spring-managed bean method, not an interface default.
 */
@FeignClient(name = "inventory-service")
public interface InventoryClient {

    /**
     * Calls {@code GET /equipment/{id}/availability} on inventory-service.
     *
     * @param equipmentItemId the ID of the equipment item to check
     * @return availability DTO with {@code available} boolean and raw status string
     */
    @GetMapping("/equipment/{id}/availability")
    EquipmentAvailabilityDto checkAvailability(@PathVariable("id") Long equipmentItemId);
}
