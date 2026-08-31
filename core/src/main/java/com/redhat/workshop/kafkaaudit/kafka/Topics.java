package com.redhat.workshop.kafkaaudit.kafka;

/// Nombres de tópicos, grupos y límites de tamaño, en un único sitio.
public final class Topics {

    public static final String ENCRYPTED = "tp.observability.logs.encrypted";
    public static final String ENCRYPTED_DLQ = "tp.observability.logs.encrypted.dlq";
    public static final String MASKED = "tp.observability.logs.masked";
    public static final String MASKED_DLQ = "tp.observability.logs.masked.dlq";

    public static final String PROCESSOR_GROUP = "log-processor-group";
    public static final String SINK_GROUP = "log-sink-group";

    /// 2,5 MB: el sizing estima ~50 KiB por mensaje, más margen para el relleno de las pruebas
    /// de carga. Debe coincidir con `max.message.bytes` del tópico o el broker rechaza el envío.
    public static final int MAX_MESSAGE_BYTES = 2_500_000;

    private Topics() {
    }
}
