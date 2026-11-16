# Gatekeeper Development Environment Activation Script
# Usage: . .\env.ps1

$env:JAVA_HOME = "D:\Development\tools\jdk-21"
$env:MAVEN_HOME = "D:\Development\tools\maven"
$env:M2_HOME = "D:\Development\tools\maven"
$env:PATH = "D:\Development\tools\jdk-21\bin;D:\Development\tools\maven\bin;$env:PATH"

# Redirect Temp and Caches to D:
$env:TEMP = "D:\Development\temp"
$env:TMP = "D:\Development\temp"
$env:MAVEN_OPTS = "-Dmaven.repo.local=D:/Development/maven-repository"
$env:npm_config_cache = "D:\Development\caches\npm"

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host " Gatekeeper Environment Activated (D:)" -ForegroundColor Green
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "JAVA_HOME       : $env:JAVA_HOME"
Write-Host "MAVEN_HOME      : $env:MAVEN_HOME"
Write-Host "Maven Repo      : D:\Development\maven-repository"
Write-Host "Temp Directory  : $env:TEMP"
Write-Host "npm Cache       : $env:npm_config_cache"
Write-Host "==========================================" -ForegroundColor Cyan
