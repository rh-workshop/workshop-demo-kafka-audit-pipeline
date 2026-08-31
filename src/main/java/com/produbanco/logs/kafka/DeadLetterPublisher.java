package com.produbanco.logs.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.jboss.logging.Logger;

/// Aparta a la cola de descartes el mensaje que no se pudo procesar.
///
/// Se reenvía el valor original tal cual llegó, nunca el descifrado: la cola de descarte del tópico
/// cifrado no puede filtrar datos personales en claro.
public final class DeadLetterPublisher {

    private static final Logger LOG = Logger.getLogger(DeadLetterPublisher.class);

    private final KafkaProducer<String, String> producer;
    private final String topic;
    private final String role;

    public DeadLetterPublisher(KafkaProducer<String, String> producer, String topic, String role) {
        this.producer = producer;
        this.topic = topic;
        this.role = role;
    }

    /// Publica el registro en la DLQ. Si la propia DLQ falla se propaga, para no confirmar el
    /// offset y que el lote se reintente en vez de darse por procesado.
    public void publish(ConsumerRecord<String, String> record, String reason, Exception cause) {
        try {
            producer.send(new ProducerRecord<>(topic, record.key(), record.value())).get();
            // Se registra la causa: sin ella, revisar la DLQ es adivinar.
            LOG.warnf(cause, "[%s] %s %s -> %s", role, record.key(), reason, topic);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrumpido al escribir en la DLQ: " + record.key(), e);
        } catch (Exception e) {
            throw new IllegalStateException("no se pudo escribir en la DLQ: " + record.key(), e);
        }
    }
}
