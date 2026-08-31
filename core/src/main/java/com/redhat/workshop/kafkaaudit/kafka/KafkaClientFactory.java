package com.redhat.workshop.kafkaaudit.kafka;

import java.util.Optional;
import java.util.Properties;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.record.CompressionType;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import com.redhat.workshop.kafkaaudit.config.PipelineConfig;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/// Única fuente de la configuración de los clientes Kafka (mTLS y garantías de entrega).
///
/// Antes cada rol armaba su propio `Properties`, así que un cambio en el TLS había que replicarlo
/// en tres sitios. Las claves usan las constantes de kafka-clients y no strings escritos a mano:
/// un typo como `enable.idempotance` se acepta en silencio con un WARN de "unknown config" y el
/// productor pierde la garantía sin que nadie se entere; así es un error de compilación.
@ApplicationScoped
public class KafkaClientFactory {

    /// El cifrado deja el dato incompresible: comprimirlo otra vez gasta CPU sin ahorrar espacio.
    private static final CompressionType NO_COMPRESSION = CompressionType.NONE;

    /// El tópico enmascarado viaja en claro, así que aquí sí se recupera almacenamiento.
    private static final CompressionType MASKED_COMPRESSION = CompressionType.ZSTD;

    /// Formato de los almacenes que genera el operador para el `KafkaUser` y la Cluster CA.
    private static final String STORE_TYPE_PKCS12 = "PKCS12";

    private static final String OFFSET_RESET_EARLIEST = "earliest";
    private static final String ACKS_ALL = "all";

    private final PipelineConfig config;

    @Inject
    public KafkaClientFactory(PipelineConfig config) {
        this.config = config;
    }

    /// Productor para los tópicos cifrados (`encrypted` y su DLQ).
    public KafkaProducer<String, String> encryptedProducer() {
        return newProducer(NO_COMPRESSION);
    }

    /// Productor para el tópico enmascarado.
    public KafkaProducer<String, String> maskedProducer() {
        return newProducer(MASKED_COMPRESSION);
    }

    public KafkaConsumer<String, String> consumer(String groupId) {
        Properties p = mutualTls();
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, OFFSET_RESET_EARLIEST);
        // Se confirma el offset tras procesar, no al recibir: un fallo no debe dar el mensaje por leído.
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, String.valueOf(Topics.MAX_MESSAGE_BYTES));
        return new KafkaConsumer<>(p);
    }

    private KafkaProducer<String, String> newProducer(CompressionType compression) {
        Properties p = mutualTls();
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        // Auditoría bancaria: se confirma solo cuando las réplicas in-sync lo tienen a salvo.
        p.put(ProducerConfig.ACKS_CONFIG, ACKS_ALL);
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        p.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, compression.name);
        p.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, String.valueOf(Topics.MAX_MESSAGE_BYTES));
        return new KafkaProducer<>(p);
    }

    private Properties mutualTls() {
        Properties p = new Properties();
        p.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, config.bootstrap());
        p.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, SecurityProtocol.SSL.name);
        p.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, config.tls().truststore());
        p.put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG,
                required(config.tls().truststorePassword(), "CA_PASSWORD"));
        p.put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, STORE_TYPE_PKCS12);
        p.put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, config.tls().keystore());
        p.put(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG,
                required(config.tls().keystorePassword(), "USER_PASSWORD"));
        p.put(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG, STORE_TYPE_PKCS12);
        return p;
    }

    /// Una contraseña ausente degradaba a cadena vacía y Kafka fallaba con "keystore password was
    /// incorrect": un error de configuración disfrazado de problema de certificados. Es `Optional`
    /// porque SmallRye no admite `@WithDefault("")`, no porque falte pueda ser válido.
    private static String required(Optional<String> value, String variable) {
        return value.filter(v -> !v.isBlank()).orElseThrow(() -> new IllegalStateException(
                "falta la variable " + variable + ": la inyecta el operador vía secretKeyRef"));
    }
}
