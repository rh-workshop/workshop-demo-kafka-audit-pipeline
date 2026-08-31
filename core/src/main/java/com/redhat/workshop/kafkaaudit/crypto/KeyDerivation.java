package com.redhat.workshop.kafkaaudit.crypto;

import java.security.GeneralSecurityException;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

import javax.crypto.KDF;
import javax.crypto.SecretKey;
import javax.crypto.spec.HKDFParameterSpec;

/// Deriva la llave AES-256 del secreto montado con HKDF-SHA256 (RFC 5869), construcción aprobada
/// por NIST SP 800-56C; el parámetro `info` ata la llave a su propósito. No se usa PBKDF2 ni Argon2
/// porque la entrada ya es aleatoria y no hay entropía que estirar (SP 800-132).
/// Debe ser byte a byte idéntica a `KeyDerivation.cs` o los mensajes dejan de ser interoperables.
public final class KeyDerivation {

    /// Contrato con `KeyDerivation.InfoV2` del .NET. El valor efectivo se lee de `KEY_INFO`; esta
    /// constante sirve de referencia y para congelar el vector de interoperabilidad en los tests.
    public static final String INFO_V2 = "redhat-workshop/kafka-audit/aes256gcm/v2";

    private static final String HKDF_ALGORITHM = "HKDF-SHA256";
    private static final String KEY_ALGORITHM = "AES";
    private static final int KEY_LENGTH_BYTES = 32;

    /// Sal fija y pública. HKDF no exige que la sal sea secreta; con un IKM ya aleatorio su aporte
    /// es la separación de dominios, que aquí ya da el `info`. Fijarla mantiene la interoperabilidad
    /// sin tener que transportarla en cada mensaje.
    private static final byte[] SALT = "redhat-workshop.kafka-audit.salt.v2".getBytes(StandardCharsets.UTF_8);

    private KeyDerivation() {
    }

    /// Deriva la llave a partir del contenido del fichero de secreto.
    public static SecretKey deriveKey(String secret, String info) {
        try {
            KDF hkdf = KDF.getInstance(HKDF_ALGORITHM);
            return hkdf.deriveKey(KEY_ALGORITHM, HKDFParameterSpec.ofExtract()
                    .addIKM(inputKeyMaterial(secret))
                    .addSalt(SALT)
                    .thenExpand(info.getBytes(StandardCharsets.UTF_8), KEY_LENGTH_BYTES));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("no se pudo derivar la llave AES con " + HKDF_ALGORITHM, e);
        }
    }

    /// El runbook crea el secreto con `openssl rand -base64 32`, así que el valor montado es la
    /// **representación** de 32 bytes aleatorios: se decodifica para alimentar HKDF con la entropía
    /// real y no con sus 44 caracteres ASCII. Si no es base64 válido se usan los bytes del texto,
    /// que es lo que hay que hacer cuando el vault entrega una passphrase en claro.
    private static byte[] inputKeyMaterial(String secret) {
        try {
            byte[] decoded = Base64.getDecoder().decode(secret);
            return decoded.length == KEY_LENGTH_BYTES ? decoded : secret.getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notBase64) {
            return secret.getBytes(StandardCharsets.UTF_8);
        }
    }
}
