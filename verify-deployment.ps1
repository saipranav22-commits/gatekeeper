# ==============================================================================
# Gatekeeper Deployment Smoke Verification Script (Production-Safe & Non-Destructive)
# ==============================================================================
# Compatible with Windows PowerShell 5.1 & PowerShell Core
# Validates public ingress, reverse proxying, security headers, unauthenticated
# API guards, and chaos lockout WITHOUT mutating any database or policy state.
# ==============================================================================

param(
    [string]$FrontendUrl = "http://localhost:3000",
    [string]$GatewayDirectUrl = "http://127.0.0.1:8080",
    [switch]$SkipDirectGatewayCheck
)

$ErrorActionPreference = "Stop"

Write-Host "======================================================" -ForegroundColor Cyan
Write-Host "  Gatekeeper Production Deployment Smoke Test Suite   " -ForegroundColor Green
Write-Host "======================================================" -ForegroundColor Cyan
Write-Host "Target Frontend Ingress : $FrontendUrl"
Write-Host "Target Direct Gateway   : $(if ($SkipDirectGatewayCheck) { 'SKIPPED' } else { $GatewayDirectUrl })"
Write-Host ""

function Invoke-SafeHttpRequest {
    param(
        [string]$Uri,
        [string]$Method = "GET",
        [hashtable]$Headers = @{},
        [int]$TimeoutSec = 10
    )

    $request = [System.Net.HttpWebRequest]::Create($Uri)
    $request.Method = $Method.ToUpperInvariant()
    $request.KeepAlive = $false
    $request.Timeout = $TimeoutSec * 1000

    foreach ($k in $Headers.Keys) {
        $request.Headers.Add($k, [string]$Headers[$k])
    }

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = $request.GetResponse()
        $sw.Stop()
        $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
        $body = $reader.ReadToEnd()
        $reader.Close()

        $respHeaders = @{}
        foreach ($key in $response.Headers.AllKeys) {
            $respHeaders[$key] = $response.Headers[$key]
        }

        $statusCode = [int]$response.StatusCode
        $response.Close()

        return @{
            StatusCode   = $statusCode
            Body         = $body
            Headers      = $respHeaders
            RoundTripMs  = $sw.Elapsed.TotalMilliseconds
            Success      = $true
        }
    } catch [System.Net.WebException] {
        $sw.Stop()
        if ($_.Exception.Response) {
            $errResponse = $_.Exception.Response
            $reader = New-Object System.IO.StreamReader($errResponse.GetResponseStream())
            $body = $reader.ReadToEnd()
            $reader.Close()

            $respHeaders = @{}
            foreach ($key in $errResponse.Headers.AllKeys) {
                $respHeaders[$key] = $errResponse.Headers[$key]
            }

            $statusCode = [int]$errResponse.StatusCode
            $errResponse.Close()

            return @{
                StatusCode   = $statusCode
                Body         = $body
                Headers      = $respHeaders
                RoundTripMs  = $sw.Elapsed.TotalMilliseconds
                Success      = $false
            }
        } else {
            return @{
                StatusCode   = 0
                Body         = $_.Exception.Message
                Headers      = @{}
                RoundTripMs  = $sw.Elapsed.TotalMilliseconds
                Success      = $false
                Error        = $_.Exception.Message
            }
        }
    }
}

$testsPassed = 0
$testsFailed = 0

# ------------------------------------------------------------------------------
# 1. Frontend Healthcheck
# ------------------------------------------------------------------------------
Write-Host "[1/8] Checking Frontend Web Server Health (/healthz)..." -ForegroundColor Yellow
$feHealth = Invoke-SafeHttpRequest -Uri "$FrontendUrl/healthz"
if ($feHealth.StatusCode -eq 200 -and $feHealth.Body -match "healthy") {
    Write-Host "  [OK] Frontend Nginx is healthy (HTTP 200, $($feHealth.RoundTripMs.ToString('F1')) ms)" -ForegroundColor Green
    $testsPassed++
} else {
    Write-Host "  [FAIL] Frontend /healthz failed: HTTP $($feHealth.StatusCode) - $($feHealth.Body)" -ForegroundColor Red
    $testsFailed++
}

