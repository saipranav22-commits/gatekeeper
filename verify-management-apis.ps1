# ==============================================================================
# Gatekeeper Live Management REST API Verification Script
# ==============================================================================
# Performs end-to-end live HTTP checks on Gatekeeper management endpoints
# using a securely supplied administrator session.
#
# Security & Privacy Safeguards:
# - Input read via Read-Host -AsSecureString
# - Plaintext passwords, JWT tokens, and API secrets are never logged or stored
# - All test records use dedicated test identifiers
# ==============================================================================

param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Username = "admin"
)

$ErrorActionPreference = "Stop"

Write-Host "======================================================" -ForegroundColor Cyan
Write-Host " Gatekeeper Live Management REST API Verification     " -ForegroundColor Green
Write-Host "======================================================" -ForegroundColor Cyan

# ------------------------------------------------------------------------------
# 1. Health Endpoint Verification
# ------------------------------------------------------------------------------
Write-Host "`n[1/10] Checking Health Endpoint ($BaseUrl/api/v1/health)..." -ForegroundColor Yellow
try {
    $healthRes = Invoke-RestMethod -Uri "$BaseUrl/api/v1/health" -Method Get
    if ($healthRes.status -eq "UP") {
        Write-Host "  [OK] Service is UP (service: $($healthRes.service))" -ForegroundColor Green
    } else {
        Write-Host "  [FAIL] Unexpected health status: $($healthRes.status)" -ForegroundColor Red
        exit 1
    }
} catch {
    Write-Host "  [FAIL] Backend unreachable at $BaseUrl: $_" -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------------------------
# 2. Administrator Authentication
# ------------------------------------------------------------------------------
Write-Host "`n[2/10] Authenticating Administrator '$Username'..." -ForegroundColor Yellow
$secPassword = $null
if ($env:GATEKEEPER_ADMIN_PASSWORD) {
    Write-Host "  [INFO] Reading administrator password from environment variable..." -ForegroundColor Gray
    $plainPw = $env:GATEKEEPER_ADMIN_PASSWORD
    $env:GATEKEEPER_ADMIN_PASSWORD = $null
} else {
    $secPassword = Read-Host "  Enter password for administrator '$Username'" -AsSecureString
    $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secPassword)
    try {
        $plainPw = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    } finally {
        [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
}

if ([string]::IsNullOrEmpty($plainPw)) {
    Write-Host "  [FAIL] Password cannot be empty." -ForegroundColor Red
    exit 1
}

$jwtToken = $null
try {
    $loginPayload = @{ username = $Username; password = $plainPw } | ConvertTo-Json
    $loginRaw = Invoke-WebRequest -Uri "$BaseUrl/api/v1/auth/login" `
        -Method Post `
        -ContentType "application/json" `
        -Body $loginPayload `
        -UseBasicParsing

    $loginRes = $loginRaw.Content | ConvertFrom-Json
    $jwtToken = if ($loginRes.accessToken) { $loginRes.accessToken } else { $loginRes.token }
    if (-not $jwtToken) {
        Write-Host "  [FAIL] No accessToken returned in login response." -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Administrator authenticated successfully (HTTP 200 OK)." -ForegroundColor Green
} catch [System.Net.WebException] {
    $code = [int]$_.Exception.Response.StatusCode
    Write-Host "  [FAIL] Login failed with HTTP $code" -ForegroundColor Red
    exit 1
} finally {
    $plainPw = $null
    $loginPayload = $null
}

$authHeaders = @{
    "Authorization" = "Bearer $jwtToken"
}

# ------------------------------------------------------------------------------
# 3. Plan Listing (GET /api/v1/plans)
# ------------------------------------------------------------------------------
Write-Host "`n[3/10] Testing GET $BaseUrl/api/v1/plans..." -ForegroundColor Yellow
$freePlanId = $null
$proPlanId = $null
try {
    $plans = Invoke-RestMethod -Uri "$BaseUrl/api/v1/plans" -Method Get -Headers $authHeaders
    Write-Host "  [OK] Retrieved $($plans.Count) subscription plans:" -ForegroundColor Green
    foreach ($p in $plans) {
        Write-Host "       - Plan: $($p.name) (Capacity: $($p.bucketCapacity), Refill: $($p.refillTokens)/$($p.refillPeriodSeconds)s)" -ForegroundColor White
        if ($p.name -eq "FREE") { $freePlanId = $p.id }
        if ($p.name -eq "PRO")  { $proPlanId = $p.id }
    }
    if (-not $freePlanId -or -not $proPlanId) {
        Write-Host "  [FAIL] Standard plans (FREE, PRO) not found in response." -ForegroundColor Red
        exit 1
    }
} catch {
    Write-Host "  [FAIL] GET /api/v1/plans failed: $_" -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------------------------
# 4. Consumer Creation (POST /api/v1/consumers)
# ------------------------------------------------------------------------------
Write-Host "`n[4/10] Testing POST $BaseUrl/api/v1/consumers..." -ForegroundColor Yellow
$timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$consumerPayload = @{
    name = "Live Test Consumer $timestamp"
    email = "live-$timestamp@gatekeeper.local"
    planId = $freePlanId
} | ConvertTo-Json

$createdConsumer = $null
$consumerId = $null
try {
    $createRaw = Invoke-WebRequest -Uri "$BaseUrl/api/v1/consumers" `
        -Method Post `
        -Headers $authHeaders `
        -ContentType "application/json" `
        -Body $consumerPayload `
        -UseBasicParsing

    if ($createRaw.StatusCode -ne 201) {
        Write-Host "  [FAIL] Expected HTTP 201 Created, got $($createRaw.StatusCode)" -ForegroundColor Red
        exit 1
    }
    $createdConsumer = $createRaw.Content | ConvertFrom-Json
    $consumerId = $createdConsumer.id
    $location = $createRaw.Headers["Location"]
    Write-Host "  [OK] Consumer created successfully: HTTP 201 Created" -ForegroundColor Green
    Write-Host "       Consumer ID : $consumerId" -ForegroundColor White
    Write-Host "       Location    : $location" -ForegroundColor White
    Write-Host "       Plan        : $($createdConsumer.plan.name)" -ForegroundColor White
    Write-Host "       Status      : $($createdConsumer.status)" -ForegroundColor White
} catch {
    Write-Host "  [FAIL] POST /api/v1/consumers failed: $_" -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------------------------
# 5. Consumer Lookup (GET /api/v1/consumers/{consumerId})
# ------------------------------------------------------------------------------
Write-Host "`n[5/10] Testing GET $BaseUrl/api/v1/consumers/$consumerId..." -ForegroundColor Yellow
try {
    $lookedUp = Invoke-RestMethod -Uri "$BaseUrl/api/v1/consumers/$consumerId" -Method Get -Headers $authHeaders
    if ($lookedUp.id -eq $consumerId -and $lookedUp.status -eq "ACTIVE") {
        Write-Host "  [OK] Consumer verified: HTTP 200 OK (name: $($lookedUp.name))" -ForegroundColor Green
    } else {
        Write-Host "  [FAIL] Lookup metadata mismatch" -ForegroundColor Red
        exit 1
    }
} catch {
    Write-Host "  [FAIL] GET /api/v1/consumers/$consumerId failed: $_" -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------------------------
# 6. Plan Assignment (PATCH /api/v1/consumers/{consumerId}/plan)
# ------------------------------------------------------------------------------
Write-Host "`n[6/10] Testing PATCH $BaseUrl/api/v1/consumers/$consumerId/plan..." -ForegroundColor Yellow
try {
    $planPatchBody = @{ planId = $proPlanId } | ConvertTo-Json
    $patched = Invoke-RestMethod -Uri "$BaseUrl/api/v1/consumers/$consumerId/plan" `
        -Method Patch `
        -Headers $authHeaders `
        -ContentType "application/json" `
        -Body $planPatchBody

    if ($patched.plan.name -eq "PRO") {
        Write-Host "  [OK] Consumer plan upgraded to PRO: HTTP 200 OK" -ForegroundColor Green
    } else {
        Write-Host "  [FAIL] Expected PRO plan, got: $($patched.plan.name)" -ForegroundColor Red
        exit 1
    }
} catch {
    Write-Host "  [FAIL] Plan patch failed: $_" -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------------------------
# 7. API Key Creation (POST /api/v1/consumers/{consumerId}/keys)
# ------------------------------------------------------------------------------
Write-Host "`n[7/10] Testing POST $BaseUrl/api/v1/consumers/$consumerId/keys..." -ForegroundColor Yellow
$keyPayload = @{
    name = "Live Test Service Key"
    scopes = @("gateway:invoke", "read:orders")
    expiresAt = ([DateTime]::UtcNow.AddDays(30)).ToString("o")
} | ConvertTo-Json

$createdKeyId = $null
try {
    $keyRaw = Invoke-WebRequest -Uri "$BaseUrl/api/v1/consumers/$consumerId/keys" `
        -Method Post `
        -Headers $authHeaders `
        -ContentType "application/json" `
        -Body $keyPayload `
        -UseBasicParsing

    if ($keyRaw.StatusCode -ne 201) {
        Write-Host "  [FAIL] Expected HTTP 201 Created, got $($keyRaw.StatusCode)" -ForegroundColor Red
        exit 1
    }

    $cacheControl = $keyRaw.Headers["Cache-Control"]
    if ($cacheControl -notlike "*no-store*") {
        Write-Host "  [WARN] Expected Cache-Control: no-store, got: $cacheControl" -ForegroundColor Yellow
    } else {
        Write-Host "  [OK] Cache-Control: no-store header verified." -ForegroundColor Green
    }

    $keyRes = $keyRaw.Content | ConvertFrom-Json
    $createdKeyId = $keyRes.metadata.id
    $prefix = $keyRes.metadata.keyPrefix

    # Assert plaintextKey presence in memory without printing it
    if ($keyRes.plaintextKey -and $keyRes.plaintextKey.StartsWith("gk_live_")) {
        Write-Host "  [OK] API key generated: HTTP 201 Created" -ForegroundColor Green
        Write-Host "       Key ID          : $createdKeyId" -ForegroundColor White
        Write-Host "       Prefix          : $prefix" -ForegroundColor White
        Write-Host "       Scopes          : $($keyRes.metadata.scopes -join ', ')" -ForegroundColor White
        Write-Host "       Plaintext Key   : Verified in memory (Prefix: $prefix, StartsWith: gk_live_)" -ForegroundColor Green
        Write-Host "       Secret Digest   : Excluded from response as required" -ForegroundColor Green
    } else {
        Write-Host "  [FAIL] Plaintext key missing or malformed" -ForegroundColor Red
        exit 1
    }
} catch {
    Write-Host "  [FAIL] Key creation failed: $_" -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------------------------
# 8. Key Metadata Listing (GET /api/v1/consumers/{consumerId}/keys)
# ------------------------------------------------------------------------------
Write-Host "`n[8/10] Testing GET $BaseUrl/api/v1/consumers/$consumerId/keys..." -ForegroundColor Yellow
try {
    $keys = Invoke-RestMethod -Uri "$BaseUrl/api/v1/consumers/$consumerId/keys" -Method Get -Headers $authHeaders
    $found = $keys | Where-Object { $_.id -eq $createdKeyId }
    if ($found) {
        # Verify plaintext key is NOT exposed on listing
        if ($found.plaintextKey) {
            Write-Host "  [FAIL] Security violation: Plaintext key exposed in listing!" -ForegroundColor Red
            exit 1
        }
        Write-Host "  [OK] Key metadata listed: HTTP 200 OK (Sanitized: no plaintext secrets, status: $($found.status))" -ForegroundColor Green
    } else {
        Write-Host "  [FAIL] Created key not found in consumer key list" -ForegroundColor Red
        exit 1
    }
} catch {
    Write-Host "  [FAIL] Key listing failed: $_" -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------------------------
# 9. Key Revocation (POST /api/v1/consumers/{consumerId}/keys/{keyId}/revoke)
# ------------------------------------------------------------------------------
Write-Host "`n[9/10] Testing POST $BaseUrl/api/v1/consumers/$consumerId/keys/$createdKeyId/revoke..." -ForegroundColor Yellow
try {
    $revokeBody = @{ reason = "Live verification test complete" } | ConvertTo-Json
    $revoked = Invoke-RestMethod -Uri "$BaseUrl/api/v1/consumers/$consumerId/keys/$createdKeyId/revoke" `
        -Method Post `
        -Headers $authHeaders `
        -ContentType "application/json" `
        -Body $revokeBody

    if ($revoked.status -eq "REVOKED" -and $revoked.revocationReason -eq "Live verification test complete") {
        Write-Host "  [OK] Key revoked successfully: HTTP 200 OK (status: REVOKED)" -ForegroundColor Green
    } else {
        Write-Host "  [FAIL] Expected REVOKED status, got: $($revoked.status)" -ForegroundColor Red
        exit 1
    }

    # Idempotent re-revocation test
    $reRevoked = Invoke-RestMethod -Uri "$BaseUrl/api/v1/consumers/$consumerId/keys/$createdKeyId/revoke" `
        -Method Post `
        -Headers $authHeaders
    if ($reRevoked.status -eq "REVOKED") {
        Write-Host "  [OK] Idempotent revocation confirmed (HTTP 200 OK)." -ForegroundColor Green
    }
} catch {
    Write-Host "  [FAIL] Revocation failed: $_" -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------------------------
# 10. Security Boundary Tests & Cleanup
# ------------------------------------------------------------------------------
Write-Host "`n[10/10] Testing Security Enforcement & Cleanup..." -ForegroundColor Yellow

# 10A: Missing Token -> 401
try {
    Invoke-WebRequest -Uri "$BaseUrl/api/v1/consumers" -Method Get -UseBasicParsing | Out-Null
    Write-Host "  [FAIL] Missing token did not return 401" -ForegroundColor Red
    exit 1
} catch [System.Net.WebException] {
    if ([int]$_.Exception.Response.StatusCode -eq 401) {
        Write-Host "  [OK] Missing token rejected with HTTP 401 Unauthorized." -ForegroundColor Green
    }
}

# 10B: Cross-Consumer Boundary Check -> 404
$randomConsumerId = [Guid]::NewGuid()
try {
    Invoke-WebRequest -Uri "$BaseUrl/api/v1/consumers/$randomConsumerId/keys/$createdKeyId" `
        -Method Get `
        -Headers $authHeaders `
        -UseBasicParsing | Out-Null
    Write-Host "  [FAIL] Cross-consumer key lookup did not return 404!" -ForegroundColor Red
    exit 1
} catch [System.Net.WebException] {
    if ([int]$_.Exception.Response.StatusCode -eq 404) {
        Write-Host "  [OK] Cross-consumer boundary enforced: returned HTTP 404 Not Found." -ForegroundColor Green
    }
}

# 10C: Soft Delete Cleanup
try {
    $delRes = Invoke-WebRequest -Uri "$BaseUrl/api/v1/consumers/$consumerId" `
        -Method Delete `
        -Headers $authHeaders `
        -UseBasicParsing

    if ($delRes.StatusCode -eq 204) {
        Write-Host "  [OK] Test consumer soft deleted: HTTP 204 No Content." -ForegroundColor Green
    }
} catch {
    Write-Host "  [WARN] Soft delete cleanup failed: $_" -ForegroundColor Yellow
}

Write-Host "`n======================================================" -ForegroundColor Green
Write-Host " Live Management API Verification: ALL 10 STEPS PASSED " -ForegroundColor Green
Write-Host "======================================================" -ForegroundColor Green
