package com.produbanco.logs.domain;

/// Evento de auditoría antes de serializarse a OTLP.
///
/// Es un record en vez de 9 parámetros posicionales: `email`, `dni` y `pan` son tres String
/// seguidos, y pasarlos en orden equivocado compilaría sin error y filtraría PII al campo errado.
public record AuditEvent(
        String id,
        String serviceName,
        String serviceInstanceId,
        String environment,
        String email,
        String dni,
        String pan,
        double amount,
        String channel,
        int payloadBytes) {
}
