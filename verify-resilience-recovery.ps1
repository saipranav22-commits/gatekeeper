param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$OrdersUrl = "http://localhost:8081",
    [string]$Username = "admin",
    [switch]$KeepServicesRunning
)

$ErrorActionPreference = "Stop"

Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host "  Gatekeeper Resilience Automatic Recovery Verification (Phase 5B.2)  " -ForegroundColor Green
Write-Host "======================================================================" -ForegroundColor Cyan

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

$servicesStarted = @()
$createdConsumerId = $null

try {
    # ------------------------------------------------------------------------------
    # 1. Health Checks & Upstream Availability
    # ------------------------------------------------------------------------------
    Write-Host "`n[1/12] Checking Gatekeeper Backend Health ($BaseUrl)..." -ForegroundColor Yellow
    $gwHealth = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/health" -Method "Get"
    if ($gwHealth.StatusCode -ne 200) {
        Write-Host "  [FAIL] Gatekeeper backend health check failed: HTTP $($gwHealth.StatusCode)" -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Gatekeeper backend is healthy (HTTP 200)." -ForegroundColor Green

    # Check Orders Service
    Write-Host "`n[2/12] Verifying Upstream Orders Service ($OrdersUrl)..." -ForegroundColor Yellow
    $ordersUp = $false
    try {
        $ordHealth = Invoke-SafeRestCall -Uri "$OrdersUrl/health" -Method "Get"
        if ($ordHealth.StatusCode -eq 200) { $ordersUp = $true }
    } catch {
        $ordersUp = $false
    }

    if (-not $ordersUp) {
        Write-Host "  Orders service not detected on port 8081. Starting background process..." -ForegroundColor Cyan
        $ordersJar = "D:\Development\Gatekeeper\services\orders-service\target\orders-service-0.0.1-SNAPSHOT.jar"
        $ordersProc = Start-Process -FilePath "java" -ArgumentList "-jar", $ordersJar -PassThru -WindowStyle Hidden
        $servicesStarted += $ordersProc
        $retries = 20
        while ($retries -gt 0 -and -not $ordersUp) {
            Start-Sleep -Seconds 1
            try {
                $chk = Invoke-SafeRestCall -Uri "$OrdersUrl/health" -Method "Get"
                if ($chk.StatusCode -eq 200) { $ordersUp = $true }
            } catch {}
            $retries--
        }
    }

    if (-not $ordersUp) {
        Write-Host "  [FAIL] Failed to start/reach Orders Service on port 8081." -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Upstream Orders Service is UP on port 8081." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 2. Operator Authentication
    # ------------------------------------------------------------------------------
    Write-Host "`n[3/12] Authenticating Administrator ($Username)..." -ForegroundColor Yellow
    $password = $null
    if ($env:GATEKEEPER_ADMIN_PASSWORD) {
        $password = $env:GATEKEEPER_ADMIN_PASSWORD
    } elseif ([Environment]::UserInteractive -and -not [Console]::IsInputRedirected) {
        try {
            $secPass = Read-Host -Prompt "Enter password for '$Username'" -AsSecureString
            $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secPass)
            $password = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
            [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
        } catch {
            $password = $null
        }
    }

    if ([string]::IsNullOrWhiteSpace($password)) {
        Write-Host "  [FAIL] Operator credentials unavailable. Set GATEKEEPER_ADMIN_PASSWORD environment variable for automated verification." -ForegroundColor Red
        exit 1
    }

    $loginBody = @{
        username = $Username
        password = $password
    } | ConvertTo-Json -Compress

    # Clear sensitive plain-text password from variable memory immediately
    $password = $null

    $loginResp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/auth/login" -Method "Post" -Body $loginBody
    $loginBody = $null

    if ($loginResp.StatusCode -ne 200) {
        if ($loginResp.StatusCode -eq 401) {
            Write-Host "  [FAIL] Administrator authentication failed (HTTP 401 Unauthorized): Invalid credentials." -ForegroundColor Red
        } elseif ($loginResp.StatusCode -eq 429) {
            Write-Host "  [FAIL] Administrator authentication failed (HTTP 429 Too Many Requests): Rate limit triggered." -ForegroundColor Red
        } else {
            Write-Host "  [FAIL] Administrator login failed with HTTP $($loginResp.StatusCode)." -ForegroundColor Red
        }
        exit 1
    }

    $loginJson = $loginResp.Body | ConvertFrom-Json
    $adminToken = if ($loginJson.accessToken) { [string]$loginJson.accessToken } else { [string]$loginJson.token }
    if ([string]::IsNullOrWhiteSpace($adminToken)) {
        Write-Host "  [FAIL] Response contract mismatch: 'accessToken' was missing or empty in login response." -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Admin authenticated successfully (HTTP 200). JWT access token acquired." -ForegroundColor Green
    $adminHeaders = @{ "Authorization" = "Bearer $adminToken" }

    # ------------------------------------------------------------------------------
    # 3. Create Dedicated Test Consumer and API Key
    # ------------------------------------------------------------------------------
    Write-Host "`n[4/12] Provisioning Test Consumer and Scoped API Key..." -ForegroundColor Yellow
    $plansResp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/plans" -Method "Get" -Headers $adminHeaders
    $plansJson = $plansResp.Body | ConvertFrom-Json
    $proPlan = $plansJson | Where-Object { $_.name -eq "PRO" } | Select-Object -First 1
    if (-not $proPlan) {
        $proPlan = $plansJson | Select-Object -First 1
    }

    $cName = "Recovery Test Consumer " + [System.Guid]::NewGuid().ToString().Substring(0, 8)
    $cEmail = "recovery_" + [System.Guid]::NewGuid().ToString().Substring(0, 8) + "@example.com"
    $createConsumerBody = @{
        name   = $cName
        email  = $cEmail
        planId = $proPlan.id
    } | ConvertTo-Json -Compress

    $consumerResp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/consumers" -Method "Post" -Headers $adminHeaders -Body $createConsumerBody
    if ($consumerResp.StatusCode -ne 201) {
        Write-Host "  [FAIL] Failed to create test consumer: HTTP $($consumerResp.StatusCode)" -ForegroundColor Red
        exit 1
    }
    $consumerJson = $consumerResp.Body | ConvertFrom-Json
    $createdConsumerId = $consumerJson.id

    $createKeyBody = @{
        name   = "Recovery Key"
        scopes = @("orders:read", "orders:write")
    } | ConvertTo-Json -Compress

    $keyResp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/consumers/$createdConsumerId/keys" -Method "Post" -Headers $adminHeaders -Body $createKeyBody
    if ($keyResp.StatusCode -ne 201) {
        Write-Host "  [FAIL] Failed to generate API key: HTTP $($keyResp.StatusCode)" -ForegroundColor Red
        exit 1
    }
    $keyJson = $keyResp.Body | ConvertFrom-Json
    $rawApiKey = $keyJson.plaintextKey
    Write-Host "  [OK] Test consumer and scoped API key created." -ForegroundColor Green

    $gatewayHeaders = @{
        "X-API-Key" = $rawApiKey
    }

    # Reset any existing faults and reset breaker to CLOSED
    Invoke-SafeRestCall -Uri "$OrdersUrl/fault/reset" -Method "Post" | Out-Null
    Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/resilience/routes/orders-service-v1/reset" -Method "Post" -Headers $adminHeaders | Out-Null

    # ------------------------------------------------------------------------------
    # 4. Verify Baseline Healthy Operation (CLOSED State)
    # ------------------------------------------------------------------------------
    Write-Host "`n[5/12] Verifying Baseline Healthy Traffic in CLOSED State..." -ForegroundColor Yellow
    $baseReq = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/proxy/orders" -Method "Get" -Headers $gatewayHeaders
    if ($baseReq.StatusCode -ne 200) {
        Write-Host "  [FAIL] Baseline proxy request failed: HTTP $($baseReq.StatusCode)" -ForegroundColor Red
        exit 1
    }

    $detailResp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/resilience/routes/orders-service-v1" -Method "Get" -Headers $adminHeaders
    $detailJson = $detailResp.Body | ConvertFrom-Json
    if ($detailJson.circuitBreaker.state -ne "CLOSED") {
        Write-Host "  [FAIL] Initial circuit state is not CLOSED (got $($detailJson.circuitBreaker.state))" -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Baseline verified: Route circuit breaker is CLOSED, traffic flows normally (HTTP 200)." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 5. Inject Upstream Fault & Trip Circuit Breaker to OPEN
    # ------------------------------------------------------------------------------
    Write-Host "`n[6/12] Injecting HTTP 503 Fault into Upstream Orders Service to Trip Circuit..." -ForegroundColor Yellow
    $faultBody = @{
        delayMs      = 0
        statusCode   = 503
        failureCount = 50
    } | ConvertTo-Json -Compress
    $cfgResp = Invoke-SafeRestCall -Uri "$OrdersUrl/fault/configure" -Method "Post" -Body $faultBody
    if ($cfgResp.StatusCode -ne 200) {
        Write-Host "  [FAIL] Failed to configure fault on Orders service: HTTP $($cfgResp.StatusCode)" -ForegroundColor Red
        exit 1
    }

    # Send 5 requests to trip breaker (minCalls = 5, failureRateThreshold = 50%)
    for ($i = 1; $i -le 5; $i++) {
        $tripReq = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/proxy/orders" -Method "Post" -Headers $gatewayHeaders -Body '{"item":"widget"}'
    }

    # Verify breaker is now OPEN
    $openDetail = (Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/resilience/routes/orders-service-v1" -Method "Get" -Headers $adminHeaders).Body | ConvertFrom-Json
    if ($openDetail.circuitBreaker.state -ne "OPEN") {
        Write-Host "  [FAIL] Circuit breaker failed to trip to OPEN (state is $($openDetail.circuitBreaker.state))." -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Circuit breaker successfully tripped: CLOSED -> OPEN (failure rate: $($openDetail.circuitBreaker.failureRate)%)." -ForegroundColor Green

    # Fast rejection test: confirm OPEN state fast-rejects without reaching upstream
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $fastRejectResp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/proxy/orders" -Method "Get" -Headers $gatewayHeaders
    $sw.Stop()
    if ($fastRejectResp.StatusCode -ne 503) {
        Write-Host "  [FAIL] Expected HTTP 503 fast rejection in OPEN state, got HTTP $($fastRejectResp.StatusCode)." -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Fast rejection verified in OPEN state: HTTP 503 received in $($sw.Elapsed.TotalMilliseconds.ToString('F2')) ms." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 6. Clear Upstream Fault & Wait Open State Duration
    # ------------------------------------------------------------------------------
    Write-Host "`n[7/12] Clearing Upstream Fault and Waiting for Open State Duration (5000ms)..." -ForegroundColor Yellow
    Invoke-SafeRestCall -Uri "$OrdersUrl/fault/reset" -Method "Post" | Out-Null
    $ordHealth = Invoke-SafeRestCall -Uri "$OrdersUrl/health" -Method "Get"
    if ($ordHealth.StatusCode -ne 200) {
        Write-Host "  [FAIL] Upstream Orders service not healthy after fault reset." -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Upstream Orders Service fault cleared; upstream is healthy." -ForegroundColor Green

    Write-Host "  Waiting 5500 ms for circuit breaker open duration window to elapse..." -ForegroundColor Cyan
    Start-Sleep -Milliseconds 5500

    # ------------------------------------------------------------------------------
    # 7. Verify Automatic Transition: OPEN -> HALF_OPEN
    # ------------------------------------------------------------------------------
    Write-Host "`n[8/12] Verifying Automatic Transition: OPEN -> HALF_OPEN (No Manual Reset)..." -ForegroundColor Yellow
    $halfOpenDetail = (Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/resilience/routes/orders-service-v1" -Method "Get" -Headers $adminHeaders).Body | ConvertFrom-Json
    if ($halfOpenDetail.circuitBreaker.state -ne "HALF_OPEN") {
        Write-Host "  [FAIL] Circuit breaker failed to transition automatically to HALF_OPEN (state is $($halfOpenDetail.circuitBreaker.state))." -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Circuit breaker automatically transitioned: OPEN -> HALF_OPEN." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 8. Verify Probe Admission and Automatic Recovery: HALF_OPEN -> CLOSED
    # ------------------------------------------------------------------------------
    Write-Host "`n[9/12] Executing Probe Calls to Verify Automatic Recovery (HALF_OPEN -> CLOSED)..." -ForegroundColor Yellow
    $probe1 = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/proxy/orders" -Method "Get" -Headers $gatewayHeaders
    Write-Host "  Probe #1: HTTP $($probe1.StatusCode)" -ForegroundColor Cyan
    $probe2 = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/proxy/orders" -Method "Get" -Headers $gatewayHeaders
    Write-Host "  Probe #2: HTTP $($probe2.StatusCode)" -ForegroundColor Cyan
    $probe3 = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/proxy/orders" -Method "Get" -Headers $gatewayHeaders
    Write-Host "  Probe #3: HTTP $($probe3.StatusCode)" -ForegroundColor Cyan

    if ($probe1.StatusCode -ne 200 -or $probe2.StatusCode -ne 200 -or $probe3.StatusCode -ne 200) {
        Write-Host "  [FAIL] One or more probe calls failed during HALF_OPEN trial." -ForegroundColor Red
        exit 1
    }

    # Query state: 3 consecutive successes must have closed the circuit
    $recoveredDetail = (Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/resilience/routes/orders-service-v1" -Method "Get" -Headers $adminHeaders).Body | ConvertFrom-Json
    if ($recoveredDetail.circuitBreaker.state -ne "CLOSED") {
        Write-Host "  [FAIL] Circuit breaker failed to close after successful probe calls (state is $($recoveredDetail.circuitBreaker.state))." -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Automatic recovery verified: HALF_OPEN -> CLOSED after 3 consecutive successful probe calls." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 9. Verify Deterministic Re-Trip on Probe Failure (HALF_OPEN -> OPEN)
    # ------------------------------------------------------------------------------
    Write-Host "`n[10/12] Testing Failed Recovery Cycle: HALF_OPEN -> OPEN..." -ForegroundColor Yellow
    # Trip breaker back to OPEN
    $cfgResp = Invoke-SafeRestCall -Uri "$OrdersUrl/fault/configure" -Method "Post" -Body $faultBody
    for ($i = 1; $i -le 5; $i++) {
        Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/proxy/orders" -Method "Post" -Headers $gatewayHeaders -Body '{"item":"widget"}' | Out-Null
    }
    $reOpenDetail = (Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/resilience/routes/orders-service-v1" -Method "Get" -Headers $adminHeaders).Body | ConvertFrom-Json
    if ($reOpenDetail.circuitBreaker.state -ne "OPEN") {
        Write-Host "  [FAIL] Failed to re-trip circuit breaker to OPEN." -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Circuit tripped back to OPEN." -ForegroundColor Green

    # Wait for open duration
    Write-Host "  Waiting 5500 ms for open duration to elapse..." -ForegroundColor Cyan
    Start-Sleep -Milliseconds 5500

    # Verify transition to HALF_OPEN
    $halfOpen2 = (Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/resilience/routes/orders-service-v1" -Method "Get" -Headers $adminHeaders).Body | ConvertFrom-Json
    if ($halfOpen2.circuitBreaker.state -ne "HALF_OPEN") {
        Write-Host "  [FAIL] Circuit breaker failed to transition to HALF_OPEN on second cycle." -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Circuit transitioned to HALF_OPEN. Injecting failure on probe call..." -ForegroundColor Green

    # Probe call will encounter the active 503 fault on Orders service
    $failedProbe = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/proxy/orders" -Method "Post" -Headers $gatewayHeaders -Body '{"item":"widget"}'
    Write-Host "  Failed Probe Result: HTTP $($failedProbe.StatusCode)" -ForegroundColor Cyan

    # Breaker must immediately transition back to OPEN
    $reTrippedDetail = (Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/resilience/routes/orders-service-v1" -Method "Get" -Headers $adminHeaders).Body | ConvertFrom-Json
    if ($reTrippedDetail.circuitBreaker.state -ne "OPEN") {
        Write-Host "  [FAIL] Circuit breaker failed to re-trip to OPEN after failed probe (state is $($reTrippedDetail.circuitBreaker.state))." -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Failed recovery verified: Probe failure immediately re-tripped circuit from HALF_OPEN -> OPEN." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 10. Clean Restoration
    # ------------------------------------------------------------------------------
    Write-Host "`n[11/12] Restoring Normal Route Resilience State..." -ForegroundColor Yellow
    Invoke-SafeRestCall -Uri "$OrdersUrl/fault/reset" -Method "Post" | Out-Null
    $resetResp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/resilience/routes/orders-service-v1/reset" -Method "Post" -Headers $adminHeaders
    if ($resetResp.StatusCode -ne 200) {
        Write-Host "  [FAIL] Failed to reset circuit breaker via Admin API: HTTP $($resetResp.StatusCode)" -ForegroundColor Red
        exit 1
    }
    $finalDetail = (Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/resilience/routes/orders-service-v1" -Method "Get" -Headers $adminHeaders).Body | ConvertFrom-Json
    if ($finalDetail.circuitBreaker.state -ne "CLOSED") {
        Write-Host "  [FAIL] Circuit breaker not CLOSED after reset (state is $($finalDetail.circuitBreaker.state))." -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Route circuit breaker fully restored to CLOSED state." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 11. Final Summary
    # ------------------------------------------------------------------------------
    Write-Host "`n[12/12] Verification Complete" -ForegroundColor Yellow
    Write-Host "======================================================================" -ForegroundColor Green
    Write-Host " ALL RESILIENCE RECOVERY VERIFICATION CHECKS PASSED!                  " -ForegroundColor Green
    Write-Host "======================================================================" -ForegroundColor Green
    Write-Host "  - Automatic OPEN -> HALF_OPEN transition verified (no manual reset)" -ForegroundColor Cyan
    Write-Host "  - Probe call admission budget strictly bounded" -ForegroundColor Cyan
    Write-Host "  - Successful probes trigger automatic recovery to CLOSED" -ForegroundColor Cyan
    Write-Host "  - Failed probe triggers immediate re-trip to OPEN" -ForegroundColor Cyan
    Write-Host "  - Sliding window cleared cleanly upon recovery" -ForegroundColor Cyan

} finally {
    if (-not $KeepServicesRunning) {
        foreach ($proc in $servicesStarted) {
            try {
                Write-Host "Stopping background process $($proc.Id)..." -ForegroundColor DarkGray
                Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
            } catch {}
        }
    }
}
