package com.redhat.workshop.kafkaaudit.role;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.jboss.logging.Logger;

import com.redhat.workshop.kafkaaudit.codec.Compression;
import com.redhat.workshop.kafkaaudit.config.PipelineConfig;
import com.redhat.workshop.kafkaaudit.crypto.Crypto;
import com.redhat.workshop.kafkaaudit.demo.DummyEventFactory;
import com.redhat.workshop.kafkaaudit.domain.AuditEvent;
import com.redhat.workshop.kafkaaudit.kafka.KafkaClientFactory;
import com.redhat.workshop.kafkaaudit.kafka.Topics;
import com.redhat.workshop.kafkaaudit.otlp.OtlpLogWriter;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/// Productor de demostración: serializa a OTLP, comprime, cifra y publica en `encrypted`.
///
/// En producción esto lo hace la librería .NET; aquí existe para poder validar el flujo completo
/// sin depender de los microservicios del cliente.
@ApplicationScoped
public class DummyDataRunner implements RoleRunner {

    private static final Logger LOG = Logger.getLogger(DummyDataRunner.class);

    private final KafkaClientFactory clients;
    private final PipelineConfig config;
    private final Crypto crypto;

    private final AtomicBoolean running = new AtomicBoolean(true);

    /// Libera la espera entre eventos al recibir SIGTERM: con `Thread.sleep` el cierre tardaba
    /// hasta un ciclo entero (10 s por defecto) en atender la parada.
    private final CountDownLatch stopped = new CountDownLatch(1);

    private volatile KafkaProducer<String, String> producer;

    @Inject
    public DummyDataRunner(KafkaClientFactory clients, PipelineConfig config, Crypto crypto) {
        this.clients = clients;
        this.config = config;
        this.crypto = crypto;
    }

    @Override
    public void run() {
        producer = clients.encryptedProducer();
        var events = new DummyEventFactory(
                config.producer().environment(),
                config.producer().payloadBytes());
        int rateMs = config.producer().rateMs();
        LOG.infof("[producer] emitiendo cada %d ms hacia %s", rateMs, Topics.ENCRYPTED);

        try {
            while (running.get()) {
                publish(events.next());
                if (rateMs > 0 && !awaitNextTick(rateMs)) {
                    break;
                }
            }
        } finally {
            // Sin este cierre, los mensajes en el búfer se pierden al terminar el pod.
            producer.close();
        }
    }

    @Override
    public void stop() {
        running.set(false);
        stopped.countDown();
    }

    private void publish(AuditEvent event) {
        try {
            byte[] otlp = OtlpLogWriter.write(event);
            byte[] compressed = Compression.compress(otlp);
            String encrypted = crypto.encrypt(compressed, Topics.ENCRYPTED);
            // Se espera la confirmación: en auditoría no se puede publicar y olvidarse.
            producer.send(new ProducerRecord<>(Topics.ENCRYPTED, event.id(), encrypted)).get();
            LOG.infof("[producer] %s OTLP %d B -> comprimido %d B -> cifrado %d chars",
                    event.id(), otlp.length, compressed.length, encrypted.length());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running.set(false);
        } catch (Crypto.CryptoException e) {
            // La llave no cambia sola: si no cifra una vez, no cifrará nunca. Reintentar cada 10 s
            // dejaría el pod aparentando salud sin publicar un solo evento -> mejor tumbarlo.
            throw new IllegalStateException("[producer] fallo permanente al cifrar, se aborta", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AuthorizationException || e.getCause() instanceof AuthenticationException) {
                // ACL denegada o mTLS mal: tampoco se arregla reintentando.
                throw new IllegalStateException("[producer] el broker rechaza las credenciales, se aborta", e);
            }
            // Los demás fallos del broker (líder en elección, timeout) sí son transitorios.
            LOG.errorf(e, "[producer] no se pudo publicar %s", event.id());
        } catch (Exception e) {
            LOG.errorf(e, "[producer] no se pudo publicar %s", event.id());
        }
    }

    /// Espera hasta el siguiente evento. Devuelve `false` si se pidió parar durante la espera.
    private boolean awaitNextTick(long millis) {
        try {
            return !stopped.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
