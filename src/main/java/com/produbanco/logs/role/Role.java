package com.produbanco.logs.role;

import java.util.Arrays;
import java.util.stream.Collectors;

/// Rol que asume la instancia. Un mismo binario sirve para los tres despliegues.
public enum Role {

    PRODUCER,
    PROCESSOR,
    SINK;

    /// Un rol desconocido aborta el arranque: un pod vivo sin hacer nada es el fallo más difícil
    /// de detectar.
    public static Role from(String value) {
        return Arrays.stream(values())
                .filter(role -> role.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "ROLE '%s' no válido; se esperaba uno de %s".formatted(value, names())));
    }

    private static String names() {
        return Arrays.stream(values()).map(Enum::name).map(String::toLowerCase).collect(Collectors.joining(", "));
    }
}
