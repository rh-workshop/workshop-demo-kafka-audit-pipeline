package com.produbanco.logs.role;

import java.util.Base64;
import java.util.List;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.jboss.logging.Logger;

import com.produbanco.logs.codec.Compression;
import com.produbanco.logs.config.PipelineConfig;
import com.produbanco.logs.crypto.Crypto;
import com.produbanco.logs.masking.Masker;
import com.produbanco.logs.kafka.ConsumerLoop;
import com.produbanco.logs.kafka.DeadLetterPublisher;
import com.produbanco.logs.kafka.KafkaClientFactory;
import com.produbanco.logs.kafka.Topics;

import io.opentelemetry.proto.logs.v1.LogsData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/// Descifra, valida el esquema OTLP y enmascara la PII antes de republicar en `masked`.
///
/// Es el único punto donde el dato existe en claro, así que nunca se registra su contenido.
///
/// Cada registro se publica confirmando el envío (`get()`), así que si falla el enésimo del lote
/// los anteriores ya están en `masked` y el lote entero se reprocesa: la entrega es *at-least-once*
/// y `masked` puede tener duplicados. El consumidor final debe deduplicar por la clave del
/// registro, que es el id del evento de auditoría.
@ApplicationScoped
public class ProcessorRunner implements RoleRunner {

    private static final Logger LOG = Logger.getLogger(ProcessorRunner.class);
    private static final String ROLE = "processor";

    private final KafkaClientFactory clients;
    private final Crypto crypto;
    private final PipelineConfig config;

    private volatile ConsumerLoop loop;
    private KafkaProducer<String, String> encryptedProducer;
    private KafkaProducer<String, String> maskedProducer;

    @Inject
    public ProcessorRunner(KafkaClientFactory clients, Crypto crypto, PipelineConfig config) {
        this.clients = clients;
        this.crypto = crypto;
        this.config = config;
    }

    @Override
    public void run() {
        encryptedProducer = clients.encryptedProducer();
        maskedProducer = clients.maskedProducer();
        var deadLetters = new DeadLetterPublisher(encryptedProducer, Topics.ENCRYPTED_DLQ, ROLE);
        var consumerLoop = new ConsumerLoop(
                clients.consumer(Topics.PROCESSOR_GROUP), ROLE, config.consumer());
        loop = consumerLoop;

        try (consumerLoop) {
            consumerLoop.run(List.of(Topics.ENCRYPTED), record -> process(record, deadLetters));
        } finally {
            encryptedProducer.close();
            maskedProducer.close();
        }
    }

    @Override
    public void stop() {
        if (loop != null) {
            loop.stop();
        }
    }

    private void process(ConsumerRecord<String, String> record, DeadLetterPublisher deadLetters)
            throws Exception {
        LogsData data;
        try {
            // Orden inverso al productor: descifrar, descomprimir y validar el esquema.
            byte[] compressed = crypto.decrypt(record.value(), record.topic());
            byte[] otlp = Compression.decompress(compressed);
            data = LogsData.parseFrom(otlp);
        } catch (Exception e) {
            // Un mensaje corrupto no debe bloquear la partición: se aparta y se sigue.
            deadLetters.publish(record, "no se pudo procesar", e);
            return;
        }

        LogsData masked = Masker.mask(data);
        String payload = Base64.getEncoder().encodeToString(masked.toByteArray());
        maskedProducer.send(new ProducerRecord<>(Topics.MASKED, record.key(), payload)).get();
        LOG.infof("[%s] %s OTLP válido con PII enmascarada -> masked (%d B)",
                ROLE, record.key(), masked.getSerializedSize());
    }
}
