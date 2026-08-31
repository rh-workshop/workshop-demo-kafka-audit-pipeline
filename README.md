# workshop-demo-kafka-audit-pipeline

Servicios del pipeline de logs de auditoría sobre Kafka. Un solo artefacto Java
(Quarkus) que asume **tres roles** según la variable de entorno `ROLE`.

| Rol | Qué hace |
|---|---|
| `processor` | Descifra, valida el esquema OTLP y **enmascara los datos personales** antes de republicar |
| `sink` | Consume el tópico ya enmascarado y lo entrega al destino final |
| `producer` | Emisor de referencia para validar el flujo; en producción quien publica es el paquete .NET |

## Por qué un solo artefacto

Los tres roles comparten el 59% del código: cifrado, cliente de Kafka, contrato
OTLP y el bucle de consumo. Separarlos produciría servicios de ~100-300 líneas
colgando de una librería mayor que cualquiera de ellos, y crearía una tercera
copia del contrato criptográfico que ya se mantiene a dos bandas con el .NET.

El aislamiento que importa es de ejecución, no de artefacto: cada rol es un
Deployment con su propia identidad, sus permisos y su grupo de consumo, y el
`sink` **no monta la llave de cifrado**.

## Construir

```bash
./mvnw test          # 36 pruebas
./mvnw package
```

Requiere JDK 25: el proyecto aborta con un mensaje claro si se compila con una
versión anterior.

## Contrato con el productor .NET

El formato del payload cifrado, la derivación de llave (HKDF-SHA256) y los
nombres de los atributos OTLP deben coincidir **byte a byte** con
[`workshop-demo-kafka-audit-producer`](https://github.com/rh-workshop/workshop-demo-kafka-audit-producer).
Los tests congelan un vector de interoperabilidad compartido por ambos lenguajes.

## Despliegue

Los manifiestos viven en
[`workshop-demo-kafka-audit-pipeline-config`](https://github.com/rh-workshop/workshop-demo-kafka-audit-pipeline-config).
Este repositorio solo produce la imagen; Argo CD no lo mira.
