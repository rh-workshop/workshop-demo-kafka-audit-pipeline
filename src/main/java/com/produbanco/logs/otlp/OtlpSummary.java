package com.produbanco.logs.otlp;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.opentelemetry.proto.logs.v1.LogsData;

/// Resumen legible de un OTLP, para evidenciar en el log que la PII llega ya enmascarada.
public final class OtlpSummary {

    /// Solo estos atributos: el resumen es una evidencia de auditoría, no un volcado del mensaje.
    /// Incluye la PII a propósito: es justo lo que se quiere ver ya enmascarado en el log.
    private static final Set<String> SUMMARY_KEYS = Stream.concat(
            Stream.of(AuditAttributes.SERVICE_NAME, AuditAttributes.TRANSACTION_CHANNEL),
            AuditAttributes.PII.stream()).collect(Collectors.toUnmodifiableSet());

    private OtlpSummary() {
    }

    public static String of(LogsData data) {
        return data.getResourceLogsList().stream()
                .flatMap(resourceLogs -> Stream.concat(
                        resourceLogs.getResource().getAttributesList().stream(),
                        resourceLogs.getScopeLogsList().stream()
                                .flatMap(scopeLogs -> scopeLogs.getLogRecordsList().stream())
                                .flatMap(logRecord -> logRecord.getAttributesList().stream())))
                .filter(attribute -> SUMMARY_KEYS.contains(attribute.getKey()))
                .map(attribute -> attribute.getKey() + "=" + attribute.getValue().getStringValue())
                .collect(Collectors.joining(" ", "{", "}"));
    }
}
