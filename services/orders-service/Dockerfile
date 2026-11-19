# ==============================================================================
# Gatekeeper Upstream Orders Microservice - Production Multi-Stage Dockerfile
# ==============================================================================
# Stage 1: Build Application Artifact
# ==============================================================================
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Cache Maven dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy application source code and compile
COPY src ./src
RUN mvn clean package -DskipTests -B

# ==============================================================================
# Stage 2: Hardened Production Runtime
# ==============================================================================
FROM eclipse-temurin:21-jre-alpine AS runtime

LABEL maintainer="Gatekeeper Architecture Team" \
      description="Gatekeeper Upstream Orders Microservice"

# Create dedicated non-root application user and group
RUN addgroup -g 10001 -S appgroup && \
    adduser -u 10001 -S appuser -G appgroup

WORKDIR /app

# Copy compiled JAR
COPY --from=builder --chown=appuser:appgroup /build/target/*.jar /app/app.jar

# JVM memory tuning for lightweight microservice
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom" \
    ORDERS_PORT=8081 \
    SPRING_PROFILES_ACTIVE=prod

EXPOSE 8081

# Healthcheck checking microservice dedicated health endpoint
HEALTHCHECK --interval=20s --timeout=5s --start-period=25s --retries=3 \
  CMD wget -qO- http://localhost:${ORDERS_PORT}/health | grep -q "UP" || exit 1

# Execute as non-root user
USER appuser:appgroup

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
