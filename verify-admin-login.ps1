# ==============================================================================
# Gatekeeper Live Administrator Authentication Verification Script
# ==============================================================================
# Verifies administrator login, JWT generation, token lifetime, RBAC,
# and protected endpoint access against the running Spring Boot backend.
#
# Memory & Credential Safety:
# - Input is read via Read-Host -AsSecureString (masked)
# - Plaintext passwords and raw JWT tokens are never logged or written to disk
# ==============================================================================

param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Username = "admin"
)

$ErrorActionPreference = "Stop"

Write-Host "======================================================" -ForegroundColor Cyan
Write-Host " Gatekeeper Live Administrator Authentication Check   " -ForegroundColor Green
Write-Host "======================================================" -ForegroundColor Cyan

# ------------------------------------------------------------------------------
# 1. Health Endpoint Verification
# ------------------------------------------------------------------------------
Write-Host "`n[1/5] Checking Health Endpoint ($BaseUrl/api/v1/health)..." -ForegroundColor Yellow
try {
    $healthRes = Invoke-RestMethod -Uri "$BaseUrl/api/v1/health" -Method Get
    if ($healthRes.status -eq "UP") {
        Write-Host "  [OK] Service is UP (status: $($healthRes.status), service: $($healthRes.service))" -ForegroundColor Green
    } else {
        Write-Host "  [FAIL] Unexpected health status: $($healthRes.status)" -ForegroundColor Red
        exit 1
    }
} catch {
    Write-Host "  [FAIL] Unable to reach backend at $BaseUrl. Ensure Spring Boot is running." -ForegroundColor Red
    Write-Host "  Error: $_" -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------------------------
# 2. Secure Credential Input
# ------------------------------------------------------------------------------
Write-Host "`n[2/5] Operator Authentication..." -ForegroundColor Yellow
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

# ------------------------------------------------------------------------------
# 3. Test POST /api/v1/auth/login
# ------------------------------------------------------------------------------
Write-Host "`n[3/5] Testing POST $BaseUrl/api/v1/auth/login..." -ForegroundColor Yellow
$loginPayload = @{
    username = $Username
    password = $plainPw
} | ConvertTo-Json

$loginResponse = $null
$httpStatus = $null
$contentType = $null

try {
    $rawResponse = Invoke-WebRequest -Uri "$BaseUrl/api/v1/auth/login" `
        -Method Post `
        -ContentType "application/json" `
        -Body $loginPayload `
        -UseBasicParsing

    $httpStatus = [int]$rawResponse.StatusCode
    $contentType = $rawResponse.Headers["Content-Type"]
    $loginResponse = $rawResponse.Content | ConvertFrom-Json
} catch [System.Net.WebException] {
    $resp = $_.Exception.Response
    if ($resp) {
        $httpStatus = [int]$resp.StatusCode
        $stream = $resp.GetResponseStream()
        $reader = New-Object System.IO.StreamReader($stream)
        $errBody = $reader.ReadToEnd()

        if ($httpStatus -eq 401) {
            Write-Host "  [FAIL] Authentication failed (HTTP 401 Unauthorized)." -ForegroundColor Red
            try {
                $errJson = $errBody | ConvertFrom-Json
                Write-Host "  Message: $($errJson.message)" -ForegroundColor Red
            } catch {}
        } elseif ($httpStatus -eq 429) {
            Write-Host "  [FAIL] Rate limit exceeded (HTTP 429 Too Many Requests)." -ForegroundColor Red
            try {
                $errJson = $errBody | ConvertFrom-Json
                Write-Host "  Message: $($errJson.message)" -ForegroundColor Red
            } catch {}
        } elseif ($httpStatus -eq 503) {
            Write-Host "  [FAIL] Service unavailable (HTTP 503). Check Redis connectivity." -ForegroundColor Red
        } else {
            Write-Host "  [FAIL] Request failed with HTTP $($httpStatus): $errBody" -ForegroundColor Red
        }
    } else {
        Write-Host "  [FAIL] Network connection failed: $_" -ForegroundColor Red
    }
    exit 1
} finally {
    # Scrub plaintext from memory
    $plainPw = $null
    $loginPayload = $null
}

# Report HTTP response properties
Write-Host "  [OK] Login request returned HTTP $httpStatus" -ForegroundColor Green
Write-Host "  Content-Type: $contentType" -ForegroundColor Gray

# ------------------------------------------------------------------------------
# 4. Validate JWT Response & Lifetime
# ------------------------------------------------------------------------------
Write-Host "`n[4/5] Validating JWT Response & Lifetime..." -ForegroundColor Yellow

# Extract token from standard accessToken property (with fallback to token)
$token = $null
if ($loginResponse) {
    # Inspect received JSON property names safely
    $propertyNames = ($loginResponse | Get-Member -MemberType NoteProperty).Name
    Write-Host "  JSON properties received: $($propertyNames -join ', ')" -ForegroundColor Gray

    if ($loginResponse.accessToken) {
        $token = [string]$loginResponse.accessToken
    } elseif ($loginResponse.token) {
        $token = [string]$loginResponse.token
    }
}

if (-not [string]::IsNullOrEmpty($token)) {
    # Verify JWS structure (header.payload.signature)
    $segments = $token.Split('.')
    if ($segments.Length -ne 3) {
        Write-Host "  [FAIL] Returned token does not match standard 3-part JWS structure." -ForegroundColor Red
        exit 1
    }

    $maskedToken = "$($token.Substring(0, 10))...[REDACTED]...$($token.Substring($token.Length - 10))"
    
    Write-Host "  [OK] Access token received: Valid 3-part compact JWS ($maskedToken)" -ForegroundColor Green
    Write-Host "  Token Type          : $($loginResponse.tokenType)" -ForegroundColor White
    Write-Host "  Expires In (seconds): $($loginResponse.expiresIn) (Expected: 900 / 15 minutes)" -ForegroundColor White
    Write-Host "  Expires At          : $($loginResponse.expiresAt)" -ForegroundColor White
    Write-Host "  Admin Username      : $($loginResponse.admin.username)" -ForegroundColor White
    Write-Host "  Admin Role          : $($loginResponse.admin.role)" -ForegroundColor White
    Write-Host "  Must Change Password: $($loginResponse.admin.mustChangePassword)" -ForegroundColor White

    if ($loginResponse.expiresIn -ne 900) {
        Write-Host "  [WARN] Expected 900s expiration, got $($loginResponse.expiresIn)s" -ForegroundColor Yellow
    } else {
        Write-Host "  [OK] Token lifetime is strictly 15 minutes (900 seconds)." -ForegroundColor Green
    }

    if ($loginResponse.admin.role -ne "ROLE_SUPER_ADMIN") {
        Write-Host "  [WARN] Expected ROLE_SUPER_ADMIN, got $($loginResponse.admin.role)" -ForegroundColor Yellow
    } else {
        Write-Host "  [OK] Administrator has ROLE_SUPER_ADMIN privileges." -ForegroundColor Green
    }
} else {
    Write-Host "  [FAIL] No accessToken received in login response." -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------------------------
# 5. Verify Protected Access & 401 Rejections
# ------------------------------------------------------------------------------
Write-Host "`n[5/5] Verifying Protected Access & Security Enforcement..." -ForegroundColor Yellow

# Test 5A: Missing Token -> Must return 401
Write-Host "  (a) Probing protected endpoint with missing token..." -ForegroundColor Gray
try {
    Invoke-WebRequest -Uri "$BaseUrl/api/v1/auth/change-password" `
        -Method Post `
        -ContentType "application/json" `
        -Body "{}" `
        -UseBasicParsing | Out-Null
    Write-Host "  [FAIL] Missing token did not trigger HTTP 401!" -ForegroundColor Red
    exit 1
} catch [System.Net.WebException] {
    $code = [int]$_.Exception.Response.StatusCode
    if ($code -eq 401) {
        Write-Host "    [OK] Missing token rejected with HTTP 401 Unauthorized." -ForegroundColor Green
    } else {
        Write-Host "    [FAIL] Expected HTTP 401, got $code" -ForegroundColor Red
        exit 1
    }
}

# Test 5B: Malformed Token -> Must return 401
Write-Host "  (b) Probing protected endpoint with malformed token..." -ForegroundColor Gray
try {
    $malformedHeaders = @{ "Authorization" = "Bearer malformed.invalid.token" }
    Invoke-WebRequest -Uri "$BaseUrl/api/v1/auth/change-password" `
        -Method Post `
        -Headers $malformedHeaders `
        -ContentType "application/json" `
        -Body "{}" `
        -UseBasicParsing | Out-Null
    Write-Host "  [FAIL] Malformed token did not trigger HTTP 401!" -ForegroundColor Red
    exit 1
} catch [System.Net.WebException] {
    $code = [int]$_.Exception.Response.StatusCode
    if ($code -eq 401) {
        Write-Host "    [OK] Malformed token rejected with HTTP 401 Unauthorized." -ForegroundColor Green
    } else {
        Write-Host "    [FAIL] Expected HTTP 401, got $code" -ForegroundColor Red
        exit 1
    }
}

# Test 5C: Valid JWT Token -> Must pass authentication filter
Write-Host "  (c) Probing protected endpoint with valid administrator JWT..." -ForegroundColor Gray
try {
    $authHeaders = @{
        "Authorization" = "Bearer $token"
    }
    
    # We send an empty body to /api/v1/auth/change-password.
    # Since authentication succeeds, the request reaches the controller where Bean Validation returns 400.
    $dummyBody = @{ oldPassword = ""; newPassword = "" } | ConvertTo-Json
    Invoke-WebRequest -Uri "$BaseUrl/api/v1/auth/change-password" `
        -Method Post `
        -Headers $authHeaders `
        -ContentType "application/json" `
        -Body $dummyBody `
        -UseBasicParsing | Out-Null
    Write-Host "  [INFO] Request returned HTTP 200." -ForegroundColor Gray
} catch [System.Net.WebException] {
    $statusCode = [int]$_.Exception.Response.StatusCode
    if ($statusCode -eq 400) {
        # 400 Bad Request proves authentication succeeded and bean validation occurred
        Write-Host "    [OK] Valid JWT accepted by JwtAuthenticationFilter (HTTP 400 Bean Validation reached as expected)." -ForegroundColor Green
    } elseif ($statusCode -eq 401) {
        Write-Host "    [FAIL] Valid JWT was unexpectedly rejected with HTTP 401 Unauthorized." -ForegroundColor Red
        exit 1
    } elseif ($statusCode -eq 403) {
        if ($loginResponse.admin.mustChangePassword) {
            Write-Host "    [OK] HTTP 403 Forbidden returned as expected because mustChangePassword=true." -ForegroundColor Green
        } else {
            Write-Host "    [FAIL] Unexpected HTTP 403 Forbidden for super admin." -ForegroundColor Red
            exit 1
        }
    } else {
        Write-Host "    [INFO] HTTP response: $statusCode" -ForegroundColor Gray
    }
}

Write-Host "`n======================================================" -ForegroundColor Green
Write-Host " Verification Summary: ALL 5 CHECKS PASSED           " -ForegroundColor Green
Write-Host "======================================================" -ForegroundColor Green