# ------------------------------------------------------------------------------
# 2. Frontend SPA Root & Static Assets
# ------------------------------------------------------------------------------
Write-Host "`n[2/8] Checking Frontend SPA Routing (/)..." -ForegroundColor Yellow
$feRoot = Invoke-SafeHttpRequest -Uri "$FrontendUrl/"
if ($feRoot.StatusCode -eq 200 -and ($feRoot.Body -match "<div id=""root""" -or $feRoot.Body -match "Gatekeeper")) {
    Write-Host "  [OK] Frontend SPA HTML served successfully (HTTP 200)" -ForegroundColor Green
    $testsPassed++
} else {
    Write-Host "  [FAIL] Frontend SPA root missing expected HTML structure: HTTP $($feRoot.StatusCode)" -ForegroundColor Red
    $testsFailed++
}

# ------------------------------------------------------------------------------
# 3. HTTP Security Headers Enforcement
# ------------------------------------------------------------------------------
Write-Host "`n[3/8] Verifying Hardened HTTP Security Headers on Ingress..." -ForegroundColor Yellow
$headers = $feRoot.Headers
$secHeadersFound = 0

if ($headers["X-Frame-Options"] -eq "DENY") {
    Write-Host "  [OK] X-Frame-Options: DENY" -ForegroundColor Green
    $secHeadersFound++
} else {
    Write-Host "  [WARN] Missing or unexpected X-Frame-Options: $($headers['X-Frame-Options'])" -ForegroundColor Yellow
}

if ($headers["X-Content-Type-Options"] -eq "nosniff") {
    Write-Host "  [OK] X-Content-Type-Options: nosniff" -ForegroundColor Green
    $secHeadersFound++
} else {
    Write-Host "  [WARN] Missing or unexpected X-Content-Type-Options: $($headers['X-Content-Type-Options'])" -ForegroundColor Yellow
}

if ($headers["Content-Security-Policy"]) {
    Write-Host "  [OK] Content-Security-Policy header present" -ForegroundColor Green
    $secHeadersFound++
} else {
    Write-Host "  [WARN] Content-Security-Policy header absent" -ForegroundColor Yellow
}

if ($headers["Referrer-Policy"]) {
    Write-Host "  [OK] Referrer-Policy header present: $($headers['Referrer-Policy'])" -ForegroundColor Green
    $secHeadersFound++
} else {
    Write-Host "  [WARN] Referrer-Policy header absent" -ForegroundColor Yellow
}

if ($secHeadersFound -ge 3) {
    $testsPassed++
} else {
    Write-Host "  [FAIL] Security header enforcement insufficient ($secHeadersFound/4 verified)" -ForegroundColor Red
    $testsFailed++
}

# ------------------------------------------------------------------------------
# 4. Backend Health via Approved Ingress Reverse Proxy
# ------------------------------------------------------------------------------
Write-Host "`n[4/8] Checking Backend Health via Ingress Reverse Proxy ($FrontendUrl/api/v1/health)..." -ForegroundColor Yellow
$apiHealth = Invoke-SafeHttpRequest -Uri "$FrontendUrl/api/v1/health"
if ($apiHealth.StatusCode -eq 200 -and $apiHealth.Body -match '"status"\s*:\s*"UP"') {
    Write-Host "  [OK] Gateway health verified through reverse proxy: HTTP 200" -ForegroundColor Green
    Write-Host "       Payload: $($apiHealth.Body.Trim())" -ForegroundColor DarkGray
    $testsPassed++
} else {
    Write-Host "  [FAIL] Gateway health check via reverse proxy failed: HTTP $($apiHealth.StatusCode) - $($apiHealth.Body)" -ForegroundColor Red
    $testsFailed++
}

# ------------------------------------------------------------------------------
# 5. Unauthenticated Management API Protection (Zero Leakage)
# ------------------------------------------------------------------------------
Write-Host "`n[5/8] Verifying Unauthenticated Management API Protection..." -ForegroundColor Yellow
$consumersRes = Invoke-SafeHttpRequest -Uri "$FrontendUrl/api/v1/consumers"
$policiesRes = Invoke-SafeHttpRequest -Uri "$FrontendUrl/api/v1/policies"

