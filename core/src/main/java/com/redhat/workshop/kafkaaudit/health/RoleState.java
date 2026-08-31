package com.redhat.workshop.kafkaaudit.health;

import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.enterprise.context.ApplicationScoped;

/// Estado del bucle del rol, compartido entre quien lo ejecuta ([com.redhat.workshop.kafkaaudit.PipelineApp]) y
/// quien lo publica a Kubernetes ([RoleHealthCheck]).
///
/// El hilo lo marca detenido al salir, termine bien o con error: la probe ve el estado del bucle,
/// no el del proceso.
@ApplicationScoped
public class RoleState {

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile String role = "desconocido";

    public void started(String role) {
        this.role = role;
        running.set(true);
    }

    public void stopped() {
        running.set(false);
    }

    public boolean isRunning() {
        return running.get();
    }

    public String role() {
        return role;
    }
}
