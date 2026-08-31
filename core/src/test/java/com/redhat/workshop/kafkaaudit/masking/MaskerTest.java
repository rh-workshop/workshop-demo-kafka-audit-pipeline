package com.redhat.workshop.kafkaaudit.masking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.redhat.workshop.kafkaaudit.otlp.AuditAttributes;

import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.LogsData;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.resource.v1.Resource;

/// Verifica que la PII no salga nunca legible del processor.
class MaskerTest {

    @Test
    void enmascara_email_dni_y_pan() {
        LogsData masked = Masker.mask(logWith(
                attr(AuditAttributes.CUSTOMER_EMAIL, "juan.perez@ejemplo.com"),
                attr(AuditAttributes.CUSTOMER_DNI, "1712345678"),
                attr(AuditAttributes.CARD_PAN, "4539123456789010")));

        assertThat(recordValues(masked))
                .containsExactly("j***@ejemplo.com", "171****678", "****9010");
    }

    /// Regresión: con menos de 10 dígitos los substring se solapaban y el DNI quedaba completo.
    @Test
    void un_dni_corto_se_oculta_por_entero() {
        LogsData masked = Masker.mask(logWith(attr(AuditAttributes.CUSTOMER_DNI, "123456")));

        assertThat(recordValues(masked)).containsExactly("***");
    }

    /// Un correo sin arroba no se puede recortar sin dejarlo legible.
    @Test
    void un_email_sin_arroba_se_oculta_por_entero() {
        LogsData masked = Masker.mask(logWith(attr(AuditAttributes.CUSTOMER_EMAIL, "sinarroba")));

        assertThat(recordValues(masked)).containsExactly("***");
    }

    @Test
    void un_pan_mas_corto_que_la_cola_visible_se_oculta_por_entero() {
        LogsData masked = Masker.mask(logWith(attr(AuditAttributes.CARD_PAN, "123")));

        assertThat(recordValues(masked)).containsExactly("****");
    }

    /// La máscara del PAN no debe revelar la longitud original: un PAN de 19 dígitos y otro de 16
    /// producen el mismo prefijo.
    @Test
    void la_mascara_del_pan_no_revela_la_longitud() {
        LogsData corto = Masker.mask(logWith(attr(AuditAttributes.CARD_PAN, "4539123456789010")));
        LogsData largo = Masker.mask(logWith(attr(AuditAttributes.CARD_PAN, "4539123456789010123")));

        assertThat(recordValues(corto)).containsExactly("****9010");
        assertThat(recordValues(largo)).containsExactly("****0123");
    }

    @Test
    void deja_intactos_los_atributos_no_sensibles() {
        LogsData masked = Masker.mask(logWith(attr(AuditAttributes.TRANSACTION_CHANNEL, "web")));

        assertThat(recordValues(masked)).containsExactly("web");
    }

    /// Un atributo que no sea texto (aquí el importe) no se toca.
    @Test
    void deja_intactos_los_atributos_que_no_son_texto() {
        var amount = KeyValue.newBuilder().setKey(AuditAttributes.TRANSACTION_AMOUNT)
                .setValue(AnyValue.newBuilder().setDoubleValue(1234.56)).build();

        LogsData masked = Masker.mask(logWith(amount));

        assertThat(attributes(masked).getFirst().getValue().getDoubleValue()).isEqualTo(1234.56);
    }

    /// El productor .NET no está bajo el control de esta clase: si coloca PII entre los atributos
    /// del recurso, también tiene que salir enmascarada.
    @Test
    void enmascara_tambien_la_pii_de_los_atributos_del_recurso() {
        var data = LogsData.newBuilder()
                .addResourceLogs(ResourceLogs.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(attr(AuditAttributes.CUSTOMER_DNI, "1712345678")))
                        .addScopeLogs(ScopeLogs.newBuilder().addLogRecords(LogRecord.newBuilder())))
                .build();

        LogsData masked = Masker.mask(data);

        assertThat(masked.getResourceLogs(0).getResource().getAttributes(0).getValue().getStringValue())
                .isEqualTo("171****678");
    }

    /// El contrato que `AuditAttributes.PII` promete: ningún atributo declarado como PII puede salir
    /// con su valor original, tenga o no una regla propia en el `switch`. Si mañana se añade PII
    /// nueva al contrato y nadie escribe su regla, este test falla antes de que llegue a producción.
    @Test
    void ninguna_pii_declarada_sale_en_claro() {
        String original = "valor-sensible-en-claro";

        for (String key : AuditAttributes.PII) {
            LogsData masked = Masker.mask(logWith(attr(key, original)));

            assertThat(recordValues(masked))
                    .as("el atributo PII '%s' salio sin enmascarar", key)
                    .doesNotContain(original);
        }
    }

    private static KeyValue attr(String key, String value) {
        return KeyValue.newBuilder().setKey(key)
                .setValue(AnyValue.newBuilder().setStringValue(value)).build();
    }

    private static LogsData logWith(KeyValue... attributes) {
        var record = LogRecord.newBuilder();
        for (KeyValue attribute : attributes) {
            record.addAttributes(attribute);
        }
        return LogsData.newBuilder()
                .addResourceLogs(ResourceLogs.newBuilder()
                        .addScopeLogs(ScopeLogs.newBuilder().addLogRecords(record)))
                .build();
    }

    private static List<KeyValue> attributes(LogsData data) {
        return data.getResourceLogs(0).getScopeLogs(0).getLogRecords(0).getAttributesList();
    }

    private static List<String> recordValues(LogsData data) {
        return attributes(data).stream().map(a -> a.getValue().getStringValue()).toList();
    }
}
