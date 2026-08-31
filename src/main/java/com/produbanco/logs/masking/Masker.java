package com.produbanco.logs.masking;

import java.util.Optional;

import com.produbanco.logs.otlp.AuditAttributes;

import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.LogsData;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;

/// Enmascaramiento determinista de PII sobre el OTLP Protobuf (auditable).
///
/// La PII viaja como `KeyValue` con `stringValue` en los atributos. El árbol se transforma de forma
/// inmutable, un método por nivel (LogsData -> ResourceLogs -> ScopeLogs -> LogRecord -> KeyValue),
/// devolviendo un `LogsData` nuevo con los atributos sensibles ya enmascarados.
///
/// Se enmascaran tanto los atributos del registro como los del recurso: el processor consume también
/// los mensajes del productor .NET, cuyo esquema no controla esta clase.
public final class Masker {

    /// Sustituye al valor entero cuando no tiene la forma esperada: recortarlo lo dejaría legible.
    private static final String FULL_MASK = "***";

    /// Cédula ecuatoriana: 10 dígitos. Con menos, los `substring` se solapan y el valor queda al
    /// aire (con 6 dígitos, "123456" devolvía "123****456": los 6 originales visibles).
    private static final int DNI_LENGTH = 10;
    private static final int DNI_VISIBLE_PREFIX = 3;
    private static final int DNI_VISIBLE_SUFFIX = 3;
    private static final String DNI_MASK = "****";

    /// Del PAN solo sobreviven los 4 últimos dígitos (regla PCI DSS). La máscara es de longitud
    /// fija: un número variable de asteriscos revelaría la longitud original del PAN.
    private static final int PAN_VISIBLE_SUFFIX = 4;
    private static final String PAN_MASK = "****";

    private Masker() {
    }

    public static LogsData mask(LogsData in) {
        return in.toBuilder().clearResourceLogs()
            .addAllResourceLogs(in.getResourceLogsList().stream().map(Masker::maskResourceLogs).toList())
            .build();
    }

    private static ResourceLogs maskResourceLogs(ResourceLogs rl) {
        var builder = rl.toBuilder().clearScopeLogs()
            .addAllScopeLogs(rl.getScopeLogsList().stream().map(Masker::maskScope).toList());
        if (rl.hasResource()) {
            builder.setResource(rl.getResource().toBuilder().clearAttributes()
                .addAllAttributes(rl.getResource().getAttributesList().stream()
                    .map(Masker::maskAttribute).toList()));
        }
        return builder.build();
    }

    private static ScopeLogs maskScope(ScopeLogs sl) {
        return sl.toBuilder().clearLogRecords()
            .addAllLogRecords(sl.getLogRecordsList().stream().map(Masker::maskRecord).toList())
            .build();
    }

    private static LogRecord maskRecord(LogRecord lr) {
        return lr.toBuilder().clearAttributes()
            .addAllAttributes(lr.getAttributesList().stream().map(Masker::maskAttribute).toList())
            .build();
    }

    private static KeyValue maskAttribute(KeyValue attr) {
        if (!attr.getValue().hasStringValue()) {
            return attr;
        }
        return maskValue(attr.getKey(), attr.getValue().getStringValue())
            .map(masked -> attr.toBuilder()
                .setValue(attr.getValue().toBuilder().setStringValue(masked)).build())
            .orElse(attr);
    }

    /// Valor enmascarado, o vacío si el atributo no es sensible y debe pasar intacto.
    ///
    /// Cada regla comprueba primero la forma del valor: si no la cumple se enmascara entero, nunca
    /// se recorta a medias (un recorte sobre un valor más corto de lo esperado lo deja legible).
    ///
    /// El `default` NO deja pasar cualquier clave desconocida: si está declarada como PII en
    /// `AuditAttributes` pero nadie le escribió una regla aquí, se enmascara entera. Así, añadir PII
    /// nueva al contrato nunca la publica en claro por olvido; en el peor caso se sobre-enmascara.
    static Optional<String> maskValue(String key, String value) {
        return Optional.ofNullable(switch (key) {
            case AuditAttributes.CUSTOMER_EMAIL -> maskEmail(value);
            case AuditAttributes.CUSTOMER_DNI -> maskDni(value);
            case AuditAttributes.CARD_PAN -> maskPan(value);
            default -> AuditAttributes.PII.contains(key) ? FULL_MASK : null;
        });
    }

    /// juan.perez@banco.com -> j***@banco.com
    private static String maskEmail(String value) {
        int at = value.indexOf('@');
        return at > 0 ? value.charAt(0) + FULL_MASK + value.substring(at) : FULL_MASK;
    }

    /// 1712345678 -> 171****678
    private static String maskDni(String value) {
        if (value.length() != DNI_LENGTH) {
            return FULL_MASK;
        }
        return value.substring(0, DNI_VISIBLE_PREFIX) + DNI_MASK
                + value.substring(value.length() - DNI_VISIBLE_SUFFIX);
    }

    /// 4539123456789010 -> ****9010
    private static String maskPan(String value) {
        if (value.length() < PAN_VISIBLE_SUFFIX) {
            return PAN_MASK;
        }
        return PAN_MASK + value.substring(value.length() - PAN_VISIBLE_SUFFIX);
    }
}
