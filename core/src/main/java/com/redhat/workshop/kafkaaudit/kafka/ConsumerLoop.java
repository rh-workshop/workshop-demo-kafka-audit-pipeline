package com.redhat.workshop.kafkaaudit.kafka;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.errors.WakeupException;
import org.jboss.logging.Logger;

import com.redhat.workshop.kafkaaudit.config.PipelineConfig;

/// Bucle de consumo compartido por el processor y el sink: hace el poll, confirma y reintenta;
/// cada rol solo aporta el tratamiento de un registro.
public final class ConsumerLoop implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ConsumerLoop.class);

    /// Se declara sobre la interfaz `Consumer` y no sobre `KafkaConsumer` para poder ejercitar el
    /// bucle en un test con `MockConsumer`, sin levantar un broker.
    private final Consumer<String, String> consumer;
    private final String name;
    private final Duration pollTimeout;
    private final long retryBackoffMs;
    private final int maxConsecutiveFailures;
    private final AtomicBoolean running = new AtomicBoolean(true);

    /// Los umbrales llegan de la configuración (`PipelineConfig.Consumer`): son operativos y deben
    /// poder ajustarse por entorno sin recompilar la imagen.
    public ConsumerLoop(Consumer<String, String> consumer, String name,
                        PipelineConfig.Consumer settings) {
        this.consumer = consumer;
        this.name = name;
        this.pollTimeout = Duration.ofMillis(settings.pollTimeoutMs());
        this.retryBackoffMs = settings.retryBackoffMs();
        this.maxConsecutiveFailures = settings.maxConsecutiveFailures();
    }

    /// Consume hasta que se pida parar. Lanza excepción si los fallos consecutivos se acumulan.
    public void run(List<String> topics, RecordHandler handler) {
        consumer.subscribe(topics);
        int failures = 0;

        while (running.get()) {
            try {
                var records = consumer.poll(pollTimeout);
                for (ConsumerRecord<String, String> record : records) {
                    handler.handle(record);
                }
                // Solo se confirma si todo el lote se procesó sin excepción.
                if (!records.isEmpty()) {
                    consumer.commitSync();
                }
                failures = 0;
            } catch (WakeupException e) {
                // La provoca stop(): es la salida ordenada, no un error.
                break;
            } catch (InterruptedException e) {
                // Se restaura la marca y se sale: tratarla como un fallo más gastaría el backoff
                // entero con la interrupción ya consumida, retrasando el cierre del pod.
                Thread.currentThread().interrupt();
                LOG.infof("[%s] interrumpido, se detiene el bucle", name);
                break;
            } catch (Exception e) {
                failures++;
                LOG.errorf(e, "[%s] fallo %d de %d, se reintenta", name, failures, maxConsecutiveFailures);
                if (failures >= maxConsecutiveFailures) {
                    throw new IllegalStateException("[" + name + "] fallos consecutivos, se aborta", e);
                }
                backoff();
            }
        }
        LOG.infof("[%s] bucle detenido", name);
    }

    /// Interrumpe el poll en curso para que el cierre no espere el timeout completo.
    public void stop() {
        running.set(false);
        consumer.wakeup();
    }

    @Override
    public void close() {
        // Cierra el grupo de forma limpia: sin esto el rebalanceo espera a que expire la sesión.
        consumer.close();
    }

    private void backoff() {
        try {
            Thread.sleep(retryBackoffMs);
        } catch (InterruptedException e) {
            // Se restaura la marca para que el hilo siga siendo interrumpible al cerrar.
            Thread.currentThread().interrupt();
            running.set(false);
        }
    }

    /// Tratamiento de un registro; puede lanzar, y entonces el lote no se confirma.
    @FunctionalInterface
    public interface RecordHandler {
        void handle(ConsumerRecord<String, String> record) throws Exception;
    }
}
