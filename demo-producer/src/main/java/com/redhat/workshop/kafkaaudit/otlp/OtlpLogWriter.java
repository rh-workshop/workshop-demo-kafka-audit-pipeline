package com.redhat.workshop.kafkaaudit.otlp;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

import com.google.protobuf.ByteString;
import com.redhat.workshop.kafkaaudit.domain.AuditEvent;

import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.LogsData;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.opentelemetry.proto.resource.v1.Resource;

/// Serializa un evento de auditoría a OTLP Protobuf con las clases oficiales de OpenTelemetry.
///
/// El esquema debe coincidir con el del productor .NET: ambos escriben en el mismo tópico y es el
/// processor Java quien los lee indistintamente. Los nombres de atributo salen de
/// [AuditAttributes], que es el contrato compartido con el enmascarado.
public final class OtlpLogWriter {

    private static final String SCHEMA_URL = "https://opentelemetry.io/schemas/1.36.0";
    private static final String SCOPE_NAME = "com.redhat.workshop.kafkaaudit";
    private static final String SERVICE_VERSION = "1.0.0";
    private static final String SERVICE_NAMESPACE = "kafka-audit";
    private static final String EVENT_NAME = "com.redhat.workshop.kafkaaudit.transfer";
    private static final String SEVERITY_INFO_TEXT = "INFO";
    private static final String BODY_TEXT = "Transferencia";

    /// Longitudes que fija el spec de OTLP / W3C Trace Context.
    private static final int TRACE_ID_LENGTH = 16;
    private static final int SPAN_ID_LENGTH = 8;

    /// Bit `sampled` del W3C Trace Context.
    private static final int TRACE_FLAG_SAMPLED = 0x01;

    private static final long NANOS_PER_MILLI = 1_000_000L;

    /// Una sola instancia: crear un `SecureRandom` por mensaje es coste puro en pruebas de carga.
    private static final SecureRandom RANDOM = new SecureRandom();

    private OtlpLogWriter() {
    }

    public static byte[] write(AuditEvent event) {
        long nowNanos = Instant.now().toEpochMilli() * NANOS_PER_MILLI;
        byte[] traceId = randomTraceId();

        var record = LogRecord.newBuilder()
                .setTimeUnixNano(nowNanos)
                .setObservedTimeUnixNano(nowNanos)
                .setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_INFO)
                .setSeverityText(SEVERITY_INFO_TEXT)
                .setBody(stringValue(BODY_TEXT))
                .setTraceId(ByteString.copyFrom(traceId))
                // El span id son los 8 primeros bytes del trace: basta para correlacionar en la demo.
                .setSpanId(ByteString.copyFrom(traceId, 0, SPAN_ID_LENGTH))
                .setFlags(TRACE_FLAG_SAMPLED)
                // La v1.3.2 del proto aún no tiene event_name como campo, así que va como atributo.
                .addAttributes(attribute(AuditAttributes.EVENT_NAME, EVENT_NAME))
                .addAttributes(attribute(AuditAttributes.CUSTOMER_EMAIL, event.email()))
                .addAttributes(attribute(AuditAttributes.CUSTOMER_DNI, event.dni()))
                .addAttributes(attribute(AuditAttributes.CARD_PAN, event.pan()))
                .addAttributes(KeyValue.newBuilder()
                        .setKey(AuditAttributes.TRANSACTION_AMOUNT)
                        .setValue(AnyValue.newBuilder().setDoubleValue(event.amount()))
                        .build())
                .addAttributes(attribute(AuditAttributes.TRANSACTION_CHANNEL, event.channel()));

        // Relleno para alcanzar el tamaño de mensaje del sizing; aleatorio para que no comprima.
        if (event.payloadBytes() > 0) {
            record.addAttributes(attribute(AuditAttributes.LOG_DETAIL, randomFiller(event.payloadBytes())));
        }

        var scopeLogs = ScopeLogs.newBuilder()
                .setScope(InstrumentationScope.newBuilder().setName(SCOPE_NAME).setVersion(SERVICE_VERSION))
                .setSchemaUrl(SCHEMA_URL)
                .addLogRecords(record.build())
                .build();

        var resource = Resource.newBuilder()
                .addAttributes(attribute(AuditAttributes.SERVICE_NAME, event.serviceName()))
                .addAttributes(attribute(AuditAttributes.SERVICE_NAMESPACE, SERVICE_NAMESPACE))
                .addAttributes(attribute(AuditAttributes.SERVICE_VERSION, SERVICE_VERSION))
                .addAttributes(attribute(AuditAttributes.SERVICE_INSTANCE_ID, event.serviceInstanceId()))
                .addAttributes(attribute(AuditAttributes.DEPLOYMENT_ENVIRONMENT, event.environment()))
                .build();

        return LogsData.newBuilder()
                .addResourceLogs(ResourceLogs.newBuilder()
                        .setResource(resource)
                        .setSchemaUrl(SCHEMA_URL)
                        .addScopeLogs(scopeLogs))
                .build()
                .toByteArray();
    }

    private static KeyValue attribute(String key, String value) {
        return KeyValue.newBuilder().setKey(key).setValue(stringValue(value)).build();
    }

    private static AnyValue stringValue(String value) {
        return AnyValue.newBuilder().setStringValue(value).build();
    }

    /// Trace id de 16 bytes aleatorios, como exige W3C Trace Context.
    private static byte[] randomTraceId() {
        byte[] bytes = new byte[TRACE_ID_LENGTH];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /// Relleno en base64 (6 bits por carácter) y no en hexadecimal (4): con hex, GZip lo reduce a la
    /// mitad y las pruebas de carga medirían mensajes más pequeños de lo indicado.
    private static String randomFiller(int length) {
        byte[] bytes = new byte[(length * 3 + 3) / 4];
        RANDOM.nextBytes(bytes);
        String encoded = Base64.getEncoder().withoutPadding().encodeToString(bytes);
        return encoded.substring(0, length);
    }
}
