package com.redhat.workshop.kafkaaudit.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import com.redhat.workshop.kafkaaudit.config.PipelineConfig;

/// Cubre la fiabilidad del bucle de consumo: cuándo confirma, cuándo reintenta y cuándo se rinde.
/// Es la lógica que decide si un pod entra en CrashLoop o se queda "sano" sin procesar nada.
class ConsumerLoopTest {

    private static final String TOPIC = "tp.observability.logs.encrypted";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);

    @Test
    void confirma_el_offset_cuando_el_lote_se_procesa_sin_error() {
        var consumer = consumerWith(2);
        var loop = new ConsumerLoop(consumer, "test", settings(10));
        var procesados = new AtomicInteger();
        // Se pide la parada en el segundo poll: sin esto, MockConsumer devuelve lotes vacíos
        // indefinidamente y el bucle (que es infinito por diseño) nunca terminaría.
        consumer.schedulePollTask(loop::stop);

        loop.run(List.of(TOPIC), record -> procesados.incrementAndGet());

        assertThat(procesados).hasValue(2);
        assertThat(consumer.committed(Set.of(PARTITION)).get(PARTITION).offset()).isEqualTo(2);
    }

    /// Si el tratamiento falla, el lote NO se confirma: el mensaje debe volver a entregarse en
    /// lugar de darse por procesado.
    @Test
    void no_confirma_el_offset_si_el_tratamiento_falla() {
        var consumer = consumerWith(1);
        // Un solo fallo tolerado: así el primer error aborta y el test no depende de más polls.
        var loop = new ConsumerLoop(consumer, "test", settings(1));

        assertThatThrownBy(() -> loop.run(List.of(TOPIC), record -> {
            throw new IllegalStateException("fallo del sink");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(consumer.committed(Set.of(PARTITION))).doesNotContainKey(PARTITION);
    }

    /// Un error permanente (ACL denegada, TLS mal) no debe quedar reintentándose para siempre
    /// mientras el pod aparenta estar sano: tras N fallos seguidos se aborta.
    /// El fallo se inyecta en el propio `poll` (como haría una ACL denegada) para que se repita en
    /// cada vuelta y se pueda contar cuántas tolera antes de rendirse.
    @Test
    void aborta_tras_los_fallos_consecutivos_configurados() {
        var consumer = new MockConsumer<String, String>(OffsetResetStrategy.EARLIEST);
        var intentos = new AtomicInteger();
        for (int i = 0; i < 10; i++) {
            consumer.schedulePollTask(() -> {
                intentos.incrementAndGet();
                consumer.setPollException(new org.apache.kafka.common.errors.AuthorizationException(
                        "not authorized to access topics"));
            });
        }
        var loop = new ConsumerLoop(consumer, "processor", settings(3));

        assertThatThrownBy(() -> loop.run(List.of(TOPIC), record -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[processor]")
                .hasMessageContaining("fallos consecutivos");

        assertThat(intentos).hasValue(3);
    }

    private MockConsumer<String, String> consumerWith(int records) {
        var consumer = new MockConsumer<String, String>(OffsetResetStrategy.EARLIEST);
        consumer.schedulePollTask(() -> {
            consumer.rebalance(List.of(PARTITION));
            consumer.updateBeginningOffsets(Map.of(PARTITION, 0L));
            for (int i = 0; i < records; i++) {
                consumer.addRecord(new ConsumerRecord<>(TOPIC, 0, i, "clave-" + i, "valor-" + i));
            }
        });
        return consumer;
    }

    /// Backoff a 0 ms: el test comprueba el conteo de fallos, no la espera entre reintentos.
    private static PipelineConfig.Consumer settings(int maxFailures) {
        return new PipelineConfig.Consumer() {
            @Override
            public long pollTimeoutMs() {
                return 10;
            }

            @Override
            public long retryBackoffMs() {
                return 0;
            }

            @Override
            public int maxConsecutiveFailures() {
                return maxFailures;
            }
        };
    }
}
