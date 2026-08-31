package com.redhat.workshop.kafkaaudit.otlp;

import java.util.Set;

/// Nombres de los atributos OTLP del evento de auditoría: el contrato con el productor .NET.
///
/// Centralizados aquí a propósito: escritos a mano en cada sitio, un renombrado compilaba sin error
/// y dejaba la PII sin enmascarar. Ahora el mismo cambio rompe la compilación de los tres usos.
///
/// El equivalente en .NET es `OtlpAttributes`; los valores deben coincidir literalmente.
public final class AuditAttributes {

    // -- Recurso: identifican al servicio que emite, no al cliente.
    public static final String SERVICE_NAME = "service.name";
    public static final String SERVICE_NAMESPACE = "service.namespace";
    public static final String SERVICE_VERSION = "service.version";
    public static final String SERVICE_INSTANCE_ID = "service.instance.id";
    public static final String DEPLOYMENT_ENVIRONMENT = "deployment.environment.name";

    // -- Registro.
    public static final String EVENT_NAME = "event.name";
    public static final String TRANSACTION_AMOUNT = "transaction.amount";
    public static final String TRANSACTION_CHANNEL = "transaction.channel";
    public static final String LOG_DETAIL = "log.detail";

    // -- PII: todo atributo listado aquí DEBE tener su regla en `Masker`.
    public static final String CUSTOMER_EMAIL = "customer.email";
    public static final String CUSTOMER_DNI = "customer.dni";
    public static final String CARD_PAN = "card.pan";

    /// Atributos que el enmascarado tiene que tratar. `Masker` consulta este conjunto en su rama por
    /// defecto: una clave listada aquí sin regla propia se enmascara entera en vez de pasar intacta.
    public static final Set<String> PII = Set.of(CUSTOMER_EMAIL, CUSTOMER_DNI, CARD_PAN);

    private AuditAttributes() {
    }
}
