# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 chmod +x mvnw \
    && ./mvnw -B -q -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S recon \
    && adduser -S -G recon recon \
    && mkdir -p /app /data \
    && chown -R recon:recon /app /data

WORKDIR /app
COPY --from=build --chown=recon:recon /workspace/recon-batch/target/recon-batch-0.0.1-SNAPSHOT.jar app.jar

USER recon
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/urandom"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
