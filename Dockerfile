# Etapa build: Maven + JDK 25 (UBI) compila el fast-jar de Quarkus.
# Las UBI son redistribuibles sin suscripcion; el runtime es Quarkus community (Maven Central),
# no el BOM productizado, porque el cliente no tiene suscripcion de Application Foundations.
# Tag fijo (1.24) en vez de :latest para que el build sea reproducible.
FROM registry.access.redhat.com/ubi9/openjdk-25:1.24 AS build
USER root
WORKDIR /build
# El wrapper fija la version de Maven: el build da igual en CI que en la maquina de cualquiera.
COPY .mvn ./.mvn
COPY mvnw pom.xml ./
# El pom se copia primero para que la capa de dependencias se reaproveche mientras solo cambie src.
RUN ./mvnw -B -DskipTests dependency:go-offline
COPY src ./src
RUN ./mvnw -B -DskipTests clean package

# Etapa runtime: solo el JRE UBI + el fast-jar
FROM registry.access.redhat.com/ubi9/openjdk-25-runtime:1.24
COPY --from=build /build/target/quarkus-app/ /deployments/
# Puerto de los health checks (/q/health/live) que consultan las probes.
EXPOSE 8080
# El entrypoint de la UBI (run-java.sh) ajusta la JVM a los limites del contenedor y respeta
# JAVA_OPTS_APPEND; invocar 'java -jar' a pelo se saltaba ese ajuste.
CMD ["/opt/jboss/container/java/run/run-java.sh"]
