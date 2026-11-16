param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Username = "admin"
)

$ErrorActionPreference = "Stop"

Write-Host "======================================================" -ForegroundColor Cyan
Write-Host " Gatekeeper Usage Analytics & Telemetry Verification  " -ForegroundColor Green
Write-Host "======================================================" -ForegroundColor Cyan

function Invoke-SafeRestCall {
    param(
        [string]$Uri,
        [string]$Method = "Get",
        [hashtable]$Headers = @{},
        [string]$Body = $null,
        [string]$ContentType = "application/json"
    )

    $request = [System.Net.HttpWebRequest]::Create($Uri)
    $request.Method = $Method.ToUpperInvariant()
    $request.KeepAlive = $false
    $request.Timeout = 15000

    foreach ($k in $Headers.Keys) {
        $request.Headers.Add($k, [string]$Headers[$k])
    }

    if ($Body -and ($request.Method -eq "POST" -or $request.Method -eq "PUT" -or $request.Method -eq "PATCH")) {
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
# 1. Health Check
# ------------------------------------------------------------------------------
Write-Host "`n[1/18] Performing Gateway Service Health Check..." -ForegroundColor Yellow
$healthRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/health" -Method "Get"
if ($healthRes.StatusCode -ne 200) {
    Write-Host "  [FAIL] Health check failed with status $($healthRes.StatusCode)" -ForegroundColor Red
    exit 1
}
Write-Host "  [OK] Gatekeeper backend is healthy (HTTP 200)." -ForegroundColor Green

# ------------------------------------------------------------------------------
# 2. Operator Authentication
# ------------------------------------------------------------------------------
Write-Host "`n[2/18] Authenticating Administrator ($Username)..." -ForegroundColor Yellow
$password = $null
if ($env:GATEKEEPER_ADMIN_PASSWORD) {
    $password = $env:GATEKEEPER_ADMIN_PASSWORD
} else {
    $secPass = Read-Host -Prompt "Enter password for '$Username'" -AsSecureString
    $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secPass)
    $password = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
    [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
}

if (-not $password) {
    Write-Host "  [FAIL] Password cannot be blank." -ForegroundColor Red
    exit 1
}

$loginBody = @{
    username = $Username
    password = $password
} | ConvertTo-Json -Compress

$loginRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/auth/login" -Method "Post" -Body $loginBody
$password = $null
$loginBody = $null

if ($loginRes.StatusCode -ne 200) {
    Write-Host "  [FAIL] Login failed: HTTP $($loginRes.StatusCode)" -ForegroundColor Red
    exit 1
}

$loginJson = $loginRes.Body | ConvertFrom-Json
$jwtToken = $loginJson.accessToken
if (-not $jwtToken) {
    Write-Host "  [FAIL] No access token received in login response." -ForegroundColor Red
    exit 1
}

$adminHeaders = @{
    "Authorization" = "Bearer $jwtToken"
}
Write-Host "  [OK] Administrator authenticated. JWT acquired." -ForegroundColor Green

# ------------------------------------------------------------------------------
# 3. Retrieve Subscription Plans
# ------------------------------------------------------------------------------
Write-Host "`n[3/18] Retrieving Active Subscription Plans..." -ForegroundColor Yellow
$plansRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/plans" -Method "Get" -Headers $adminHeaders
if ($plansRes.StatusCode -ne 200) {
    Write-Host "  [FAIL] Failed to retrieve plans: HTTP $($plansRes.StatusCode)" -ForegroundColor Red
    exit 1
}

$plans = $plansRes.Body | ConvertFrom-Json
$freePlan = $plans | Where-Object { $_.name -eq "FREE" } | Select-Object -First 1
if (-not $freePlan) {
    $freePlan = $plans[0]
}
$planId = $freePlan.id
Write-Host "  [OK] Using Plan '$($freePlan.name)' (ID: $planId)" -ForegroundColor Green

# ------------------------------------------------------------------------------
# 4. Create Dedicated Test Consumer
# ------------------------------------------------------------------------------
$uniqueSuffix = [System.Guid]::NewGuid().ToString().Substring(0, 8)
$consumerName = "Analytics-Verify-$uniqueSuffix"
$consumerEmail = "verify-$uniqueSuffix@example.com"

Write-Host "`n[4/18] Creating Dedicated Test Consumer ($consumerName)..." -ForegroundColor Yellow
$createConsumerBody = @{
    name   = $consumerName
    email  = $consumerEmail
    planId = $planId
} | ConvertTo-Json -Compress

$consumerRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/consumers" -Method "Post" -Headers $adminHeaders -Body $createConsumerBody
if ($consumerRes.StatusCode -ne 201) {
    Write-Host "  [FAIL] Consumer creation failed: HTTP $($consumerRes.StatusCode): $($consumerRes.Body)" -ForegroundColor Red
    exit 1
}

$consumerJson = $consumerRes.Body | ConvertFrom-Json
$consumerId = $consumerJson.id
Write-Host "  [OK] Consumer created: ID=$consumerId" -ForegroundColor Green

# ------------------------------------------------------------------------------
# 5. Create Dedicated API Key (Scope: gateway:invoke)
# ------------------------------------------------------------------------------
Write-Host "`n[5/18] Generating Test Consumer API Key (scope: gateway:invoke)..." -ForegroundColor Yellow
$createKeyBody = @{
    name   = "Verify-Analytics-Key-$uniqueSuffix"
    scopes = @("gateway:invoke")
} | ConvertTo-Json -Compress

$keyRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/consumers/$consumerId/keys" -Method "Post" -Headers $adminHeaders -Body $createKeyBody
if ($keyRes.StatusCode -ne 201) {
    Write-Host "  [FAIL] Key creation failed: HTTP $($keyRes.StatusCode)" -ForegroundColor Red
    exit 1
}

$keyJson = $keyRes.Body | ConvertFrom-Json
$keyId = $keyJson.key.id
$plainKey = $keyJson.plaintextKey
$keyPrefix = $keyJson.key.keyPrefix

$keyHeaders = @{
    "X-API-Key" = $plainKey
}
Write-Host "  [OK] API key generated: prefix=$keyPrefix, ID=$keyId" -ForegroundColor Green

# ------------------------------------------------------------------------------
# 6. Verify Initial lastUsedAt is NULL
# ------------------------------------------------------------------------------
Write-Host "`n[6/18] Verifying Initial lastUsedAt is Null..." -ForegroundColor Yellow
$keysListRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/consumers/$consumerId/keys" -Method "Get" -Headers $adminHeaders
if ($keysListRes.StatusCode -ne 200) {
    Write-Host "  [FAIL] Failed to list keys: HTTP $($keysListRes.StatusCode)" -ForegroundColor Red
    exit 1
}
$keysListJson = $keysListRes.Body | ConvertFrom-Json
$targetKey = $keysListJson | Where-Object { $_.id -eq $keyId }
if ($targetKey.lastUsedAt) {
    Write-Host "  [FAIL] Expected lastUsedAt to be null before traffic, found: $($targetKey.lastUsedAt)" -ForegroundColor Red
    exit 1
}
Write-Host "  [OK] Initial lastUsedAt is confirmed NULL." -ForegroundColor Green

# ------------------------------------------------------------------------------
# 7. Generate Allowed Gateway Traffic (HTTP 200)
# ------------------------------------------------------------------------------
Write-Host "`n[7/18] Sending Allowed Request to /api/v1/gateway/demo..." -ForegroundColor Yellow
$customCorrId = "audit-corr-$uniqueSuffix"
$reqHeaders = @{
    "X-API-Key"    = $plainKey
    "X-Request-Id" = $customCorrId
}

$demoRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/gateway/demo" -Method "Get" -Headers $reqHeaders
if ($demoRes.StatusCode -ne 200) {
    Write-Host "  [FAIL] Expected HTTP 200 on demo endpoint, got $($demoRes.StatusCode): $($demoRes.Body)" -ForegroundColor Red
    exit 1
}

$returnedCorrId = $demoRes.Headers["X-Request-Id"]
if ($returnedCorrId -ne $customCorrId) {
    Write-Host "  [WARN] Expected correlation ID '$customCorrId', got '$returnedCorrId'" -ForegroundColor Yellow
}
Write-Host "  [OK] Gateway demo request succeeded (HTTP 200) with correlation ID '$returnedCorrId'." -ForegroundColor Green

# ------------------------------------------------------------------------------
# 8. Generate Scope-Denied Gateway Traffic (HTTP 403)
# ------------------------------------------------------------------------------
Write-Host "`n[8/18] Sending Request Missing Scope to /api/v1/gateway/orders..." -ForegroundColor Yellow
$ordersRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/gateway/orders" -Method "Get" -Headers $keyHeaders
if ($ordersRes.StatusCode -ne 403) {
    Write-Host "  [FAIL] Expected HTTP 403 for missing 'read:orders' scope, got $($ordersRes.StatusCode)" -ForegroundColor Red
    exit 1
}
Write-Host "  [OK] Scope check enforced: HTTP 403 Forbidden captured." -ForegroundColor Green

# ------------------------------------------------------------------------------
# 9. Trigger Rate Limit Quota Exhaustion (HTTP 429)
# ------------------------------------------------------------------------------
Write-Host "`n[9/18] Bursting Gateway Requests to Trigger Quota Exhaustion (HTTP 429)..." -ForegroundColor Yellow
$rateLimitedHit = $false
for ($i = 1; $i -le 70; $i++) {
    $burstRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/gateway/demo" -Method "Get" -Headers $keyHeaders
    if ($burstRes.StatusCode -eq 429) {
        $rateLimitedHit = $true
        Write-Host "  [OK] Quota exhausted on request #$($i): HTTP 429 Too Many Requests (Retry-After: $($burstRes.Headers['Retry-After'])s)" -ForegroundColor Green
        break
    }
}

if (-not $rateLimitedHit) {
    Write-Host "  [FAIL] Failed to trigger HTTP 429 after 70 burst requests." -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------------------------
# 10. Bounded Polling for Asynchronous Batch Persistence
# ------------------------------------------------------------------------------
Write-Host "`n[10/18] Awaiting Asynchronous Stream Batch Processing in PostgreSQL..." -ForegroundColor Yellow
$persisted = $false
$maxPollSeconds = 12
$startTime = [System.DateTime]::UtcNow

while (([System.DateTime]::UtcNow - $startTime).TotalSeconds -lt $maxPollSeconds) {
    Start-Sleep -Milliseconds 1500
    $checkRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/analytics/consumers/$consumerId" -Method "Get" -Headers $adminHeaders
    if ($checkRes.StatusCode -eq 200) {
        $checkJson = $checkRes.Body | ConvertFrom-Json
        if ($checkJson.totalRequests -ge 3) {
            $persisted = $true
            Write-Host "  [OK] Telemetry batch persisted to database: $($checkJson.totalRequests) events recorded." -ForegroundColor Green
            break
        }
    }
}

if (-not $persisted) {
    Write-Host "  [FAIL] Telemetry events were not persisted within $maxPollSeconds seconds." -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------------------------
# 11. Verify Overview Analytics
# ------------------------------------------------------------------------------
Write-Host "`n[11/18] Verifying GET /api/v1/analytics/overview..." -ForegroundColor Yellow
$overviewRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/analytics/overview" -Method "Get" -Headers $adminHeaders
if ($overviewRes.StatusCode -ne 200) {
    Write-Host "  [FAIL] Overview endpoint failed: HTTP $($overviewRes.StatusCode)" -ForegroundColor Red
    exit 1
}

$overviewJson = $overviewRes.Body | ConvertFrom-Json
Write-Host "  Overview Metrics:" -ForegroundColor Cyan
Write-Host "    Total Requests       : $($overviewJson.totalRequests)"
Write-Host "    Allowed Requests     : $($overviewJson.allowedRequests)"
Write-Host "    Rate Limited Requests: $($overviewJson.rateLimitedRequests)"
Write-Host "    Scope Denied Requests: $($overviewJson.scopeDeniedRequests)"
Write-Host "    Denial Percentage    : $($overviewJson.rateLimitDenialPercentage)%"

if ($overviewJson.totalRequests -le 0 -or $overviewJson.rateLimitedRequests -le 0) {
    Write-Host "  [FAIL] Overview metrics did not capture rate-limited requests." -ForegroundColor Red
    exit 1
}
Write-Host "  [OK] Overview analytics verified." -ForegroundColor Green

# ------------------------------------------------------------------------------
# 12. Verify Time-Series Analytics
# ------------------------------------------------------------------------------
Write-Host "`n[12/18] Verifying GET /api/v1/analytics/timeseries..." -ForegroundColor Yellow
$tsRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/analytics/timeseries?granularity=hourly" -Method "Get" -Headers $adminHeaders
if ($tsRes.StatusCode -ne 200) {
    Write-Host "  [FAIL] Time-series endpoint failed: HTTP $($tsRes.StatusCode)" -ForegroundColor Red
    exit 1
}
$tsJson = $tsRes.Body | ConvertFrom-Json
if ($tsJson.Count -le 0) {
    Write-Host "  [FAIL] Time-series returned empty dataset." -ForegroundColor Red
    exit 1
}
Write-Host "  [OK] Time-series returned $($tsJson.Count) aggregation bucket(s)." -ForegroundColor Green

# ------------------------------------------------------------------------------
# 13. Verify Consumer Analytics
# ------------------------------------------------------------------------------
Write-Host "`n[13/18] Verifying GET /api/v1/analytics/consumers/{id}..." -ForegroundColor Yellow
$cStatsRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/analytics/consumers/$consumerId" -Method "Get" -Headers $adminHeaders
if ($cStatsRes.StatusCode -ne 200) {
    Write-Host "  [FAIL] Consumer analytics failed: HTTP $($cStatsRes.StatusCode)" -ForegroundColor Red
    exit 1
}

$cStatsJson = $cStatsRes.Body | ConvertFrom-Json
Write-Host "  Consumer Stats ($consumerName):" -ForegroundColor Cyan
Write-Host "    Total Requests       : $($cStatsJson.totalRequests)"
Write-Host "    Allowed Requests     : $($cStatsJson.allowedRequests)"
Write-Host "    Rate-Limited Requests: $($cStatsJson.rateLimitedRequests)"
Write-Host "    Scope-Denied Requests: $($cStatsJson.scopeDeniedRequests)"

if ($cStatsJson.rateLimitedRequests -le 0 -or $cStatsJson.scopeDeniedRequests -le 0) {
    Write-Host "  [FAIL] Consumer metrics missing expected rate-limited or scope-denied outcomes." -ForegroundColor Red
    exit 1
}
Write-Host "  [OK] Consumer analytics verified." -ForegroundColor Green

# ------------------------------------------------------------------------------
# 14. Verify Paginated Consumer Usage Events Audit
# ------------------------------------------------------------------------------
Write-Host "`n[14/18] Verifying GET /api/v1/analytics/consumers/{id}/usage..." -ForegroundColor Yellow
$usageRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/analytics/consumers/$consumerId/usage?page=0&size=5" -Method "Get" -Headers $adminHeaders
if ($usageRes.StatusCode -ne 200) {
    Write-Host "  [FAIL] Usage audit log endpoint failed: HTTP $($usageRes.StatusCode)" -ForegroundColor Red
    exit 1
}
$usageJson = $usageRes.Body | ConvertFrom-Json
Write-Host "  [OK] Usage audit log returned paginated events (total elements: $($usageJson.page.totalElements))." -ForegroundColor Green

# ------------------------------------------------------------------------------
# 15. Verify Top Consumers
# ------------------------------------------------------------------------------
Write-Host "`n[15/18] Verifying GET /api/v1/analytics/top-consumers..." -ForegroundColor Yellow
$topRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/analytics/top-consumers?limit=5" -Method "Get" -Headers $adminHeaders
if ($topRes.StatusCode -ne 200) {
    Write-Host "  [FAIL] Top consumers endpoint failed: HTTP $($topRes.StatusCode)" -ForegroundColor Red
    exit 1
}
$topJson = $topRes.Body | ConvertFrom-Json
$foundSelf = $topJson | Where-Object { $_.consumerId -eq $consumerId }
if (-not $foundSelf) {
    Write-Host "  [WARN] Test consumer not found in top 5 list (might be superseded by higher volume test consumers)." -ForegroundColor Yellow
} else {
    Write-Host "  [OK] Test consumer present in top consumers ranking (requests: $($foundSelf.totalRequests))." -ForegroundColor Green
}

# ------------------------------------------------------------------------------
# 16. Verify Eventual Monotonic lastUsedAt Persistence
# ------------------------------------------------------------------------------
Write-Host "`n[16/18] Verifying Eventual Monotonic lastUsedAt Persistence..." -ForegroundColor Yellow
$updatedKeysRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/consumers/$consumerId/keys" -Method "Get" -Headers $adminHeaders
$updatedKeysJson = $updatedKeysRes.Body | ConvertFrom-Json
$updatedTargetKey = $updatedKeysJson | Where-Object { $_.id -eq $keyId }

if (-not $updatedTargetKey.lastUsedAt) {
    Write-Host "  [FAIL] lastUsedAt was not asynchronously updated on API key." -ForegroundColor Red
    exit 1
}
Write-Host "  [OK] API key lastUsedAt successfully populated: $($updatedTargetKey.lastUsedAt)" -ForegroundColor Green

# ------------------------------------------------------------------------------
# 17. Verify Security Isolation & RBAC
# ------------------------------------------------------------------------------
Write-Host "`n[17/18] Verifying Analytics RBAC and Credential Isolation..." -ForegroundColor Yellow
# Unauthenticated
$anonRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/analytics/overview" -Method "Get"
if ($anonRes.StatusCode -ne 401) {
    Write-Host "  [FAIL] Anonymous access to analytics should return 401, got $($anonRes.StatusCode)" -ForegroundColor Red
    exit 1
}
Write-Host "  [OK] Anonymous request rejected with HTTP 401." -ForegroundColor Green

# Consumer API Key must NOT access analytics
$keyAnalyticsRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/analytics/overview" -Method "Get" -Headers $keyHeaders
if ($keyAnalyticsRes.StatusCode -ne 401) {
    Write-Host "  [FAIL] Consumer API key access to analytics should return 401, got $($keyAnalyticsRes.StatusCode)" -ForegroundColor Red
    exit 1
}
Write-Host "  [OK] Consumer API key rejected on management analytics endpoint with HTTP 401." -ForegroundColor Green

# ------------------------------------------------------------------------------
# 18. Cleanup & Soft Deletion
# ------------------------------------------------------------------------------
Write-Host "`n[18/18] Cleaning Up Test Consumer ($consumerId)..." -ForegroundColor Yellow
$deleteRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/consumers/$consumerId" -Method "Delete" -Headers $adminHeaders
if ($deleteRes.StatusCode -eq 204) {
    Write-Host "  [OK] Test consumer soft-deleted: HTTP 204 No Content." -ForegroundColor Green
} else {
    Write-Host "  [WARN] Soft-delete returned HTTP $($deleteRes.StatusCode)" -ForegroundColor Yellow
}

# Clean in-memory credentials
$jwtToken = $null
$plainKey = $null
$adminHeaders = $null
$keyHeaders = $null

Write-Host "`n======================================================" -ForegroundColor Green
Write-Host " Usage Analytics: ALL 18 VERIFICATION CHECKS PASSED  " -ForegroundColor Green
Write-Host "======================================================" -ForegroundColor Green
exit 0
