# workshop-demo-kafka-audit-pipeline

Servicios del pipeline de logs de auditoría sobre Kafka (Java / Quarkus).

## Tres módulos, dos imágenes

```
kafka-audit/          (parent)
├── core/             cifrado, compresión, clientes de Kafka, configuración y salud
├── pipeline/         → imagen kafka-audit-pipeline        (producción)
└── demo-producer/    → imagen kafka-audit-demo-producer   (solo dev y test)
```

| Imagen | Roles | Qué hace |
|---|---|---|
| `kafka-audit-pipeline` | `processor`, `sink` | Descifra, enmascara los datos personales y entrega al destino |
| `kafka-audit-demo-producer` | — | Emite eventos con **datos ficticios** para validar el flujo |

## Por qué el generador va en su propio artefacto

El generador inventa nombres, cédulas y números de tarjeta. Si viajara en la misma
imagen que corre en producción, bastaría una variable de entorno para que un pod
productivo publicara **transacciones inventadas en el tópico de auditoría**, y eso
compromete la integridad del registro.

Separarlo convierte el control de *"confiamos en no desplegarlo"* a *"el código no
está en el binario"*. Es lo que pide OWASP ASVS V14.2.2 —retirar de producción las
funcionalidades de demostración— y lo que un auditor puede comprobar:

```bash
unzip -l pipeline/target/quarkus-app/app/*.jar | grep -ci dummy   # debe dar 0
```

Esa comprobación corre en cada build (`.github/workflows/build.yml`): si alguien
vuelve a mezclarlos, el build falla.

El módulo `core` se comparte, así que la infraestructura criptográfica no se
duplica: mantiene un contrato byte a byte con el productor .NET y una segunda copia
acabaría desincronizándose.

## Construir

```bash
./mvnw verify                                   # los tres módulos
podman build -f pipeline/Dockerfile -t kafka-audit-pipeline .
```

Requiere JDK 25: el build aborta con un mensaje claro si se usa una versión anterior.

## Contrato con el productor .NET

El formato del payload cifrado, la derivación de llave (HKDF-SHA256) y los nombres
de los atributos OTLP deben coincidir **byte a byte** con
[`workshop-demo-kafka-audit-producer`](https://github.com/rh-workshop/workshop-demo-kafka-audit-producer).
Los tests congelan un vector de interoperabilidad compartido por ambos lenguajes.

El contrato vigente es la **v2** (`redhat-workshop/kafka-audit/aes256gcm/v2`). Cambió
al genericizar los identificadores, y con él la llave derivada: **un mensaje cifrado
con la v1 no se puede descifrar con la v2**. La versión viaja en el `info` de HKDF y se
puede fijar por la variable `KEY_INFO`, así que la rotación se hace desplegando ambos
lenguajes de forma coordinada y, si hay mensajes pendientes en los tópicos, drenándolos
antes de cambiar.

## Despliegue

Los manifiestos viven en
[`workshop-demo-kafka-audit-pipeline-config`](https://github.com/rh-workshop/workshop-demo-kafka-audit-pipeline-config).
Este repositorio solo produce las imágenes; Argo CD no lo mira.
