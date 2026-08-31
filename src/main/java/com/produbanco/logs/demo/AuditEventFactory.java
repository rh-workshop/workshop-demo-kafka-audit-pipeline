package com.produbanco.logs.demo;

import java.security.SecureRandom;
import java.util.UUID;

import com.produbanco.logs.domain.AuditEvent;

/// Genera eventos ficticios para la demo E2E. En producción los emiten los microservicios .NET.
///
/// Está aislado en su propio paquete para que quede claro qué código es de demostración: los datos
/// son inventados, nunca de cliente.
public final class AuditEventFactory {

    private static final String[] CUSTOMERS =
            {"juan.perez", "maria.gomez", "carlos.ruiz", "ana.torres", "luis.vaca"};
    private static final String[] SERVICES =
            {"bff-canal", "escenario-negocio", "servicio-dominio", "acceso-core"};
    private static final String MAIL_DOMAIN = "@produbanco.com";
    private static final String ID_FORMAT = "LOG-%05d";
    private static final String CHANNEL = "web";

    /// Importe máximo del movimiento ficticio, redondeado a dos decimales.
    private static final double MAX_AMOUNT = 5000;
    private static final double CENTS = 100;

    /// Cédula ecuatoriana ficticia: prefijo + 2 dígitos variables + sufijo = 10 dígitos.
    private static final String DNI_PREFIX = "17123";
    private static final String DNI_SUFFIX = "678";

    /// PAN ficticio de 16 dígitos: prefijo de prueba + 4 dígitos variables.
    private static final String PAN_PREFIX = "453912345678";

    private final SecureRandom random = new SecureRandom();
    private final String instanceId = UUID.randomUUID().toString();
    private final String environment;
    private final int payloadBytes;
    private long sequence;

    public AuditEventFactory(String environment, int payloadBytes) {
        this.environment = environment;
        this.payloadBytes = payloadBytes;
    }

    public AuditEvent next() {
        sequence++;
        // El módulo va contra la longitud del array, no contra un número fijo, para no desincronizarse.
        int index = (int) (sequence % CUSTOMERS.length);
        return new AuditEvent(
                ID_FORMAT.formatted(sequence),
                SERVICES[(int) (sequence % SERVICES.length)],
                instanceId,
                environment,
                CUSTOMERS[index] + MAIL_DOMAIN,
                fakeDni(),
                fakePan(),
                Math.round(random.nextDouble() * MAX_AMOUNT * CENTS) / CENTS,
                CHANNEL,
                payloadBytes);
    }

    /// Cédula ecuatoriana ficticia de 10 dígitos.
    private String fakeDni() {
        return DNI_PREFIX + (10 + random.nextInt(89)) + DNI_SUFFIX;
    }

    /// PAN ficticio de 16 dígitos con prefijo de prueba.
    private String fakePan() {
        return PAN_PREFIX + (1000 + random.nextInt(9000));
    }
}
