# ==============================================================================
# Gatekeeper Backend - Production Multi-Stage Dockerfile
# ==============================================================================
# Stage 1: Build Application Artifact
# ==============================================================================
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Cache Maven dependencies by copying pom.xml first
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy application source code and compile production JAR
COPY src ./src
RUN mvn clean package -DskipTests -B

# ==============================================================================
# Stage 2: Hardened Production Runtime
# ==============================================================================
FROM eclipse-temurin:21-jre-alpine AS runtime

LABEL maintainer="Gatekeeper Architecture Team" \
      description="Gatekeeper API Gateway & Policy Engine"

# Create dedicated non-root application user and group
RUN addgroup -g 10001 -S appgroup && \
    adduser -u 10001 -S appuser -G appgroup

WORKDIR /app

# Copy compiled application JAR from builder stage
COPY --from=builder --chown=appuser:appgroup /build/target/gatekeeper-backend-*.jar /app/app.jar

# JVM memory management tailored for container cgroups and low-memory environments
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom" \
    SERVER_PORT=8080 \
    SPRING_PROFILES_ACTIVE=prod

EXPOSE 8080

# Healthcheck leveraging dedicated public health endpoint
HEALTHCHECK --interval=20s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:${SERVER_PORT}/api/v1/health | grep -q "UP" || exit 1

# Execute as non-root user
USER appuser:appgroup

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
