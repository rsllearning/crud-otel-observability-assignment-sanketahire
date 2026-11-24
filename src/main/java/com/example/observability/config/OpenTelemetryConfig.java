package com.example.observability.config;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for OpenTelemetry API components.
 *
 * Exposes Tracer and Meter beans bound to GlobalOpenTelemetry.
 * When executed with the OpenTelemetry Java Agent, GlobalOpenTelemetry
 * transparently delegates to the active agent SDK runtime.
 * In standalone or unit test environments, it defaults to the official No-Op implementation,
 * ensuring robust test execution without requiring an active telemetry collector.
 */
@Configuration
public class OpenTelemetryConfig {

    private static final String INSTRUMENTATION_SCOPE_NAME = "com.example.observability";
    private static final String INSTRUMENTATION_SCOPE_VERSION = "0.0.1";

    @Bean
    public OpenTelemetry openTelemetry() {
        return GlobalOpenTelemetry.get();
    }

    @Bean
    public Tracer tracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer(INSTRUMENTATION_SCOPE_NAME, INSTRUMENTATION_SCOPE_VERSION);
    }

    @Bean
    public Meter meter(OpenTelemetry openTelemetry) {
        return openTelemetry.getMeter(INSTRUMENTATION_SCOPE_NAME);
    }
}
