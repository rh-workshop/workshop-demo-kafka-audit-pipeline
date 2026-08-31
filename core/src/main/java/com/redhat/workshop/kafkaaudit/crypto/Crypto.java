package com.redhat.workshop.kafkaaudit.crypto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Map;

import javax.crypto.SecretKey;

import org.jboss.logging.Logger;

import com.redhat.workshop.kafkaaudit.config.PipelineConfig;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/// Cifra el payload con AES-256-GCM. Es la fachada CDI de [AesGcmCipher]: carga el secreto montado,
/// deriva la llave y traduce las excepciones de criptografía a una no comprobada.
///
/// Recibe el OTLP ya serializado y comprimido con GZip: el dato cifrado tiene entropía alta y no
/// comprime, así que comprimir tiene que ir antes.
@ApplicationScoped
public class Crypto {

    private static final Logger LOG = Logger.getLogger(Crypto.class);

    private final AesGcmCipher cipher;

    /// Inyección por constructor: si la llave no está montada el arranque falla aquí, en vez de
    /// descubrirlo al procesar el primer mensaje.
    @Inject
    public Crypto(PipelineConfig config) {
        this.cipher = new AesGcmCipher(
                KeyDerivation.deriveKey(readSecret(config.kvKeyFile()), config.keyInfo()),
                (byte) config.keyId(),
                previousKeys(config));
    }

    /// Llave anterior en modo solo-descifrado durante la ventana de rotación. Exige fichero e
    /// identificador juntos: con uno solo la configuración está a medias y es mejor no arrancar que
    /// mandar en silencio a la DLQ todo lo cifrado con la llave vieja.
    private static Map<Byte, SecretKey> previousKeys(PipelineConfig config) {
        var file = config.previousKeyFile();
        var id = config.previousKeyId();
        if (file.isEmpty() && id.isEmpty()) {
            return Map.of();
        }
        if (file.isEmpty() || id.isEmpty()) {
            throw new IllegalStateException(
                    "la rotación exige PREVIOUS_KEY_FILE y PREVIOUS_KEY_ID a la vez");
        }
        if (id.get() == config.keyId()) {
            throw new IllegalStateException(
                    "PREVIOUS_KEY_ID no puede coincidir con KEY_ID (" + config.keyId() + ")");
        }
        String info = config.previousKeyInfo().orElse(config.keyInfo());
        LOG.infof("rotación activa: se acepta también la llave %d en modo solo-descifrado", id.get());
        return Map.of(id.get().byteValue(),
                KeyDerivation.deriveKey(readSecret(file.get()), info));
    }

    public String encrypt(byte[] plain, String topic) {
        try {
            return cipher.encrypt(plain, topic);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("no se pudo cifrar el payload", e);
        }
    }

    public byte[] decrypt(String base64, String topic) {
        try {
            return cipher.decrypt(base64, topic);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // El processor lo captura para apartar el mensaje a la DLQ, no para abortar.
            throw new CryptoException("no se pudo descifrar el payload", e);
        }
    }

    /// El trim es imprescindible: un salto de línea final daría una llave distinta a la del .NET.
    private static String readSecret(String file) {
        try {
            return Files.readString(Path.of(file)).trim();
        } catch (IOException e) {
            throw new IllegalStateException("no se pudo leer la llave AES de " + file, e);
        }
    }

    /// Unchecked para no obligar a cada llamador a declarar `throws Exception`.
    public static class CryptoException extends RuntimeException {
        public CryptoException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
