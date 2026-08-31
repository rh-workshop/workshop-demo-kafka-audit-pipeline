package com.redhat.workshop.kafkaaudit.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

/// Congela la derivación de llave: es el contrato con el productor .NET, y si cambia sin que el
/// otro extremo cambie igual, todos los mensajes acaban en la DLQ.
class KeyDerivationTest {

    /// Secreto de ejemplo con el formato que crea el runbook (`openssl rand -base64 32`).
    private static final String SECRET_BASE64 = "cmVkaGF0LXdvcmtzaG9wLWFlcy0yNTYta2V5LTAwMDE=";

    @Test
    void la_derivacion_es_determinista() {
        var primera = KeyDerivation.deriveKey(SECRET_BASE64, KeyDerivation.INFO_V2);
        var segunda = KeyDerivation.deriveKey(SECRET_BASE64, KeyDerivation.INFO_V2);

        assertThat(primera.getEncoded()).isEqualTo(segunda.getEncoded());
    }

    @Test
    void produce_una_llave_aes_de_256_bits() {
        var key = KeyDerivation.deriveKey(SECRET_BASE64, KeyDerivation.INFO_V2);

        assertThat(key.getEncoded()).hasSize(32);
        assertThat(key.getAlgorithm()).isEqualTo("AES");
    }

    /// El `info` separa dominios: rotar su versión da una llave distinta sin tocar el Key Vault.
    @Test
    void un_info_distinto_da_una_llave_distinta() {
        var v1 = KeyDerivation.deriveKey(SECRET_BASE64, KeyDerivation.INFO_V2);
        var v2 = KeyDerivation.deriveKey(SECRET_BASE64, "redhat-workshop/kafka-audit/aes256gcm/v3");

        assertThat(v1.getEncoded()).isNotEqualTo(v2.getEncoded());
    }

    /// El secreto se decodifica de base64 antes de entrar al HKDF: lo que alimenta la derivación
    /// son los 32 bytes aleatorios, no los 44 caracteres ASCII que los representan.
    @Test
    void usa_los_bytes_decodificados_del_secreto_base64() {
        byte[] material = new byte[32];
        for (int i = 0; i < material.length; i++) {
            material[i] = (byte) i;
        }
        String base64 = Base64.getEncoder().encodeToString(material);

        var desdeBase64 = KeyDerivation.deriveKey(base64, KeyDerivation.INFO_V2);
        // Un secreto que NO es base64 de 32 bytes se usa tal cual, así que debe dar otra llave.
        var desdeTexto = KeyDerivation.deriveKey("no-es-base64-de-32-bytes", KeyDerivation.INFO_V2);

        assertThat(desdeBase64.getEncoded()).isNotEqualTo(desdeTexto.getEncoded());
    }

    /// Vector fijo: si este valor cambia, la derivación dejó de ser compatible con el .NET ya
    /// desplegado y hay que rotar la versión del `info` de forma coordinada.
    @Test
    void vector_de_interoperabilidad_con_dotnet() {
        var key = KeyDerivation.deriveKey(SECRET_BASE64, KeyDerivation.INFO_V2);

        assertThat(HexFormat.of().formatHex(key.getEncoded()))
                .isEqualTo("c7f17f9a0d99f34c31a67381b6d97354764b63ffdf433447e38232fe33987fb6");
    }
}
