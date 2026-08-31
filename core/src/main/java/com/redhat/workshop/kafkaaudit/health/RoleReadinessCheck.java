package com.redhat.workshop.kafkaaudit.health;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/// Readiness del rol: indica si esta instancia ya está conectada y procesando.
///
/// Se apoya en el mismo estado que la liveness porque el bucle solo se marca en marcha después de
/// crear los clientes de Kafka: si el broker no responde, el arranque falla ahí.
@Readiness
@ApplicationScoped
public class RoleReadinessCheck implements HealthCheck {

    private final RoleState state;

    @Inject
    public RoleReadinessCheck(RoleState state) {
        this.state = state;
    }

    @Override
    public HealthCheckResponse call() {
        var builder = HealthCheckResponse.named("role-ready").withData("role", state.role());
        return state.isRunning() ? builder.up().build() : builder.down().build();
    }
}
