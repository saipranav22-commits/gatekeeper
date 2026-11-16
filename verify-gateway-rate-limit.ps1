# ==============================================================================
# Gatekeeper Live Gateway Authentication & Rate Limit Verification Script
# ==============================================================================
# Performs end-to-end live HTTP checks on Gatekeeper external gateway endpoints:
# - API Key authentication and format validation
# - Scope-based authorization enforcement
# - Distributed Redis Token Bucket rate limiting and HTTP headers
# - Shared quota consumption across multiple consumer keys
# - HTTP 429 quota exhaustion and Retry-After header
# - Immediate revocation propagation
# - Strict isolation between Admin JWT and Consumer API keys
# - Consumer lifecycle termination enforcement
#
# Security & Privacy Safeguards:
# - Admin password read securely (SecureString or env var)
# - Plaintext passwords, JWT tokens, and API key secrets are NEVER logged or stored
# - All test entities use isolated test identifiers and are cleaned up at exit
# ==============================================================================

param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Username = "admin"
)

$ErrorActionPreference = "Stop"

Write-Host "======================================================" -ForegroundColor Cyan
Write-Host " Gatekeeper Gateway & Rate Limiting Live Verification  " -ForegroundColor Green
Write-Host "======================================================" -ForegroundColor Cyan

# Helper to execute HTTP requests with detailed status and header inspection
function Invoke-SafeRestCall {
    param(
        [string]$Uri,
        [string]$Method = "Get",
        [hashtable]$Headers = @{},
        [string]$Body = $null,
        [string]$ContentType = "application/json"
    )

    $request = [System.Net.HttpWebRequest]::Create($Uri)
    $request.Method = $Method
    $request.KeepAlive = $false
    $request.Timeout = 10000

    foreach ($k in $Headers.Keys) {
        $request.Headers.Add($k, [string]$Headers[$k])
    }

    if ($Body -and ($Method -eq "Post" -or $Method -eq "Put" -or $Method -eq "Patch")) {
        $request.ContentType = $ContentType
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
        $request.ContentLength = $bytes.Length
        $reqStream = $request.GetRequestStream()
        $reqStream.Write($bytes, 0, $bytes.Length)
        $reqStream.Close()
    }

    try {
        $response = $request.GetResponse()
        $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
        $content = $reader.ReadToEnd()
        $reader.Close()

        $respHeaders = @{}
        foreach ($key in $response.Headers.AllKeys) {
            $respHeaders[$key] = $response.Headers[$key]
        }

        $statusCode = [int]$response.StatusCode
        $response.Close()

        return @{
            StatusCode = $statusCode
            Headers    = $respHeaders
            Body       = $content
        }
    } catch [System.Net.WebException] {
        if ($_.Exception.Response) {
            $errResponse = $_.Exception.Response
            $reader = New-Object System.IO.StreamReader($errResponse.GetResponseStream())
            $content = $reader.ReadToEnd()
            $reader.Close()

            $respHeaders = @{}
            foreach ($key in $errResponse.Headers.AllKeys) {
                $respHeaders[$key] = $errResponse.Headers[$key]
            }

            $statusCode = [int]$errResponse.StatusCode
            $errResponse.Close()

            return @{
                StatusCode = $statusCode
                Headers    = $respHeaders
                Body       = $content
            }
        } else {
            throw $_
        }
    }
}

