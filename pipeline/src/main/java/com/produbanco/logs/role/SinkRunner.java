package com.produbanco.logs.role;

import java.util.Base64;
import java.util.List;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.jboss.logging.Logger;

import com.produbanco.logs.config.PipelineConfig;
import com.produbanco.logs.kafka.ConsumerLoop;
import com.produbanco.logs.kafka.DeadLetterPublisher;
import com.produbanco.logs.kafka.KafkaClientFactory;
import com.produbanco.logs.kafka.Topics;
import com.produbanco.logs.otlp.OtlpSummary;

import io.opentelemetry.proto.logs.v1.LogsData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/// Persiste el log ya enmascarado; aquí se simula la escritura en Cosmos DB.
///
/// No monta la llave AES, por mínimo privilegio: solo lee del tópico que ya viaja enmascarado.
@ApplicationScoped
public class SinkRunner implements RoleRunner {

    private static final Logger LOG = Logger.getLogger(SinkRunner.class);
    private static final String ROLE = "sink";

    private final KafkaClientFactory clients;
    private final PipelineConfig config;

    private volatile ConsumerLoop loop;
    private KafkaProducer<String, String> deadLetterProducer;

    @Inject
    public SinkRunner(KafkaClientFactory clients, PipelineConfig config) {
        this.clients = clients;
        this.config = config;
    }

    @Override
    public void run() {
        // El tópico enmascarado viaja en claro, así que su DLQ usa el productor con compresión.
        deadLetterProducer = clients.maskedProducer();
        var deadLetters = new DeadLetterPublisher(deadLetterProducer, Topics.MASKED_DLQ, ROLE);
        var consumerLoop = new ConsumerLoop(
                clients.consumer(Topics.SINK_GROUP), ROLE, config.consumer());
        loop = consumerLoop;

        try (consumerLoop) {
            consumerLoop.run(List.of(Topics.MASKED), record -> persist(record, deadLetters));
        } finally {
            deadLetterProducer.close();
        }
    }

    @Override
    public void stop() {
        if (loop != null) {
            loop.stop();
        }
    }

    private void persist(ConsumerRecord<String, String> record, DeadLetterPublisher deadLetters) {
        LogsData data;
        try {
            data = LogsData.parseFrom(Base64.getDecoder().decode(record.value()));
        } catch (Exception e) {
            // A la cola de descarte, no a un warning: un mensaje ilegible no puede perderse en silencio.
            deadLetters.publish(record, "no es OTLP válido", e);
            return;
        }
        LOG.infof("[%s] persistido id=%s doc=%s", ROLE, record.key(), OtlpSummary.of(data));
    }
}
