package com.produbanco.logs.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;

/// Cubre el cifrado del payload: es la pieza más crítica del pipeline y hasta ahora no tenía
/// ninguna prueba, porque la llave se cargaba en un `@PostConstruct` y hacía falta CDI.
class AesGcmCipherTest {

    private static final String SECRET = "cHJvZHViYW5jby1sYWItYWVzLTI1Ni1rZXktMDAwMDE=";
    private static final String TOPIC = "tp.observability.logs.encrypted";
    private static final byte KEY_ID = 1;

    private final AesGcmCipher cipher = cipherWith(KEY_ID);

    @Test
    void cifrar_y_descifrar_devuelve_el_original() throws Exception {
        byte[] original = "log de auditoría con acentos y ñ".getBytes(StandardCharsets.UTF_8);

        byte[] resultado = cipher.decrypt(cipher.encrypt(original, TOPIC), TOPIC);

        assertThat(resultado).isEqualTo(original);
    }

    /// Nonce distinto por mensaje: dos cifrados del mismo texto no pueden coincidir, o GCM dejaría
    /// de proteger el contenido.
    @Test
    void el_mismo_texto_produce_ciphertexts_distintos() throws Exception {
        byte[] original = "mismo mensaje".getBytes(StandardCharsets.UTF_8);

        assertThat(cipher.encrypt(original, TOPIC)).isNotEqualTo(cipher.encrypt(original, TOPIC));
    }

    /// El tópico va como AAD: un ciphertext válido copiado a otro tópico no debe descifrar.
    @Test
    void un_mensaje_movido_a_otro_topico_no_descifra() throws Exception {
        String cifrado = cipher.encrypt("dato".getBytes(StandardCharsets.UTF_8), TOPIC);

        assertThatThrownBy(() -> cipher.decrypt(cifrado, "tp.observability.logs.masked"))
                .isInstanceOf(GeneralSecurityException.class);
    }

    /// GCM autentica: manipular un byte del ciphertext tiene que fallar, no devolver basura.
    @Test
    void un_ciphertext_manipulado_no_descifra() throws Exception {
        byte[] raw = Base64.getDecoder().decode(
                cipher.encrypt("dato".getBytes(StandardCharsets.UTF_8), TOPIC));
        raw[raw.length - 1] ^= 0x01;
        String manipulado = Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> cipher.decrypt(manipulado, TOPIC))
                .isInstanceOf(GeneralSecurityException.class);
    }

    /// Rotación: un mensaje de la llave anterior se detecta por su key id, con un error explícito
    /// en vez de un fallo de tag indistinguible de la corrupción.
    @Test
    void un_mensaje_de_otra_llave_se_rechaza_indicando_el_key_id() throws Exception {
        String cifradoConLlave1 = cipher.encrypt("dato".getBytes(StandardCharsets.UTF_8), TOPIC);
        AesGcmCipher otraLlave = cipherWith((byte) 2);

        assertThatThrownBy(() -> otraLlave.decrypt(cifradoConLlave1, TOPIC))
                .isInstanceOf(GeneralSecurityException.class)
                .hasMessageContaining("se cifró con la llave 1")
                .hasMessageContaining("acepta [2]");
    }

    /// La ventana de rotación: mientras el tópico retenga mensajes de la llave anterior, el
    /// consumidor tiene que poder descifrarlos aunque ya publique con la nueva. Sin esto, rotar
    /// mandaba a la DLQ todo lo pendiente.
    @Test
    void durante_la_rotacion_se_descifra_con_la_llave_anterior() throws Exception {
        var llaveAnterior = KeyDerivation.deriveKey("secreto-anterior", KeyDerivation.INFO_V1);
        var anterior = new AesGcmCipher(llaveAnterior, (byte) 1);
        String cifradoAntesDeRotar = anterior.encrypt("dato".getBytes(StandardCharsets.UTF_8), TOPIC);

        // Tras rotar: la activa es la 2 y la 1 se conserva en modo solo-descifrado.
        var trasRotar = new AesGcmCipher(KeyDerivation.deriveKey("secreto-nuevo", KeyDerivation.INFO_V1),
                (byte) 2, Map.of((byte) 1, llaveAnterior));

        assertThat(trasRotar.decrypt(cifradoAntesDeRotar, TOPIC))
                .asString(StandardCharsets.UTF_8).isEqualTo("dato");

        // Y lo que publica desde ahora va con la llave nueva, no con la vieja.
        byte[] nuevo = Base64.getDecoder().decode(
                trasRotar.encrypt("dato".getBytes(StandardCharsets.UTF_8), TOPIC));
        assertThat(EncryptedPayload.parse(nuevo).keyId()).isEqualTo((byte) 2);
    }

    /// El payload lleva cabecera de versión y key id; sin ella no se podría rotar la llave.
    @Test
    void el_payload_lleva_version_y_key_id() throws Exception {
        byte[] raw = Base64.getDecoder().decode(
                cipher.encrypt("dato".getBytes(StandardCharsets.UTF_8), TOPIC));

        var payload = EncryptedPayload.parse(raw);
        assertThat(payload.version()).isEqualTo(EncryptedPayload.VERSION_1);
        assertThat(payload.keyId()).isEqualTo(KEY_ID);
        assertThat(payload.iv()).hasSize(EncryptedPayload.IV_LENGTH);
    }

    @Test
    void un_payload_truncado_se_rechaza() {
        String truncado = Base64.getEncoder().encodeToString(new byte[] {1, 1, 0, 0});

        assertThatThrownBy(() -> cipher.decrypt(truncado, TOPIC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("demasiado corto");
    }

    @Test
    void una_version_desconocida_se_rechaza() {
        byte[] raw = new byte[64];
        raw[0] = 99;   // versión inexistente
        String desconocido = Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> cipher.decrypt(desconocido, TOPIC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("versión de payload no soportada");
    }

    private static AesGcmCipher cipherWith(byte keyId) {
        return new AesGcmCipher(KeyDerivation.deriveKey(SECRET, KeyDerivation.INFO_V1), keyId);
    }
}
