package com.emergencylending.loan.config;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.zipkin.ZipkinSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the OTel SDK and Micrometer Tracing bridge manually.
 *
 * Spring Boot 4.x extracted its tracing autoconfiguration into a module that is
 * not pulled in transitively by spring-boot-starter-actuator alone, so no
 * Tracer or SpanExporter beans appear in the context without this class.
 */
@Configuration
public class TracingConfig {

    @SuppressWarnings("deprecation") // ZipkinSpanExporter deprecated in OTel 1.x; still functional for Zipkin v2
    @Bean
    public OpenTelemetry openTelemetry(
            @Value("${spring.application.name}") String serviceName,
            @Value("${management.zipkin.tracing.endpoint:http://localhost:9411/api/v2/spans}") String zipkinEndpoint) {

        ZipkinSpanExporter exporter = ZipkinSpanExporter.builder()
                .setEndpoint(zipkinEndpoint)
                .build();

        Resource resource = Resource.create(
                Attributes.of(AttributeKey.stringKey("service.name"), serviceName));

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                .build();

        // Flush pending spans on graceful shutdown.
        Runtime.getRuntime().addShutdownHook(
                new Thread(tracerProvider::close, "otel-shutdown"));

        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .buildAndRegisterGlobal();
    }

    @Bean
    public Tracer micrometerTracer(OpenTelemetry openTelemetry,
                                   @Value("${spring.application.name}") String serviceName) {
        OtelCurrentTraceContext traceContext = new OtelCurrentTraceContext();
        io.opentelemetry.api.trace.Tracer otelTracer = openTelemetry.getTracer(serviceName);
        return new OtelTracer(otelTracer, traceContext, event -> {});
    }

    // Picked up automatically by ObservationAutoConfiguration and registered
    // with the ObservationRegistry — this is what places traceId/spanId into MDC.
    @Bean
    public DefaultTracingObservationHandler tracingObservationHandler(Tracer tracer) {
        return new DefaultTracingObservationHandler(tracer);
    }
}
