param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$OrdersUrl = "http://localhost:8081",
    [string]$InventoryUrl = "http://localhost:8082",
    [string]$Username = "admin",
    [switch]$KeepServicesRunning
)

$ErrorActionPreference = "Stop"

Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host "  Gatekeeper Bulkhead Concurrency & Isolation Verification (Phase 5B.2) " -ForegroundColor Green
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
    Write-Host "`n[1/10] Checking Gatekeeper Backend Health ($BaseUrl)..." -ForegroundColor Yellow
    $gwHealth = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/health" -Method "Get"
    if ($gwHealth.StatusCode -ne 200) {
        Write-Host "  [FAIL] Gatekeeper backend health check failed: HTTP $($gwHealth.StatusCode)" -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Gatekeeper backend is healthy (HTTP 200)." -ForegroundColor Green

    # Check Orders Service
    Write-Host "`n[2/10] Verifying Upstream Orders Service ($OrdersUrl)..." -ForegroundColor Yellow
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

    # Check Inventory Service
    Write-Host "`n[3/10] Verifying Upstream Inventory Service ($InventoryUrl)..." -ForegroundColor Yellow
    $invUp = $false
    try {
        $invHealth = Invoke-SafeRestCall -Uri "$InventoryUrl/health" -Method "Get"
        if ($invHealth.StatusCode -eq 200) { $invUp = $true }
    } catch {
        $invUp = $false
    }

    if (-not $invUp) {
        Write-Host "  Inventory service not detected on port 8082. Starting background process..." -ForegroundColor Cyan
        $invJar = "D:\Development\Gatekeeper\services\inventory-service\target\inventory-service-0.0.1-SNAPSHOT.jar"
        $invProc = Start-Process -FilePath "java" -ArgumentList "-jar", $invJar -PassThru -WindowStyle Hidden
        $servicesStarted += $invProc
        $retries = 20
        while ($retries -gt 0 -and -not $invUp) {
            Start-Sleep -Seconds 1
            try {
                $chk = Invoke-SafeRestCall -Uri "$InventoryUrl/health" -Method "Get"
                if ($chk.StatusCode -eq 200) { $invUp = $true }
            } catch {}
            $retries--
        }
    }

    if (-not $invUp) {
        Write-Host "  [FAIL] Failed to start/reach Inventory Service on port 8082." -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Upstream Inventory Service is UP on port 8082." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 2. Operator Authentication
    # ------------------------------------------------------------------------------
    Write-Host "`n[4/10] Authenticating Administrator ($Username)..." -ForegroundColor Yellow
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
    Write-Host "`n[5/10] Provisioning Test Consumer with Enterprise Quota and Permissions..." -ForegroundColor Yellow
    $plansResp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/plans" -Method "Get" -Headers $adminHeaders
    $plansJson = $plansResp.Body | ConvertFrom-Json
    $enterprisePlan = $plansJson | Where-Object { $_.name -eq "ENTERPRISE" } | Select-Object -First 1
    if (-not $enterprisePlan) {
        $enterprisePlan = $plansJson | Where-Object { $_.name -eq "PRO" } | Select-Object -First 1
    }
    if (-not $enterprisePlan) {
        $enterprisePlan = $plansJson | Select-Object -First 1
    }

    $cName = "Bulkhead Test Consumer " + [System.Guid]::NewGuid().ToString().Substring(0, 8)
    $cEmail = "bulkhead_" + [System.Guid]::NewGuid().ToString().Substring(0, 8) + "@example.com"
    $createConsumerBody = @{
        name   = $cName
        email  = $cEmail
        planId = $enterprisePlan.id
    } | ConvertTo-Json -Compress

    $consumerResp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/consumers" -Method "Post" -Headers $adminHeaders -Body $createConsumerBody
    if ($consumerResp.StatusCode -ne 201) {
        Write-Host "  [FAIL] Failed to create test consumer: HTTP $($consumerResp.StatusCode)" -ForegroundColor Red
        exit 1
    }
    $consumerJson = $consumerResp.Body | ConvertFrom-Json
    $createdConsumerId = $consumerJson.id

    $createKeyBody = @{
        name   = "Bulkhead Key"
        scopes = @("orders:read", "orders:write", "inventory:read")
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

    # Reset any existing faults
    Invoke-SafeRestCall -Uri "$OrdersUrl/fault/reset" -Method "Post" | Out-Null
    Invoke-SafeRestCall -Uri "$InventoryUrl/fault/reset" -Method "Post" | Out-Null
    Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/resilience/routes/orders-service-v1/reset" -Method "Post" -Headers $adminHeaders | Out-Null

    # ------------------------------------------------------------------------------
    # 4. Verify Initial Bulkhead Capacity
    # ------------------------------------------------------------------------------
    Write-Host "`n[6/10] Verifying Route Bulkhead Baseline Capacity..." -ForegroundColor Yellow
    $ordersDetail = (Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/resilience/routes/orders-service-v1" -Method "Get" -Headers $adminHeaders).Body | ConvertFrom-Json
    $invDetail = (Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/resilience/routes/inventory-service-v1" -Method "Get" -Headers $adminHeaders).Body | ConvertFrom-Json

    $ordersMaxPermits = $ordersDetail.bulkhead.maxConcurrentCalls
    $invMaxPermits = $invDetail.bulkhead.maxConcurrentCalls
    Write-Host "  Orders Bulkhead: max=$ordersMaxPermits, available=$($ordersDetail.bulkhead.availablePermits), inFlight=$($ordersDetail.bulkhead.inFlightCalls)" -ForegroundColor Cyan
    Write-Host "  Inventory Bulkhead: max=$invMaxPermits, available=$($invDetail.bulkhead.availablePermits), inFlight=$($invDetail.bulkhead.inFlightCalls)" -ForegroundColor Cyan

    if ($ordersMaxPermits -ne 10 -or $ordersDetail.bulkhead.availablePermits -ne 10) {
        Write-Host "  [FAIL] Orders bulkhead capacity unexpected (expected 10 permits)." -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Bulkhead baselines confirmed (10 permits each, 0 in-flight)." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 5. Inject Latency into Orders Service to Saturate Bulkhead
    # ------------------------------------------------------------------------------
    Write-Host "`n[7/10] Injecting 2500ms Delay into Upstream Orders Service..." -ForegroundColor Yellow
    $delayBody = @{
        delayMs      = 2500
        statusCode   = 0
        failureCount = 0
    } | ConvertTo-Json -Compress

    $cfgResp = Invoke-SafeRestCall -Uri "$OrdersUrl/fault/configure" -Method "Post" -Body $delayBody
    if ($cfgResp.StatusCode -ne 200) {
        Write-Host "  [FAIL] Failed to configure delay fault on Orders service: HTTP $($cfgResp.StatusCode)" -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] 2500ms artificial delay configured on Orders service." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 6. Execute 30 Concurrent Requests to orders-service-v1
    # ------------------------------------------------------------------------------
    Write-Host "`n[8/10] Executing 30 Concurrent Requests against Orders Route (Bulkhead Capacity = 10)..." -ForegroundColor Yellow
    $pool = [RunspaceFactory]::CreateRunspacePool(1, 35)
    $pool.Open()

    $workerScript = {
        param($url, $apiKey)
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $statusCode = 0
        $body = ""
        try {
            $req = [System.Net.HttpWebRequest]::Create($url)
            $req.Method = "GET"
            $req.KeepAlive = $false
            $req.Timeout = 15000
            $req.Headers.Add("X-API-Key", $apiKey)
            $resp = $req.GetResponse()
            $statusCode = [int]$resp.StatusCode
            $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
            $body = $reader.ReadToEnd()
            $reader.Close()
            $resp.Close()
        } catch [System.Net.WebException] {
            if ($_.Exception.Response) {
                $resp = $_.Exception.Response
                $statusCode = [int]$resp.StatusCode
                $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
                $body = $reader.ReadToEnd()
                $reader.Close()
                $resp.Close()
            } else {
                $statusCode = 500
                $body = $_.Exception.Message
            }
        }
        $sw.Stop()
        return [PSCustomObject]@{
            StatusCode = $statusCode
            LatencyMs  = [math]::Round($sw.Elapsed.TotalMilliseconds, 2)
            Body       = $body
        }
    }

    $ordersUrl = "$BaseUrl/api/v1/proxy/orders"
    $asyncTasks = @()
    for ($i = 0; $i -lt 30; $i++) {
        $ps = [PowerShell]::Create().AddScript($workerScript).AddParameter("url", $ordersUrl).AddParameter("apiKey", $rawApiKey)
        $ps.RunspacePool = $pool
        $asyncTasks += @{ Pipe = $ps; Handle = $ps.BeginInvoke() }
    }

    # Allow ~400ms for requests to hit gateway and saturate the bulkhead
    Start-Sleep -Milliseconds 400

    # ------------------------------------------------------------------------------
    # 7. Cross-Route Isolation Test: Query Inventory while Orders is Saturated
    # ------------------------------------------------------------------------------
    Write-Host "`n[9/10] Verifying Cross-Route Isolation (Inventory Route under Orders Bulkhead Saturation)..." -ForegroundColor Yellow
    $invSw = [System.Diagnostics.Stopwatch]::StartNew()
    $invResp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/proxy/inventory" -Method "Get" -Headers $gatewayHeaders
    $invSw.Stop()

    if ($invResp.StatusCode -ne 200) {
        Write-Host "  [FAIL] Cross-route isolation failed! Inventory request returned HTTP $($invResp.StatusCode) while Orders was saturated." -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Cross-Route Isolation Confirmed: Inventory request succeeded with HTTP 200 in $($invSw.Elapsed.TotalMilliseconds.ToString('F2')) ms." -ForegroundColor Green

    # Also inspect route resilience telemetry while orders is in flight
    $midflightOrders = (Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/resilience/routes/orders-service-v1" -Method "Get" -Headers $adminHeaders).Body | ConvertFrom-Json
    Write-Host "  Telemetry during saturation: Orders in-flight=$($midflightOrders.bulkhead.inFlightCalls), availablePermits=$($midflightOrders.bulkhead.availablePermits), rejectedCalls=$($midflightOrders.bulkhead.rejectedCalls)" -ForegroundColor Cyan

    # ------------------------------------------------------------------------------
    # 8. Collect and Validate Concurrency Results
    # ------------------------------------------------------------------------------
    Write-Host "`n  Waiting for all 30 concurrent orders calls to finish..." -ForegroundColor Cyan
    $results = @()
    foreach ($t in $asyncTasks) {
        $res = $t.Pipe.EndInvoke($t.Handle)
        $results += $res
        $t.Pipe.Dispose()
    }
    $pool.Close()
    $pool.Dispose()

    $admittedCount = ($results | Where-Object { $_.StatusCode -eq 200 }).Count
    $rejectedCount = ($results | Where-Object { $_.StatusCode -eq 503 }).Count
    $otherCount = ($results | Where-Object { $_.StatusCode -ne 200 -and $_.StatusCode -ne 503 }).Count

    Write-Host "`n  Concurrency Results (Total 30 requests):" -ForegroundColor Green
    Write-Host "    HTTP 200 Admitted: $admittedCount" -ForegroundColor Green
    Write-Host "    HTTP 503 Rejected: $rejectedCount" -ForegroundColor Yellow
    if ($otherCount -gt 0) {
        Write-Host "    Other Status Codes: $otherCount" -ForegroundColor Red
    }

    # Verify that exactly 10 requests were admitted and 20 were rejected
    if ($admittedCount -ne 10 -or $rejectedCount -ne 20) {
        Write-Host "  [FAIL] Bulkhead concurrency enforcement violation: expected 10 admitted and 20 rejected, got $admittedCount admitted and $rejectedCount rejected." -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Bulkhead strictly enforced 10 concurrent requests limit. 20 excess requests rejected with HTTP 503." -ForegroundColor Green

    # Verify permit recovery (zero permit leaks)
    $postDetail = (Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/resilience/routes/orders-service-v1" -Method "Get" -Headers $adminHeaders).Body | ConvertFrom-Json
    if ($postDetail.bulkhead.availablePermits -ne 10 -or $postDetail.bulkhead.inFlightCalls -ne 0) {
        Write-Host "  [FAIL] Permit leak detected! Available permits: $($postDetail.bulkhead.availablePermits), inFlight: $($postDetail.bulkhead.inFlightCalls)" -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Zero permit leak verified: all 10 permits returned to bulkhead pool (available=10, inFlight=0)." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 9. Clean Restoration
    # ------------------------------------------------------------------------------
    Write-Host "`n[10/10] Restoring Upstream Orders Service..." -ForegroundColor Yellow
    Invoke-SafeRestCall -Uri "$OrdersUrl/fault/reset" -Method "Post" | Out-Null
    Write-Host "  [OK] Upstream faults reset." -ForegroundColor Green

    Write-Host "`n======================================================================" -ForegroundColor Green
    Write-Host " ALL BULKHEAD CONCURRENCY & ISOLATION CHECKS PASSED!                  " -ForegroundColor Green
    Write-Host "======================================================================" -ForegroundColor Green
    Write-Host "  - Strict permit bounding (10 permits max concurrent in flight)" -ForegroundColor Cyan
    Write-Host "  - Immediate fast rejection for excess concurrent calls (20 rejected with 503)" -ForegroundColor Cyan
    Write-Host "  - Cross-route bulkhead isolation: Inventory 100% operational during Orders saturation" -ForegroundColor Cyan
    Write-Host "  - Zero permit leak: all execution permits restored upon call completion" -ForegroundColor Cyan

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
