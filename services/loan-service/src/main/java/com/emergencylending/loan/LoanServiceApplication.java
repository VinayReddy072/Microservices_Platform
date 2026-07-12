package com.emergencylending.loan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Loan Service — Service A (Primary Workflow)
 *
 * <p>Owns the loan-request lifecycle for the Emergency Equipment Lending Platform.
 * Before approving a loan, it synchronously calls inventory-service via OpenFeign
 * to confirm the requested equipment is available.
 *
 * <p>Resilience stack on the inventory-service call (see
 * {@link com.emergencylending.loan.client.InventoryAvailabilityAdapter}):
 * <ol>
 *   <li>Feign connection/read timeouts (fast-fail on slow downstream)</li>
 *   <li>@Retry — 3 attempts, restricted to IOException / RetryableException</li>
 *   <li>@CircuitBreaker — trips at configurable failure-rate threshold</li>
 *   <li>Fallback method — provisional approval + warning log when CB is open</li>
 * </ol>
 *
 * <p>TODO (Days 7-8): Publish LoanApprovedEvent and LoanReturnedEvent to RabbitMQ
 *   so inventory-service can update equipment status asynchronously.
 */
@SpringBootApplication
@EnableFeignClients
public class LoanServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoanServiceApplication.class, args);
    }
}
