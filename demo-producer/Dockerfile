# Imagen del generador de datos ficticios.
# Se construye desde la RAIZ del repo (necesita el módulo core): podman build -f demo-producer/Dockerfile .
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
COPY demo-producer/src demo-producer/src
RUN mvn -B -DskipTests -pl demo-producer -am clean package

# Etapa runtime: solo el JRE UBI y el fast-jar de este módulo.
FROM registry.access.redhat.com/ubi9/openjdk-25-runtime:1.24
COPY --from=build /build/demo-producer/target/quarkus-app/ /deployments/
# Puerto de los health checks (/q/health/live y /q/health/ready) que consultan las probes.
EXPOSE 8080
# El entrypoint de la UBI ajusta la JVM a los límites del contenedor y respeta JAVA_OPTS_APPEND.
CMD ["/opt/jboss/container/java/run/run-java.sh"]
