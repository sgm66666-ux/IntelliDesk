# IntelliDesk backend — Spring Boot (Java 21) multi-stage build
#
# Stage 1: build the runnable jar inside the image (reproducible `docker build`,
#          no host Maven/JDK required, no secrets baked in).
# Stage 2: minimal JRE runtime that executes the jar.
#
# Secrets (JWT secret, DB/Redis/RabbitMQ/MinIO/ES creds, AI provider keys) are
# injected ONLY at runtime via compose env_file / environment. They are never
# copied into the image or committed.

# ---- Stage 1: build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Cache dependency downloads across builds: copy pom first, resolve, then copy src.
COPY backend/pom.xml ./
RUN mvn -B dependency:go-offline -DskipTests -q || true

COPY backend/src ./src
RUN mvn -B -q clean package -DskipTests
RUN ls -la target/*.jar

# ---- Stage 2: runtime ----
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache wget
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --retries=5 --start-period=30s \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]