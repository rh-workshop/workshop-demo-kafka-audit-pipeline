package com.produbanco.logs.crypto;

import java.nio.ByteBuffer;
import java.util.Arrays;

/// Formato binario del payload cifrado: `version(1) || keyId(1) || IV(12) || ciphertext || tag(16)`.
///
/// La cabecera identifica la llave: permite mantener la anterior en modo solo-descifrado mientras
/// expira la retención, y sin ella rotar haría indescifrable lo ya publicado. Va dentro del payload
/// y no en un header de Kafka para sobrevivir a cualquier copia entre tópicos o clústeres.
public record EncryptedPayload(byte version, byte keyId, byte[] iv, byte[] ciphertext) {

    public static final byte VERSION_1 = 1;
    public static final int IV_LENGTH = 12;
    private static final int HEADER_LENGTH = 2;

    /// El tag de GCM va al final del ciphertext, así que un payload válido tiene como mínimo la
    /// cabecera, el IV y el tag.
    private static final int TAG_LENGTH = 16;
    private static final int MIN_LENGTH = HEADER_LENGTH + IV_LENGTH + TAG_LENGTH;

    public byte[] toBytes() {
        return ByteBuffer.allocate(HEADER_LENGTH + iv.length + ciphertext.length)
                .put(version).put(keyId).put(iv).put(ciphertext)
                .array();
    }

    public static EncryptedPayload parse(byte[] raw) {
        if (raw.length < MIN_LENGTH) {
            throw new IllegalArgumentException(
                    "payload cifrado demasiado corto: %d B, mínimo %d".formatted(raw.length, MIN_LENGTH));
        }
        byte version = raw[0];
        if (version != VERSION_1) {
            throw new IllegalArgumentException("versión de payload no soportada: " + version);
        }
        return new EncryptedPayload(version, raw[1],
                Arrays.copyOfRange(raw, HEADER_LENGTH, HEADER_LENGTH + IV_LENGTH),
                Arrays.copyOfRange(raw, HEADER_LENGTH + IV_LENGTH, raw.length));
    }

    /// Datos autenticados pero no cifrados (AAD de GCM): atan el ciphertext a su contexto. Si
    /// alguien copia un mensaje válido a otro tópico, el tag deja de validar y el descifrado falla
    /// en vez de aceptarlo. No viaja en el mensaje: se reconstruye en ambos extremos.
    public static byte[] associatedData(String topic, byte version, byte keyId) {
        byte[] topicBytes = topic.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ByteBuffer.allocate(HEADER_LENGTH + topicBytes.length)
                .put(version).put(keyId).put(topicBytes)
                .array();
    }
}
