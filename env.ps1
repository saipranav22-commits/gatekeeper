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

# Safely load local .env variables for Spring Boot (without printing secrets)
$dotenvPath = "D:\Development\Gatekeeper\.env"
if (Test-Path $dotenvPath) {
    Get-Content $dotenvPath | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
            $parts = $line.Split("=", 2)
            $varName = $parts[0].Trim()
            $varVal = $parts[1].Trim()
            [System.Environment]::SetEnvironmentVariable($varName, $varVal, "Process")
        }
    }
    # Map to standard database and redis connection variables
    if ($env:POSTGRES_USER) { $env:DB_USERNAME = $env:POSTGRES_USER }
    if ($env:POSTGRES_PASSWORD) { $env:DB_PASSWORD = $env:POSTGRES_PASSWORD }
    if ($env:POSTGRES_DB) { $env:DB_NAME = $env:POSTGRES_DB }
    if (-not $env:DB_HOST) { $env:DB_HOST = "127.0.0.1" }
    if (-not $env:DB_PORT) { $env:DB_PORT = "5434" }
    if (-not $env:REDIS_HOST) { $env:REDIS_HOST = "127.0.0.1" }
    if (-not $env:REDIS_PORT) { $env:REDIS_PORT = "6380" }
}

# Ensure stable 256-bit JWT secret is persisted across restarts on D: for local development
$jwtKeyFile = "D:\Development\Gatekeeper\.jwt.key"
if (Test-Path $jwtKeyFile) {
    $existingKey = (Get-Content $jwtKeyFile -Raw).Trim()
    if ($existingKey.Length -ge 64) {
        $env:GATEKEEPER_JWT_SECRET = $existingKey
    }
}

if (-not $env:GATEKEEPER_JWT_SECRET) {
    $bytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $generatedSecret = -join ($bytes | ForEach-Object { '{0:x2}' -f $_ })
    Set-Content -Path $jwtKeyFile -Value $generatedSecret -NoNewline
    $env:GATEKEEPER_JWT_SECRET = $generatedSecret
}

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host " Gatekeeper Environment Activated (D:)" -ForegroundColor Green
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "JAVA_HOME       : $env:JAVA_HOME"
Write-Host "MAVEN_HOME      : $env:MAVEN_HOME"
Write-Host "Maven Repo      : D:\Development\maven-repository"
Write-Host "Temp Directory  : $env:TEMP"
Write-Host "PostgreSQL Host : 127.0.0.1:5434"
Write-Host "Redis Host      : 127.0.0.1:6380"
Write-Host "JWT Secret      : [Configured from environment]"
Write-Host "==========================================" -ForegroundColor Cyan
