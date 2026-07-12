package com.emergencylending.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API Gateway
 *
 * <p>Reactive Spring Cloud Gateway — the single externally reachable entry point
 * for all client traffic to the Emergency Equipment Lending Platform.
 *
 * <p>Routes:
 * <ul>
 *   <li>{@code /api/loans/**}     → {@code lb://loan-service/loans/**}</li>
 *   <li>{@code /api/equipment/**} → {@code lb://inventory-service/equipment/**}</li>
 * </ul>
 *
 * <p>Routes are explicit (not auto-discovery-locator) so the gateway exposes only
 * the intended paths. Target services are resolved via Eureka load balancing (lb://).
 *
 * <p>NOTE: During development and demonstration, domain service ports 8081 and 8082
 * are intentionally left open locally to allow direct-vs-gateway testing and
 * mid-demo kill scenarios. In production these ports would be firewalled / restricted
 * to the VPC/pod-network — the gateway on port 8080 would be the SOLE external entry point.
 */
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
