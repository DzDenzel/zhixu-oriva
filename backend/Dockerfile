# syntax=docker/dockerfile:1
FROM maven:3.9.10-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN mvn -B -ntp -DskipTests package && \
    cp /src/planlock-app/target/planlock-app-*.jar /src/app.jar && \
    javac -d /health /src/docker/Healthcheck.java

FROM eclipse-temurin:21.0.8_9-jre-jammy
ARG VERSION=development
ARG REVISION=unknown
LABEL org.opencontainers.image.title="知序 ORIVA backend" \
      org.opencontainers.image.source="https://github.com/DzDenzel/zhixu-oriva" \
      org.opencontainers.image.version="$VERSION" \
      org.opencontainers.image.revision="$REVISION" \
      org.opencontainers.image.licenses="MIT"
RUN groupadd --gid 10001 oriva && useradd --uid 10001 --gid oriva --no-create-home oriva
WORKDIR /opt/oriva
COPY --from=build --chown=10001:10001 /src/app.jar app.jar
COPY --from=build --chown=10001:10001 /health /opt/oriva/health
COPY --chown=10001:10001 plugins /opt/oriva/plugins
ENV PLANLOCK_PLUGIN_DIRECTORY=/opt/oriva/plugins \
    PLANLOCK_VERSION=$VERSION
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
    CMD ["java", "-cp", "/opt/oriva/health", "Healthcheck"]
ENTRYPOINT ["java", "-jar", "/opt/oriva/app.jar"]
