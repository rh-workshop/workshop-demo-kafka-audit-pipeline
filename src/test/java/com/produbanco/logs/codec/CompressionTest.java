package com.produbanco.logs.codec;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class CompressionTest {

    @Test
    void comprimir_y_descomprimir_devuelve_el_original() throws Exception {
        byte[] original = "log de auditoría con acentos y ñ".getBytes(StandardCharsets.UTF_8);

        byte[] resultado = Compression.decompress(Compression.compress(original));

        assertThat(resultado).isEqualTo(original);
    }

    @Test
    void un_texto_repetitivo_ocupa_menos_comprimido() throws Exception {
        byte[] repetitivo = "produbanco".repeat(500).getBytes(StandardCharsets.UTF_8);

        assertThat(Compression.compress(repetitivo).length).isLessThan(repetitivo.length);
    }
}