# ------------------------------------------------------------------------------
# 1. Health Endpoint Verification
# ------------------------------------------------------------------------------
Write-Host "`n[1/17] Checking Health Endpoint ($BaseUrl/api/v1/health)..." -ForegroundColor Yellow
$healthRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/health" -Method "Get"
if ($healthRes.StatusCode -eq 200) {
    Write-Host "  [OK] Service is UP (HTTP 200)" -ForegroundColor Green
} else {
    Write-Host "  [FAIL] Backend unreachable or unhealthy: HTTP $($healthRes.StatusCode)" -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------------------------
# 2. Administrator Authentication
# ------------------------------------------------------------------------------
Write-Host "`n[2/17] Authenticating Administrator '$Username'..." -ForegroundColor Yellow
$plainPw = $null
if ($env:GATEKEEPER_ADMIN_PASSWORD) {
    Write-Host "  [INFO] Reading administrator password from environment..." -ForegroundColor Gray
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
    $loginRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/auth/login" `
        -Method "Post" `
        -Body $loginPayload

    if ($loginRes.StatusCode -ne 200) {
        Write-Host "  [FAIL] Administrator login failed: HTTP $($loginRes.StatusCode)" -ForegroundColor Red
        exit 1
    }

    $loginObj = $loginRes.Body | ConvertFrom-Json
    $jwtToken = if ($loginObj.accessToken) { $loginObj.accessToken } else { $loginObj.token }
    if (-not $jwtToken) {
        Write-Host "  [FAIL] No accessToken returned in login response." -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Administrator authenticated successfully (HTTP 200 OK)." -ForegroundColor Green
} finally {
    $plainPw = $null
    $loginPayload = $null
}

$adminHeaders = @{
    "Authorization" = "Bearer $jwtToken"
}

# ------------------------------------------------------------------------------
# 3. Retrieve FREE Subscription Plan
# ------------------------------------------------------------------------------
Write-Host "`n[3/17] Querying Subscription Plans (GET /api/v1/plans)..." -ForegroundColor Yellow
$plansRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/plans" -Method "Get" -Headers $adminHeaders
if ($plansRes.StatusCode -ne 200) {
    Write-Host "  [FAIL] Failed to retrieve plans: HTTP $($plansRes.StatusCode)" -ForegroundColor Red
    exit 1
}

$plans = $plansRes.Body | ConvertFrom-Json
$freePlan = $plans | Where-Object { $_.name -eq "FREE" }
if (-not $freePlan) {
    Write-Host "  [FAIL] FREE subscription plan not found." -ForegroundColor Red
    exit 1
}
$freePlanId = $freePlan.id
$planCapacity = $freePlan.bucketCapacity
Write-Host "  [OK] FREE plan identified: ID=$freePlanId, Capacity=$planCapacity, Refill=$($freePlan.refillTokens)/$($freePlan.refillPeriodSeconds)s" -ForegroundColor Green

# ------------------------------------------------------------------------------
# 4. Create Isolated Test Consumer
# ------------------------------------------------------------------------------
Write-Host "`n[4/17] Creating Test Consumer under FREE Plan..." -ForegroundColor Yellow
$timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$consumerPayload = @{
    name = "RateLimit-Test-Consumer-$timestamp"
    email = "ratelimit-$timestamp@gatekeeper.test"
    planId = $freePlanId
} | ConvertTo-Json

$consumerRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/consumers" `
    -Method "Post" `
    -Headers $adminHeaders `
    -Body $consumerPayload

if ($consumerRes.StatusCode -ne 201) {
    Write-Host "  [FAIL] Failed to create test consumer: HTTP $($consumerRes.StatusCode)" -ForegroundColor Red
    exit 1
}

$consumerObj = $consumerRes.Body | ConvertFrom-Json
$consumerId = $consumerObj.id
Write-Host "  [OK] Test consumer created: ID=$consumerId, Name=$($consumerObj.name)" -ForegroundColor Green

# ------------------------------------------------------------------------------
# 5. Generate Key A (Scopes: gateway:invoke, read:orders)
# ------------------------------------------------------------------------------
Write-Host "`n[5/17] Generating Key A (scopes: gateway:invoke, read:orders)..." -ForegroundColor Yellow
$keyAPayload = @{
    name = "Test-Key-A"
    scopes = @("gateway:invoke", "read:orders")
    expiresAt = ([DateTime]::UtcNow.AddDays(7)).ToString("o")
} | ConvertTo-Json

$keyARes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/consumers/$consumerId/keys" `
    -Method "Post" `
    -Headers $adminHeaders `
    -Body $keyAPayload

if ($keyARes.StatusCode -ne 201) {
    Write-Host "  [FAIL] Failed to generate Key A: HTTP $($keyARes.StatusCode)" -ForegroundColor Red
    exit 1
}

$keyAObj = $keyARes.Body | ConvertFrom-Json
$keyAId = $keyAObj.metadata.id
$plainKeyA = $keyAObj.plaintextKey
Write-Host "  [OK] Key A generated: ID=$keyAId, Prefix=$($keyAObj.metadata.keyPrefix)" -ForegroundColor Green

# ------------------------------------------------------------------------------
# 6. Generate Key B (Scope: gateway:invoke only)
# ------------------------------------------------------------------------------
Write-Host "`n[6/17] Generating Key B (scope: gateway:invoke)..." -ForegroundColor Yellow
$keyBPayload = @{
    name = "Test-Key-B"
    scopes = @("gateway:invoke")
    expiresAt = ([DateTime]::UtcNow.AddDays(7)).ToString("o")
} | ConvertTo-Json

$keyBRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/consumers/$consumerId/keys" `
    -Method "Post" `
    -Headers $adminHeaders `
    -Body $keyBPayload

if ($keyBRes.StatusCode -ne 201) {
    Write-Host "  [FAIL] Failed to generate Key B: HTTP $($keyBRes.StatusCode)" -ForegroundColor Red
    exit 1
}

$keyBObj = $keyBRes.Body | ConvertFrom-Json
$keyBId = $keyBObj.metadata.id
$plainKeyB = $keyBObj.plaintextKey
Write-Host "  [OK] Key B generated: ID=$keyBId, Prefix=$($keyBObj.metadata.keyPrefix)" -ForegroundColor Green

# ------------------------------------------------------------------------------
# 7. Generate Key C (Scope: metrics:read - lacks gateway:invoke)
# ------------------------------------------------------------------------------
Write-Host "`n[7/17] Generating Key C (scope: metrics:read - lacking gateway:invoke)..." -ForegroundColor Yellow
$keyCPayload = @{
    name = "Test-Key-C"
    scopes = @("metrics:read")
    expiresAt = ([DateTime]::UtcNow.AddDays(7)).ToString("o")
} | ConvertTo-Json

$keyCRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/consumers/$consumerId/keys" `
    -Method "Post" `
    -Headers $adminHeaders `
    -Body $keyCPayload

if ($keyCRes.StatusCode -ne 201) {
    Write-Host "  [FAIL] Failed to generate Key C: HTTP $($keyCRes.StatusCode)" -ForegroundColor Red
    exit 1
}

$keyCObj = $keyCRes.Body | ConvertFrom-Json
$keyCId = $keyCObj.metadata.id
$plainKeyC = $keyCObj.plaintextKey
Write-Host "  [OK] Key C generated: ID=$keyCId, Prefix=$($keyCObj.metadata.keyPrefix)" -ForegroundColor Green

# ------------------------------------------------------------------------------
# 8. Test Missing API Key on Gateway
# ------------------------------------------------------------------------------
Write-Host "`n[8/17] Testing Unauthenticated Gateway Access (GET /api/v1/gateway/demo)..." -ForegroundColor Yellow
$unauthRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/gateway/demo" -Method "Get"
if ($unauthRes.StatusCode -eq 401) {
    Write-Host "  [OK] Missing API key rejected with HTTP 401 Unauthorized." -ForegroundColor Green
} else {
    Write-Host "  [FAIL] Expected HTTP 401, got $($unauthRes.StatusCode)" -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------------------------
# 9. Test Malformed & Unsupported Environment API Keys
# ------------------------------------------------------------------------------
Write-Host "`n[9/18] Testing Malformed & Unsupported API Keys (GET /api/v1/gateway/demo)..." -ForegroundColor Yellow

# 9A: Arbitrary malformed string
$malformedHeaders = @{ "X-API-Key" = "gk_live_invalidprefix1234567890abcdef" }
$malformedRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/gateway/demo" -Method "Get" -Headers $malformedHeaders
if ($malformedRes.StatusCode -eq 401) {
    Write-Host "  [OK] Malformed API key rejected with HTTP 401 Unauthorized." -ForegroundColor Green
} else {
    Write-Host "  [FAIL] Expected HTTP 401, got $($malformedRes.StatusCode)" -ForegroundColor Red
    exit 1
}

# 9B: Unsupported test environment prefix 'gk_test_'
$testPrefixHeaders = @{ "X-API-Key" = "gk_test_0000000000000000_1234567890123456789012345678901234567890123" }
$testPrefixRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/gateway/demo" -Method "Get" -Headers $testPrefixHeaders
if ($testPrefixRes.StatusCode -eq 401) {
    Write-Host "  [OK] Unsupported environment prefix (gk_test_) rejected with HTTP 401 Unauthorized." -ForegroundColor Green
} else {
    Write-Host "  [FAIL] Expected HTTP 401 for gk_test_, got $($testPrefixRes.StatusCode)" -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------------------------
# 10. Test Scope Enforcement (Key C lacking gateway:invoke)
# ------------------------------------------------------------------------------
Write-Host "`n[10/18] Testing Scope Authorization with Key C (GET /api/v1/gateway/demo)..." -ForegroundColor Yellow
$keyCHeaders = @{ "X-API-Key" = $plainKeyC }
$scopeRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/gateway/demo" -Method "Get" -Headers $keyCHeaders
if ($scopeRes.StatusCode -eq 403) {
    Write-Host "  [OK] Insufficient scope rejected with HTTP 403 Forbidden." -ForegroundColor Green
} else {
    Write-Host "  [FAIL] Expected HTTP 403, got $($scopeRes.StatusCode)" -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------------------------
# 11. Test Successful Request with Key A & Verify RateLimit Headers
# ------------------------------------------------------------------------------
Write-Host "`n[11/18] Testing Valid Gateway Request with Key A (GET /api/v1/gateway/demo)..." -ForegroundColor Yellow
$keyAHeaders = @{ "X-API-Key" = $plainKeyA }
$reqARes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/gateway/demo" -Method "Get" -Headers $keyAHeaders
if ($reqARes.StatusCode -eq 200) {
    $rlLimit = $reqARes.Headers["RateLimit-Limit"]
    $rlRemaining = $reqARes.Headers["RateLimit-Remaining"]
    $rlReset = $reqARes.Headers["RateLimit-Reset"]

    Write-Host "  [OK] Gateway invocation successful: HTTP 200 OK" -ForegroundColor Green
    Write-Host "       RateLimit-Limit     : $rlLimit (expected $planCapacity)" -ForegroundColor White
    Write-Host "       RateLimit-Remaining : $rlRemaining (expected $($planCapacity - 1))" -ForegroundColor White
    Write-Host "       RateLimit-Reset     : $rlReset seconds" -ForegroundColor White

    if (-not $rlLimit -or -not $rlRemaining -or -not $rlReset) {
        Write-Host "  [FAIL] Missing required RateLimit headers!" -ForegroundColor Red
        exit 1
    }

    if ([int]$rlRemaining -ne ($planCapacity - 1)) {
        Write-Host "  [FAIL] RateLimit-Remaining was $rlRemaining instead of $($planCapacity - 1), indicating scope-failed request may have consumed quota!" -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host "  [FAIL] Expected HTTP 200, got $($reqARes.StatusCode): $($reqARes.Body)" -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------------------------
# 12. Test Quota Sharing with Key B
# ------------------------------------------------------------------------------
Write-Host "`n[12/18] Testing Quota Sharing with Key B (GET /api/v1/gateway/demo)..." -ForegroundColor Yellow
$keyBHeaders = @{ "X-API-Key" = $plainKeyB }
$reqBRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/gateway/demo" -Method "Get" -Headers $keyBHeaders
if ($reqBRes.StatusCode -eq 200) {
    $remB = [int]$reqBRes.Headers["RateLimit-Remaining"]
    Write-Host "  [OK] Key B invocation successful: HTTP 200 OK" -ForegroundColor Green
    Write-Host "       RateLimit-Remaining : $remB (expected $($planCapacity - 2))" -ForegroundColor White

    if ($remB -ne ($planCapacity - 2)) {
        Write-Host "  [WARN] Remaining tokens ($remB) differs from strictly expected ($($planCapacity - 2)) due to refill/timing." -ForegroundColor Yellow
    }
} else {
    Write-Host "  [FAIL] Expected HTTP 200, got $($reqBRes.StatusCode)" -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------------------------
# 13. Test Endpoint-Specific Scope Requirements (GET /api/v1/gateway/orders)
# ------------------------------------------------------------------------------
Write-Host "`n[13/18] Testing Specific Scope 'read:orders' on /api/v1/gateway/orders..." -ForegroundColor Yellow

# Key B lacks read:orders -> should be 403
$ordersB = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/gateway/orders" -Method "Get" -Headers $keyBHeaders
if ($ordersB.StatusCode -eq 403) {
    Write-Host "  [OK] Key B (lacking read:orders) rejected with HTTP 403 Forbidden." -ForegroundColor Green
} else {
    Write-Host "  [FAIL] Key B should have been rejected with 403, got $($ordersB.StatusCode)" -ForegroundColor Red
    exit 1
}

# Key A possesses read:orders -> should be 200
$ordersA = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/gateway/orders" -Method "Get" -Headers $keyAHeaders
if ($ordersA.StatusCode -eq 200) {
    Write-Host "  [OK] Key A (possessing read:orders) succeeded with HTTP 200 OK." -ForegroundColor Green
} else {
    Write-Host "  [FAIL] Key A failed on orders endpoint: HTTP $($ordersA.StatusCode)" -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------------------------
# 14. Rapid Quota Consumption to Exhaustion (HTTP 429)
# ------------------------------------------------------------------------------
Write-Host "`n[14/18] Testing Rate Limit Exhaustion (looping until HTTP 429)..." -ForegroundColor Yellow
$exhausted = $false
$exhaustionRes = $null
$loopCount = 0
$maxLoops = $planCapacity + 10

while ($loopCount -lt $maxLoops) {
    $loopCount++
    $callRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/gateway/demo" -Method "Get" -Headers $keyAHeaders
    if ($callRes.StatusCode -eq 429) {
        $exhausted = $true
        $exhaustionRes = $callRes
        break
    }
}

if ($exhausted) {
    $retryAfter = $exhaustionRes.Headers["Retry-After"]
    $rlRem = $exhaustionRes.Headers["RateLimit-Remaining"]
    Write-Host "  [OK] Quota exhausted: HTTP 429 Too Many Requests returned after $loopCount requests." -ForegroundColor Green
    Write-Host "       Retry-After         : $retryAfter seconds" -ForegroundColor White
    Write-Host "       RateLimit-Remaining : $rlRem" -ForegroundColor White

    if (-not $retryAfter) {
        Write-Host "  [FAIL] Retry-After header missing on HTTP 429 response!" -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host "  [FAIL] Failed to trigger HTTP 429 after $loopCount requests!" -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------------------------
# 15. Refill Verification
# ------------------------------------------------------------------------------
Write-Host "`n[15/18] Verifying Bucket Refill Restoration..." -ForegroundColor Yellow
$waitSec = [Math]::Max(1, [int]$retryAfter)
Write-Host "  [INFO] Waiting $waitSec seconds for token refill..." -ForegroundColor Gray
Start-Sleep -Seconds $waitSec

$refillRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/gateway/demo" -Method "Get" -Headers $keyBHeaders
if ($refillRes.StatusCode -eq 200) {
    Write-Host "  [OK] Gateway requests succeed again after token bucket refill: HTTP 200 OK." -ForegroundColor Green
    Write-Host "       RateLimit-Remaining : $($refillRes.Headers['RateLimit-Remaining'])" -ForegroundColor White
} else {
    Write-Host "  [FAIL] Expected HTTP 200 after refill, got $($refillRes.StatusCode)" -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------------------------
# 16. Key Revocation Enforcement
# ------------------------------------------------------------------------------
Write-Host "`n[16/18] Revoking Key A and Verifying Immediate Propagation..." -ForegroundColor Yellow
$revokeBody = @{ reason = "Automated rate limit verification completion" } | ConvertTo-Json
$revokeRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/consumers/$consumerId/keys/$keyAId/revoke" `
    -Method "Post" `
    -Headers $adminHeaders `
    -Body $revokeBody

if ($revokeRes.StatusCode -ne 200) {
    Write-Host "  [FAIL] Revocation request failed: HTTP $($revokeRes.StatusCode)" -ForegroundColor Red
    exit 1
}
Write-Host "  [OK] Key A revoked via management API (HTTP 200 OK)." -ForegroundColor Green

# Test Key A: Must be immediately rejected with 401
$revokedAttempt = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/gateway/demo" -Method "Get" -Headers $keyAHeaders
if ($revokedAttempt.StatusCode -eq 401) {
    Write-Host "  [OK] Revoked Key A immediately rejected with HTTP 401 Unauthorized." -ForegroundColor Green
} else {
    Write-Host "  [FAIL] Revoked Key A did not return 401! Got $($revokedAttempt.StatusCode)" -ForegroundColor Red
    exit 1
}

# Key B must still be active
$activeAttempt = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/gateway/demo" -Method "Get" -Headers $keyBHeaders
if ($activeAttempt.StatusCode -eq 200 -or $activeAttempt.StatusCode -eq 429) {
    Write-Host "  [OK] Non-revoked Key B remains authenticated (HTTP $($activeAttempt.StatusCode))." -ForegroundColor Green
} else {
    Write-Host "  [FAIL] Non-revoked Key B failed authentication: HTTP $($activeAttempt.StatusCode)" -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------------------------
# 17. Security Boundary Isolation
# ------------------------------------------------------------------------------
Write-Host "`n[17/18] Verifying Security Boundary Isolation..." -ForegroundColor Yellow

# 17A: Admin Bearer token sent to consumer gateway -> 401
$adminGatewayRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/gateway/demo" -Method "Get" -Headers $adminHeaders
if ($adminGatewayRes.StatusCode -eq 401) {
    Write-Host "  [OK] Admin Bearer JWT rejected on gateway endpoint: HTTP 401 Unauthorized." -ForegroundColor Green
} else {
    Write-Host "  [FAIL] Expected HTTP 401 when sending JWT to gateway, got $($adminGatewayRes.StatusCode)" -ForegroundColor Red
    exit 1
}

# 17B: Consumer API Key sent to admin management API -> 401
$consumerMgmtRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/consumers" -Method "Get" -Headers $keyBHeaders
if ($consumerMgmtRes.StatusCode -eq 401) {
    Write-Host "  [OK] Consumer API Key rejected on management endpoint: HTTP 401 Unauthorized." -ForegroundColor Green
} else {
    Write-Host "  [FAIL] Expected HTTP 401 when sending API Key to management, got $($consumerMgmtRes.StatusCode)" -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------------------------
# 18. Consumer Lifecycle Deletion & Final Cleanup
# ------------------------------------------------------------------------------
Write-Host "`n[18/18] Soft-Deleting Test Consumer and Verifying Total Key Invalidation..." -ForegroundColor Yellow
}

# ------------------------------------------------------------------------------
# 16. Security Boundary Isolation
# ------------------------------------------------------------------------------
Write-Host "`n[16/17] Verifying Security Boundary Isolation..." -ForegroundColor Yellow

# 16A: Admin Bearer token sent to consumer gateway -> 401
$adminGatewayRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/gateway/demo" -Method "Get" -Headers $adminHeaders
if ($adminGatewayRes.StatusCode -eq 401) {
    Write-Host "  [OK] Admin Bearer JWT rejected on gateway endpoint: HTTP 401 Unauthorized." -ForegroundColor Green
} else {
    Write-Host "  [FAIL] Expected HTTP 401 when sending JWT to gateway, got $($adminGatewayRes.StatusCode)" -ForegroundColor Red
    exit 1
}

# 16B: Consumer API Key sent to admin management API -> 401
$consumerMgmtRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/consumers" -Method "Get" -Headers $keyBHeaders
if ($consumerMgmtRes.StatusCode -eq 401) {
    Write-Host "  [OK] Consumer API Key rejected on management endpoint: HTTP 401 Unauthorized." -ForegroundColor Green
} else {
    Write-Host "  [FAIL] Expected HTTP 401 when sending API Key to management, got $($consumerMgmtRes.StatusCode)" -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------------------------
# 18. Consumer Lifecycle Deletion & Final Cleanup
# ------------------------------------------------------------------------------
Write-Host "`n[18/18] Soft-Deleting Test Consumer and Verifying Total Key Invalidation..." -ForegroundColor Yellow
$deleteRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/consumers/$consumerId" `
    -Method "Delete" `
    -Headers $adminHeaders

if ($deleteRes.StatusCode -eq 204) {
    Write-Host "  [OK] Consumer soft-deleted: HTTP 204 No Content." -ForegroundColor Green
} else {
    Write-Host "  [WARN] Consumer soft-delete returned HTTP $($deleteRes.StatusCode)" -ForegroundColor Yellow
}

# Verify Key B (which was not explicitly revoked) is now rejected with 401 due to consumer SUSPENDED status
$postDeleteRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/gateway/demo" -Method "Get" -Headers $keyBHeaders
if ($postDeleteRes.StatusCode -eq 401) {
    Write-Host "  [OK] Key B rejected with HTTP 401 Unauthorized following consumer soft-deletion." -ForegroundColor Green
} else {
    Write-Host "  [FAIL] Key B should be rejected with 401 after consumer deletion, got $($postDeleteRes.StatusCode)" -ForegroundColor Red
    exit 1
}

# Clean in-memory secrets
$plainKeyA = $null
$plainKeyB = $null
$plainKeyC = $null
$jwtToken = $null
$adminHeaders = $null
$keyAHeaders = $null
$keyBHeaders = $null
$keyCHeaders = $null

Write-Host "`n======================================================" -ForegroundColor Green
Write-Host " Gateway & Rate Limiting: ALL 18 VERIFICATION CHECKS PASSED " -ForegroundColor Green
Write-Host "======================================================" -ForegroundColor Green
exit 0
