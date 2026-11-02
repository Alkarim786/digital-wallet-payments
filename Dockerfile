# syntax=docker/dockerfile:1.7
# One Dockerfile for all five services. The build stage does not depend on SERVICE, so BuildKit builds the Maven
# reactor once and every service image reuses that cached layer.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY wallet-common wallet-common
COPY auth-service auth-service
COPY wallet-service wallet-service
COPY fraud-service fraud-service
COPY notification-service notification-service
COPY gateway gateway
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre-alpine
ARG SERVICE
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /src/${SERVICE}/target/${SERVICE}-1.0.0-exec.jar app.jar
USER app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
