package com.produbanco.logs.crypto;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/// Cifrado AES-256-GCM del payload, sin dependencias de CDI ni de ficheros.
///
/// Separado del bean `Crypto` para poder instanciarlo en un test sin levantar CDI: la pieza más
/// crítica del pipeline debe ser la mejor cubierta.
///
/// Formato de salida y derivación de llave son el contrato con el productor .NET; ver
/// [EncryptedPayload] y [KeyDerivation].
public final class AesGcmCipher {

    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecureRandom random = new SecureRandom();
    private final SecretKey key;
    private final byte keyId;

    /// Llaves aceptadas al descifrar, por `keyId`. Siempre contiene la activa y, durante una
    /// rotación, también la anterior: el tópico retiene mensajes cifrados con ella hasta que expira
    /// su retención, y sin esto todos acabarían en la DLQ. Cifrar usa exclusivamente la activa.
    private final Map<Byte, SecretKey> decryptionKeys;

    public AesGcmCipher(SecretKey key, byte keyId) {
        this(key, keyId, Map.of());
    }

    /// `previousKeys` son llaves en modo solo-descifrado; la activa tiene precedencia sobre ellas.
    public AesGcmCipher(SecretKey key, byte keyId, Map<Byte, SecretKey> previousKeys) {
        this.key = key;
        this.keyId = keyId;
        var accepted = new HashMap<>(previousKeys);
        accepted.put(keyId, key);
        this.decryptionKeys = Map.copyOf(accepted);
    }

    /// Cifra y devuelve el payload en base64. El tópico se autentica como AAD: el mismo ciphertext
    /// copiado a otro tópico deja de descifrar.
    public String encrypt(byte[] plain, String topic) throws GeneralSecurityException {
        byte[] iv = new byte[EncryptedPayload.IV_LENGTH];
        // Nonce único por mensaje: repetirlo en GCM permite recuperar el texto en claro y falsificar.
        random.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(EncryptedPayload.associatedData(topic, EncryptedPayload.VERSION_1, keyId));

        byte[] ciphertext = cipher.doFinal(plain);
        return Base64.getEncoder().encodeToString(
                new EncryptedPayload(EncryptedPayload.VERSION_1, keyId, iv, ciphertext).toBytes());
    }

    public byte[] decrypt(String base64, String topic) throws GeneralSecurityException {
        var payload = EncryptedPayload.parse(Base64.getDecoder().decode(base64));
        SecretKey messageKey = decryptionKeys.get(payload.keyId());
        if (messageKey == null) {
            // Explícito: sin esto el fallo sería un tag inválido, indistinguible de corrupción.
            throw new GeneralSecurityException(
                    "el mensaje se cifró con la llave %d y esta instancia acepta %s"
                            .formatted(payload.keyId(), decryptionKeys.keySet()));
        }
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, messageKey, new GCMParameterSpec(TAG_BITS, payload.iv()));
        cipher.updateAAD(EncryptedPayload.associatedData(topic, payload.version(), payload.keyId()));
        return cipher.doFinal(payload.ciphertext());
    }
}