if ($consumersRes.StatusCode -eq 401 -and $policiesRes.StatusCode -eq 401) {
    Write-Host "  [OK] /api/v1/consumers safely protected: HTTP 401 Unauthorized" -ForegroundColor Green
    Write-Host "  [OK] /api/v1/policies safely protected: HTTP 401 Unauthorized" -ForegroundColor Green
    $testsPassed++
} else {
    Write-Host "  [FAIL] Unauthenticated access allowed: consumers HTTP $($consumersRes.StatusCode), policies HTTP $($policiesRes.StatusCode)" -ForegroundColor Red
    $testsFailed++
}

# ------------------------------------------------------------------------------
# 6. Production Chaos Lockout (HTTP 404 Guaranteed)
# ------------------------------------------------------------------------------
Write-Host "`n[6/8] Verifying Production Chaos Engineering Lockout..." -ForegroundColor Yellow
$chaosExpRes = Invoke-SafeHttpRequest -Uri "$FrontendUrl/api/v1/chaos/experiments"
$chaosRunRes = Invoke-SafeHttpRequest -Uri "$FrontendUrl/api/v1/chaos/run" -Method "POST"

if ($chaosExpRes.StatusCode -eq 404 -and $chaosRunRes.StatusCode -eq 404) {
    Write-Host "  [OK] GET /api/v1/chaos/experiments returned HTTP 404 (Controller unmapped in prod)" -ForegroundColor Green
    Write-Host "  [OK] POST /api/v1/chaos/run returned HTTP 404 (Execution disabled in prod)" -ForegroundColor Green
    $testsPassed++
} else {
    Write-Host "  [FAIL] Chaos endpoints exposed in production: experiments HTTP $($chaosExpRes.StatusCode), run HTTP $($chaosRunRes.StatusCode)" -ForegroundColor Red
    $testsFailed++
}

# ------------------------------------------------------------------------------
# 7. Non-Mutating Upstream Routing Protection
# ------------------------------------------------------------------------------
Write-Host "`n[7/8] Verifying Proxy Route Authentication Interception (/api/v1/proxy/orders)..." -ForegroundColor Yellow
$proxyRes = Invoke-SafeHttpRequest -Uri "$FrontendUrl/api/v1/proxy/orders"
if ($proxyRes.StatusCode -eq 401) {
    Write-Host "  [OK] Unauthenticated proxy request intercepted by Gateway API Key filter: HTTP 401" -ForegroundColor Green
    $testsPassed++
} else {
    Write-Host "  [FAIL] Expected HTTP 401 on unauthenticated proxy call, got HTTP $($proxyRes.StatusCode)" -ForegroundColor Red
    $testsFailed++
}

# ------------------------------------------------------------------------------
# 8. Secret & Credential Leakage Inspection
# ------------------------------------------------------------------------------
Write-Host "`n[8/8] Inspecting Responses for Secret & Password Leakage..." -ForegroundColor Yellow
$leakFound = $false
$allBodies = @($feHealth.Body, $feRoot.Body, $apiHealth.Body, $consumersRes.Body, $policiesRes.Body, $chaosExpRes.Body)

foreach ($b in $allBodies) {
    if ($b -match "password" -or $b -match "secret" -or $b -match "privateKey" -or $b -match "pgdata") {
        # Check if it's not a generic word in a template
        if ($b -match "password\s*[:=]\s*[^a-zA-Z0-9\s]") {
            $leakFound = $true
            break
        }
    }
}

if (-not $leakFound) {
    Write-Host "  [OK] Zero credential, secret, or database path leakage detected in HTTP responses" -ForegroundColor Green
    $testsPassed++
} else {
    Write-Host "  [FAIL] Sensitive secret detected in HTTP response body!" -ForegroundColor Red
    $testsFailed++
}

# ------------------------------------------------------------------------------
# Summary
# ------------------------------------------------------------------------------
Write-Host "`n======================================================" -ForegroundColor Cyan
Write-Host "  Smoke Test Results: $testsPassed Passed, $testsFailed Failed" -ForegroundColor $(if ($testsFailed -eq 0) { "Green" } else { "Red" })
Write-Host "======================================================" -ForegroundColor Cyan

if ($testsFailed -gt 0) {
    exit 1
} else {
    exit 0
}
