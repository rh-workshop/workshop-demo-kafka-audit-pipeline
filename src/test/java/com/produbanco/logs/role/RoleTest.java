package com.produbanco.logs.role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RoleTest {

    @Test
    void reconoce_el_rol_sin_distinguir_mayusculas() {
        assertThat(Role.from("dummy_data")).isEqualTo(Role.DUMMY_DATA);
        assertThat(Role.from("PROCESSOR")).isEqualTo(Role.PROCESSOR);
        assertThat(Role.from("Sink")).isEqualTo(Role.SINK);
    }

    /// Un rol desconocido aborta el arranque: un pod vivo sin procesar nada es el fallo más
    /// difícil de detectar.
    @Test
    void un_rol_desconocido_aborta_e_indica_los_validos() {
        assertThatThrownBy(() -> Role.from("consumidor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("consumidor")
                .hasMessageContaining("dummy_data, processor, sink");
    }
}
