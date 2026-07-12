package com.emergencylending.eureka;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * Eureka Service Registry
 *
 * <p>Standalone Netflix Eureka server — the central service registry for the
 * Emergency Equipment Lending Platform. All microservices (Config Server,
 * loan-service, inventory-service, api-gateway) register here on startup so
 * they can discover each other by logical name rather than hardcoded IP/port.
 *
 * <p>Start this service FIRST before any other module.
 * Dashboard: http://localhost:8761
 */
@SpringBootApplication
@EnableEurekaServer
public class EurekaServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(EurekaServerApplication.class, args);
    }
}
