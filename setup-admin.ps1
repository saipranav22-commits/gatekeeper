# ==============================================================================
# Gatekeeper Initial Administrator Bootstrap Script (Local Development)
# ==============================================================================
# Triggers the one-time interactive bootstrap runner.
# Credentials are read directly by the Java runtime via Console/stdin without
# passing passwords as CLI arguments or persisting to disk.
# ==============================================================================

. .\env.ps1

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host " Gatekeeper Initial Admin Bootstrap Setup" -ForegroundColor Green
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "Starting Gatekeeper bootstrap runner in interactive mode..." -ForegroundColor Yellow

Set-Location D:\Development\Gatekeeper\backend
mvn spring-boot:run "-Dspring-boot.run.arguments=--gatekeeper.bootstrap.enabled=true"
