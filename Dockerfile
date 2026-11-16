# Multi-stage hardened build for HRMS Spring Boot application
# Stage 1: Build with Eclipse Temurin JDK 25 and project Maven Wrapper
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /build

# Cache Maven wrapper and dependencies layer
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B || true

# Copy source and build production artifact
COPY src ./src
RUN ./mvnw clean package -DskipTests

# Stage 2: Hardened Runtime with Eclipse Temurin JRE 25
FROM eclipse-temurin:25-jre
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
