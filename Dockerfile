# Multi-stage hardened build for HRMS Spring Boot application
# Stage 1: Build with Maven
FROM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /build

# Cache dependencies layer
COPY pom.xml .
RUN mvn dependency:go-offline -B || true

# Copy source and build production artifact
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Hardened Runtime
FROM eclipse-temurin:25-jre-slim
WORKDIR /app

# Install curl for health probes and security auditing
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Create non-root runtime group and user (GAP-10)
RUN groupadd -r appgroup -g 1001 \
    && useradd -r -u 1001 -g appgroup -s /sbin/nologin -d /app appuser \
    && chown -R appuser:appgroup /app

# JVM container memory ergonomics and crash behavior
ENV JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=40.0 -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# Copy JAR artifact with proper ownership
COPY --from=builder --chown=appuser:appgroup /build/target/*.jar app.jar

# Run container as non-root user
USER appuser

# Expose default HTTP port
EXPOSE 8080

# Hardened Actuator Health Check probe
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
