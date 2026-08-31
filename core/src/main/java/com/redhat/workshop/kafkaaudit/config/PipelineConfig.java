package com.redhat.workshop.kafkaaudit.config;

import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithName;

/// Configuración del pipeline, mapeada desde variables de entorno por MicroProfile Config.
///
/// Sin `@WithDefault`: los valores por defecto viven en `application.properties` (`${VAR:defecto}`),
/// única fuente. Declararlos también aquí sería letra muerta, porque el `:` siempre gana.
@ConfigMapping(prefix = "pipeline")
public interface PipelineConfig {

    /// Rol que asume esta instancia: producer, processor o sink.
    @WithName("ROLE")
    String role();

    @WithName("BOOTSTRAP")
    String bootstrap();

    /// Secreto del cifrado AES: el sink no lo monta, por mínimo privilegio.
    @WithName("KV_KEY_FILE")
    String kvKeyFile();

    /// Etiqueta de propósito del HKDF. Al rotar la llave se sube la versión, aquí y en el .NET.
    @WithName("KEY_INFO")
    String keyInfo();

    /// Identifica la llave dentro del payload cifrado, para poder rotarla sin perder lo publicado.
    @WithName("KEY_ID")
    int keyId();

    /// Llave ANTERIOR, en modo solo-descifrado, para la ventana de rotación.
    ///
    /// Al rotar, el tópico sigue conteniendo mensajes cifrados con la llave vieja hasta que expira su
    /// retención. Si el consumidor solo conociera la nueva, todo lo pendiente acabaría en la DLQ; con
    /// estas dos opciones puede descifrarlo mientras publica ya con la nueva. Se dejan de montar
    /// cuando la retención del tópico ha superado el momento de la rotación.
    @WithName("PREVIOUS_KEY_FILE")
    Optional<String> previousKeyFile();

    @WithName("PREVIOUS_KEY_ID")
    Optional<Integer> previousKeyId();

    /// Etiqueta HKDF de la llave anterior; si no se indica se reutiliza la vigente (rotación del
    /// material del Key Vault sin cambiar la versión del propósito).
    @WithName("PREVIOUS_KEY_INFO")
    Optional<String> previousKeyInfo();

    Tls tls();

    Producer producer();

    Consumer consumer();

    interface Tls {
        @WithName("KEYSTORE")
        String keystore();

        /// La inyecta el operador vía secretKeyRef (USER_PASSWORD), no es un fichero.
        ///
        /// Es `Optional` por una limitación de SmallRye (la cadena vacía se convierte a null y aborta
        /// el arranque), no porque falte pueda ser válido: `KafkaClientFactory` exige que venga.
        @WithName("USER_PASSWORD")
        Optional<String> keystorePassword();

        @WithName("TRUSTSTORE")
        String truststore();

        /// Idem, desde el Secret de la Cluster CA (CA_PASSWORD).
        @WithName("CA_PASSWORD")
        Optional<String> truststorePassword();
    }

    interface Producer {
        /// Milisegundos entre eventos; 0 los emite sin pausa para pruebas de carga.
        @WithName("RATE_MS")
        int rateMs();

        /// Relleno aleatorio para alcanzar el tamaño de mensaje del sizing (~50 KiB).
        @WithName("PAYLOAD_BYTES")
        int payloadBytes();

        /// Entorno que se publica como `deployment.environment.name`.
        @WithName("ENVIRONMENT")
        String environment();
    }

    /// Fiabilidad del bucle de consumo: son valores operativos (10 fallos x 3 s = 30 s hasta el
    /// CrashLoop) y deben ajustarse por entorno sin recompilar la imagen.
    interface Consumer {
        @WithName("POLL_TIMEOUT_MS")
        long pollTimeoutMs();

        @WithName("RETRY_BACKOFF_MS")
        long retryBackoffMs();

        /// Tras estos fallos seguidos el proceso termina: un error permanente (ACL denegada, TLS
        /// mal) no debe quedar reintentándose mientras el pod aparenta estar sano.
        @WithName("MAX_CONSECUTIVE_FAILURES")
        int maxConsecutiveFailures();
    }
}
