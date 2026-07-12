package com.emergencylending.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

/**
 * Spring Cloud Config Server
 *
 * <p>Serves externalised configuration for all microservices in the Emergency
 * Equipment Lending Platform. Uses the native (filesystem) backend, reading
 * YAML files from the {@code config-repo/} directory at the project root.
 *
 * <p>Config files follow the naming convention:
 * <ul>
 *   <li>{@code application.yml}              — shared across all services</li>
 *   <li>{@code {service-name}-dev.yml}       — dev profile overrides</li>
 *   <li>{@code {service-name}-production.yml}— production profile overrides</li>
 * </ul>
 *
 * <p>Start AFTER Eureka, BEFORE domain services.
 * Health: http://localhost:8888/actuator/health
 */
@SpringBootApplication
@EnableConfigServer
public class ConfigServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfigServerApplication.class, args);
    }
}
