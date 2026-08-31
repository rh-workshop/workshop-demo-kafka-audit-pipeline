package com.produbanco.logs.role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RoleTest {

    @Test
    void reconoce_el_rol_sin_distinguir_mayusculas() {
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
                .hasMessageContaining("processor, sink");
    }

    /// Garantía estructural: el generador de datos ficticios vive en otro artefacto, así que aquí
    /// ni siquiera es un rol válido. Un pod productivo no puede convertirse en emisor de
    /// transacciones inventadas por mucho que se le cambie la variable de entorno.
    @Test
    void el_rol_de_datos_ficticios_no_existe_en_produccion() {
        assertThatThrownBy(() -> Role.from("dummy_data"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
