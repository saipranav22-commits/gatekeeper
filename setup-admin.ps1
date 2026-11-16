# ==============================================================================
# Gatekeeper Initial Administrator Bootstrap Script (Local Development)
# ==============================================================================
# Securely prompts for administrator credentials without command-line parameter
# exposure or storing plaintext passwords in script files.
# ==============================================================================

. .\env.ps1

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host " Gatekeeper Initial Admin Bootstrap Setup" -ForegroundColor Green
Write-Host "==========================================" -ForegroundColor Cyan

$adminUsername = Read-Host "Enter initial administrator username (e.g. superadmin)"
$adminEmail = Read-Host "Enter initial administrator email (e.g. admin@gatekeeper.local)"
$securePassword = Read-Host "Enter initial administrator password (min 12 chars, upper/lower/digit/special)" -AsSecureString

# Convert SecureString to process-only memory string
$bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
$plainPass = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
[System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)

$env:BOOTSTRAP_ADMIN_USERNAME = $adminUsername
$env:BOOTSTRAP_ADMIN_EMAIL = $adminEmail
$env:BOOTSTRAP_ADMIN_PASSWORD = $plainPass

try {
    Set-Location D:\Development\Gatekeeper\backend
    mvn spring-boot:run "-Dspring-boot.run.arguments=--gatekeeper.bootstrap.enabled=true"
} finally {
    Remove-Item Env:\BOOTSTRAP_ADMIN_USERNAME -ErrorAction SilentlyContinue
    Remove-Item Env:\BOOTSTRAP_ADMIN_EMAIL -ErrorAction SilentlyContinue
    Remove-Item Env:\BOOTSTRAP_ADMIN_PASSWORD -ErrorAction SilentlyContinue
    $plainPass = $null
    [System.GC]::Collect()
}
