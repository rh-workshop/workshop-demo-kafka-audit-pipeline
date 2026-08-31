package com.redhat.workshop.kafkaaudit.health;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/// Liveness del rol: responde caído en cuanto el bucle del rol deja de estar en marcha.
///
/// El rol corre en un hilo del `ManagedExecutor`: si termina por un fallo permanente el proceso
/// sigue vivo y el pod parecería sano sin procesar un solo mensaje.
@Liveness
@ApplicationScoped
public class RoleHealthCheck implements HealthCheck {

    private final RoleState state;

    @Inject
    public RoleHealthCheck(RoleState state) {
        this.state = state;
    }

    @Override
    public HealthCheckResponse call() {
        var builder = HealthCheckResponse.named("role-loop").withData("role", state.role());
        return state.isRunning() ? builder.up().build() : builder.down().build();
    }
}
