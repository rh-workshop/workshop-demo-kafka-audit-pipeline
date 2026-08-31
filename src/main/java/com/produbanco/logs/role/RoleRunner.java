package com.produbanco.logs.role;

/// Contrato común de los tres roles, para que el arranque y la parada sean iguales en todos.
public interface RoleRunner {

    /// Bloquea hasta que se pida parar o un fallo permanente lo aborte.
    void run();

    /// Se invoca al recibir SIGTERM: debe cerrar los clientes de Kafka.
    void stop();
}
