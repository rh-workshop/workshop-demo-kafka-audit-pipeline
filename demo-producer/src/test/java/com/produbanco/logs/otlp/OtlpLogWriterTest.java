package com.produbanco.logs.otlp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.produbanco.logs.domain.AuditEvent;

import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.LogsData;
import io.opentelemetry.proto.logs.v1.ResourceLogs;

/// Congela el esquema OTLP que produce este servicio.
///
/// Es el contrato con el productor .NET (`OtlpLogFormatter`), que ya tenía sus pruebas mientras este
/// lado no: un renombrado de atributo aquí compilaba sin error y solo se detectaba en ejecución.
class OtlpLogWriterTest {

    private static final AuditEvent EVENTO = new AuditEvent(
            "evt-1", "servicio-demo", "instancia-1", "dev",
            "juan.perez@produbanco.com", "1712345678", "4539123456789010",
            1234.56, "web", 0);

    @Test
    void publica_los_atributos_de_pii_con_los_nombres_del_contrato() {
        var atributos = atributosDelRegistro(OtlpLogWriter.write(EVENTO));

        assertThat(atributos)
                .containsEntry(AuditAttributes.CUSTOMER_EMAIL, "juan.perez@produbanco.com")
                .containsEntry(AuditAttributes.CUSTOMER_DNI, "1712345678")
                .containsEntry(AuditAttributes.CARD_PAN, "4539123456789010")
                .containsEntry(AuditAttributes.TRANSACTION_CHANNEL, "web");
    }

    @Test
    void publica_los_atributos_del_recurso_que_identifican_al_emisor() {
        var recurso = parse(OtlpLogWriter.write(EVENTO)).getResourceLogs(0).getResource();
        var atributos = recurso.getAttributesList().stream()
                .collect(Collectors.toMap(KeyValue::getKey, a -> a.getValue().getStringValue()));

        assertThat(atributos)
                .containsEntry(AuditAttributes.SERVICE_NAME, "servicio-demo")
                .containsEntry(AuditAttributes.SERVICE_INSTANCE_ID, "instancia-1")
                .containsEntry(AuditAttributes.DEPLOYMENT_ENVIRONMENT, "dev");
    }

    /// El importe viaja como número, no como texto: convertirlo rompería a quien lo agregue.
    @Test
    void el_importe_viaja_como_numero() {
        var importe = parse(OtlpLogWriter.write(EVENTO))
                .getResourceLogs(0).getScopeLogs(0).getLogRecords(0)
                .getAttributesList().stream()
                .filter(a -> a.getKey().equals(AuditAttributes.TRANSACTION_AMOUNT))
                .findFirst().orElseThrow();

        assertThat(importe.getValue().getDoubleValue()).isEqualTo(1234.56);
    }

    /// W3C Trace Context exige 16 bytes de trace id y 8 de span id.
    @Test
    void el_trace_id_y_el_span_id_tienen_la_longitud_que_exige_w3c() {
        LogRecord registro = parse(OtlpLogWriter.write(EVENTO))
                .getResourceLogs(0).getScopeLogs(0).getLogRecords(0);

        assertThat(registro.getTraceId().size()).isEqualTo(16);
        assertThat(registro.getSpanId().size()).isEqualTo(8);
    }

    /// Con relleno se alcanza el tamaño de mensaje del sizing; sin él, el atributo no aparece.
    @Test
    void el_relleno_solo_aparece_cuando_se_pide() {
        var sinRelleno = atributosDelRegistro(OtlpLogWriter.write(EVENTO));
        assertThat(sinRelleno).doesNotContainKey(AuditAttributes.LOG_DETAIL);

        var conRelleno = atributosDelRegistro(OtlpLogWriter.write(
                new AuditEvent("evt-2", "servicio-demo", "instancia-1", "dev",
                        "a@b.com", "1712345678", "4539123456789010", 1.0, "web", 512)));
        assertThat(conRelleno.get(AuditAttributes.LOG_DETAIL)).hasSize(512);
    }

    /// Dos eventos seguidos no pueden compartir trace id: se perdería la trazabilidad entre ellos.
    @Test
    void cada_evento_lleva_su_propio_trace_id() {
        var uno = parse(OtlpLogWriter.write(EVENTO)).getResourceLogs(0).getScopeLogs(0).getLogRecords(0);
        var otro = parse(OtlpLogWriter.write(EVENTO)).getResourceLogs(0).getScopeLogs(0).getLogRecords(0);

        assertThat(uno.getTraceId()).isNotEqualTo(otro.getTraceId());
    }

    private static LogsData parse(byte[] bytes) {
        try {
            return LogsData.parseFrom(bytes);
        } catch (Exception e) {
            throw new AssertionError("el OTLP publicado no es válido", e);
        }
    }

    private static Map<String, String> atributosDelRegistro(byte[] bytes) {
        ResourceLogs recurso = parse(bytes).getResourceLogs(0);
        return recurso.getScopeLogs(0).getLogRecords(0).getAttributesList().stream()
                .filter(a -> a.getValue().hasStringValue())
                .collect(Collectors.toMap(KeyValue::getKey, a -> a.getValue().getStringValue()));
    }
}
