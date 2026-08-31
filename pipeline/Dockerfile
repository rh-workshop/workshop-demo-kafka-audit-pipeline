# Imagen del pipeline de logs (processor y sink).
# Se construye desde la RAIZ del repo (necesita el módulo core): podman build -f pipeline/Dockerfile .
FROM registry.access.redhat.com/ubi9/openjdk-25:1.24 AS build
USER root
WORKDIR /build
# Se usa el Maven de la imagen y no ./mvnw: las UBI de OpenJDK traen tar pero NO gzip, así que el
# wrapper muere al desempaquetar su propia distribución. La versión de Maven queda fijada por la
# imagen base, que ya está anclada por tag.
COPY pom.xml ./
COPY core/pom.xml core/
COPY pipeline/pom.xml pipeline/
COPY demo-producer/pom.xml demo-producer/
# Los pom se copian primero para que la capa de dependencias se reaproveche mientras solo cambie src.
RUN mvn -B -DskipTests dependency:go-offline
COPY core/src core/src
COPY pipeline/src pipeline/src
RUN mvn -B -DskipTests -pl pipeline -am clean package
# Control de auditoría: la imagen de producción no puede contener el generador de datos ficticios.
# El aislamiento ya es estructural (arriba no se copia demo-producer/src y el módulo 'pipeline' no
# depende de él), pero eso se puede deshacer con un cambio de una línea en un pom. Verificarlo aquí
# convierte la garantía en algo comprobado en cada build, y falla el CI en vez de una revisión.
# Se lista con 'jar tf' y no con unzip: unzip no está garantizado en la UBI (esta misma imagen
# tampoco trae gzip), mientras que jar viene con el JDK que ya se está usando para compilar.
RUN if jar tf pipeline/target/quarkus-app/app/*.jar | grep -i dummy; then \
      echo "ERROR: el artefacto de producción contiene clases de datos ficticios" && exit 1; \
    fi && echo "OK: el artefacto de producción no contiene código de datos ficticios"

# Etapa runtime: solo el JRE UBI y el fast-jar de este módulo.
FROM registry.access.redhat.com/ubi9/openjdk-25-runtime:1.24
COPY --from=build /build/pipeline/target/quarkus-app/ /deployments/
# Puerto de los health checks (/q/health/live y /q/health/ready) que consultan las probes.
EXPOSE 8080
# El entrypoint de la UBI ajusta la JVM a los límites del contenedor y respeta JAVA_OPTS_APPEND.
CMD ["/opt/jboss/container/java/run/run-java.sh"]
