# Gatekeeper

Gatekeeper is an API Key Management and Intelligent Rate Limiting Platform built with Spring Boot and Java 21.

## Prerequisites

- **Java**: Eclipse Temurin OpenJDK 21 (`D:\Development\tools\jdk-21`)
- **Maven**: Apache Maven 3.9.16 (`D:\Development\tools\maven`)
- **Environment**: Set up using `env.ps1` located at the root of this project.

## Environment Initialization

Before running build or execution commands in PowerShell:

```powershell
. .\env.ps1
```

This configures:
- `JAVA_HOME` pointing to `D:\Development\tools\jdk-21`
- `MAVEN_HOME` pointing to `D:\Development\tools\maven`
- Maven local repository redirect to `D:\Development\maven-repository`
- Temporary directory redirection to `D:\Development\temp`

## Building the Project

Navigate to the backend module:

```powershell
cd D:\Development\Gatekeeper\backend
mvn clean compile
```

Run test suite:

```powershell
mvn test
```

## Running the Application

### Local Foundation Profile (Phase 1A)

The foundation profile enables starting Gatekeeper locally without requiring live PostgreSQL or Redis instances, while keeping Spring Security active.

```powershell
mvn spring-boot:run -Dspring-boot.run.profiles=foundation
```

Or via packaged jar:

```powershell
mvn package
java -jar target/gatekeeper-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=foundation
```

## Verification

- Health Check Endpoint:
  - `GET http://localhost:8080/api/v1/health`
  - Expected Response: `{"status":"UP","service":"gatekeeper","serviceName":"gatekeeper","timestamp":"..."}`
- Protected Endpoints:
  - Any other endpoint (e.g., `GET http://localhost:8080/api/v1/consumers`) requires authentication and will return `401 Unauthorized` by default.
