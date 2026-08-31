package com.produbanco.logs.codec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/// Compresión del OTLP ANTES de cifrar (§7 del CER).
///
/// Se comprime el payload serializado y luego se cifra: el dato cifrado (AES-256-GCM) es
/// incompresible, por eso el tópico `encrypted` va con `compression.type=none` en Kafka y el ahorro
/// se logra aquí.
///
/// Usa GZip del JDK (`java.util.zip`), sin dependencias de terceros; es el mismo formato que
/// produce y consume el productor .NET (`System.IO.Compression`).
public final class Compression {

    private Compression() {}

    public static byte[] compress(byte[] in) throws IOException {
        var bos = new ByteArrayOutputStream();
        try (var g = new GZIPOutputStream(bos)) {
            g.write(in);
        }
        return bos.toByteArray();
    }

    public static byte[] decompress(byte[] in) throws IOException {
        try (var g = new GZIPInputStream(new ByteArrayInputStream(in))) {
            return g.readAllBytes();
        }
    }
}
