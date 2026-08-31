package com.produbanco.logs.role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RoleTest {

    @Test
    void reconoce_el_rol_sin_distinguir_mayusculas() {
        assertThat(Role.from("producer")).isEqualTo(Role.PRODUCER);
        assertThat(Role.from("PROCESSOR")).isEqualTo(Role.PROCESSOR);
        assertThat(Role.from("Sink")).isEqualTo(Role.SINK);
    }

    /// Regresión: un rol desconocido debe abortar el arranque. Antes solo se registraba el error y
    /// el pod quedaba vivo sin procesar nada, que es el fallo más difícil de detectar.
    @Test
    void un_rol_desconocido_aborta_e_indica_los_validos() {
        assertThatThrownBy(() -> Role.from("consumidor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("consumidor")
                .hasMessageContaining("producer, processor, sink");
    }
}
