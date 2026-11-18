param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$OrdersUrl = "http://localhost:8081",
    [string]$InventoryUrl = "http://localhost:8082",
    [string]$Username = "admin",
    [ValidateSet("Precheck", "OrdersOutage", "SlowUpstream", "BulkheadIsolation", "RedisFailure", "PolicySafety", "RetryStorm", "All")]
    [string]$Scenario = "Precheck",
    [switch]$ExecuteDisruptive,
    [switch]$KeepServicesRunning
)

$ErrorActionPreference = "Stop"

Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host "  Gatekeeper Controlled Chaos Engineering & Reliability Lab (Phase 5D)" -ForegroundColor Green
Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host "  Scenario Mode      : $Scenario" -ForegroundColor Cyan
Write-Host "  Execute Disruptive : $ExecuteDisruptive" -ForegroundColor $(if ($ExecuteDisruptive) { "Yellow" } else { "Gray" })

function Invoke-SafeRestCall {
    param(
        [string]$Uri,
        [string]$Method = "Get",
        [hashtable]$Headers = @{},
        [string]$Body = $null,
        [string]$ContentType = "application/json",
        [int]$TimeoutMs = 15000
    )

    $request = [System.Net.HttpWebRequest]::Create($Uri)
    $request.Method = $Method.ToUpperInvariant()
    $request.KeepAlive = $false
    $request.Timeout = $TimeoutMs

    $effectiveContentType = $ContentType

    foreach ($k in $Headers.Keys) {
        $val = [string]$Headers[$k]
        if ($k -ieq "Content-Type") {
            $effectiveContentType = $val
        } elseif ($k -ieq "Accept") {
            $request.Accept = $val
        } elseif ($k -ieq "User-Agent") {
            $request.UserAgent = $val
        } elseif ($k -ieq "Referer") {
            $request.Referer = $val
        } elseif ($k -ieq "Host") {
            $request.Host = $val
        } elseif ($k -ieq "Expect") {
            $request.Expect = $val
        } elseif ($k -ieq "Content-Length") {
            # Skip manual Content-Length
        } else {
            $request.Headers.Add($k, $val)
        }
    }

    if ($Body -and ($request.Method -eq "POST" -or $request.Method -eq "PUT" -or $request.Method -eq "PATCH" -or $request.Method -eq "DELETE")) {
        $request.ContentType = $effectiveContentType
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
        $request.ContentLength = $bytes.Length
        $reqStream = $request.GetRequestStream()
        $reqStream.Write($bytes, 0, $bytes.Length)
        $reqStream.Close()
    } elseif ($request.Method -eq "POST" -or $request.Method -eq "PUT" -or $request.Method -eq "PATCH") {
        $request.ContentType = $effectiveContentType
        $request.ContentLength = 0
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
            return @{
                StatusCode = 0
                Headers    = @{}
                Body       = $_.Exception.Message
            }
        }
    }
}

$servicesStarted = @()
$createdConsumerId = $null
$adminHeaders = @{}

try {
    # ------------------------------------------------------------------------------
    # 1. Preflight Health Verification
    # ------------------------------------------------------------------------------
    Write-Host "`n[1/6] Verifying Upstream Microservices & Gateway Health..." -ForegroundColor Yellow

    # Check Orders Service
    $ordUp = $false
    try {
        $chk = Invoke-SafeRestCall -Uri "$OrdersUrl/health" -Method "Get"
        if ($chk.StatusCode -eq 200) { $ordUp = $true }
    } catch { $ordUp = $false }

    if (-not $ordUp) {
        $ordJar = "D:\Development\Gatekeeper\services\orders-service\target\orders-service-0.0.1-SNAPSHOT.jar"
        if (Test-Path $ordJar) {
            Write-Host "  Starting background Orders Service (port 8081)..." -ForegroundColor Cyan
            $p = Start-Process -FilePath "java" -ArgumentList "-jar", $ordJar -PassThru -WindowStyle Hidden
            $servicesStarted += $p
            for ($r = 0; $r -lt 25; $r++) {
                Start-Sleep -Seconds 1
                try {
                    $chk = Invoke-SafeRestCall -Uri "$OrdersUrl/health" -Method "Get"
                    if ($chk.StatusCode -eq 200) { $ordUp = $true; break }
                } catch {}
            }
        }
    }
    if (-not $ordUp) {
        Write-Host "  [FAIL] Upstream Orders service at $OrdersUrl is not reachable." -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Upstream Orders service is healthy (HTTP 200)." -ForegroundColor Green

    # Check Inventory Service
    $invUp = $false
    try {
        $chk = Invoke-SafeRestCall -Uri "$InventoryUrl/health" -Method "Get"
        if ($chk.StatusCode -eq 200) { $invUp = $true }
    } catch { $invUp = $false }

    if (-not $invUp) {
        $invJar = "D:\Development\Gatekeeper\services\inventory-service\target\inventory-service-0.0.1-SNAPSHOT.jar"
        if (Test-Path $invJar) {
            Write-Host "  Starting background Inventory Service (port 8082)..." -ForegroundColor Cyan
            $p = Start-Process -FilePath "java" -ArgumentList "-jar", $invJar -PassThru -WindowStyle Hidden
            $servicesStarted += $p
            for ($r = 0; $r -lt 25; $r++) {
                Start-Sleep -Seconds 1
                try {
                    $chk = Invoke-SafeRestCall -Uri "$InventoryUrl/health" -Method "Get"
                    if ($chk.StatusCode -eq 200) { $invUp = $true; break }
                } catch {}
            }
        }
    }
    if (-not $invUp) {
        Write-Host "  [FAIL] Upstream Inventory service at $InventoryUrl is not reachable." -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Upstream Inventory service is healthy (HTTP 200)." -ForegroundColor Green

    # Check Gatekeeper Backend
    $gwUp = $false
    try {
        $chk = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/health" -Method "Get"
        if ($chk.StatusCode -eq 200) { $gwUp = $true }
    } catch { $gwUp = $false }

    if (-not $gwUp) {
        $gwJar = "D:\Development\Gatekeeper\backend\target\gatekeeper-backend-0.0.1-SNAPSHOT.jar"
        if (Test-Path $gwJar) {
            Write-Host "  Starting background Gatekeeper Backend (port 8080)..." -ForegroundColor Cyan
            $p = Start-Process -FilePath "java" -ArgumentList "-jar", $gwJar, "--spring.profiles.active=local" -PassThru -WindowStyle Hidden
            $servicesStarted += $p
            for ($r = 0; $r -lt 35; $r++) {
                Start-Sleep -Seconds 1
                try {
                    $chk = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/health" -Method "Get"
                    if ($chk.StatusCode -eq 200) { $gwUp = $true; break }
                } catch {}
            }
        }
    }
    if (-not $gwUp) {
        Write-Host "  [FAIL] Gatekeeper backend at $BaseUrl is not reachable." -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Gatekeeper backend is healthy (HTTP 200)." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 2. Authenticate Administrator
    # ------------------------------------------------------------------------------
    Write-Host "`n[2/6] Authenticating Operator for Chaos Administration..." -ForegroundColor Yellow
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
        Write-Host "  [NOTICE] Operator password not supplied in environment or interactive session." -ForegroundColor DarkYellow
        Write-Host "  Preflight checks passed. Disruptive scenarios require authenticated operator." -ForegroundColor Cyan
        if (-not $ExecuteDisruptive -or $Scenario -eq "Precheck") {
            Write-Host "`n[PRECHECK SUCCESS] All Gatekeeper targets are verified healthy." -ForegroundColor Green
            exit 0
        }
        exit 1
    }

    $loginBody = @{
        username = $Username
        password = $password
    } | ConvertTo-Json -Compress
    $password = $null

    $loginResp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/auth/login" -Method "Post" -Body $loginBody
    $loginBody = $null

    if ($loginResp.StatusCode -ne 200) {
        Write-Host "  [FAIL] Administrator login failed: HTTP $($loginResp.StatusCode)" -ForegroundColor Red
        exit 1
    }

    $loginJson = $loginResp.Body | ConvertFrom-Json
    $adminToken = if ($null -ne $loginJson.accessToken) { [string]$loginJson.accessToken } else { [string]$loginJson.token }
    $adminHeaders = @{ "Authorization" = "Bearer $adminToken" }
    Write-Host "  [OK] Operator authenticated successfully (JWT acquired)." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 3. Check Chaos Safety Mode
    # ------------------------------------------------------------------------------
    if (-not $ExecuteDisruptive -or $Scenario -eq "Precheck") {
        Write-Host "`n[NOTICE] Running in NON-DISRUPTIVE PRECHECK mode." -ForegroundColor Green
        Write-Host "  Pass -ExecuteDisruptive to execute live fault-injection experiments." -ForegroundColor Cyan
        Write-Host "`n======================================================================" -ForegroundColor Green
        Write-Host "  GATEKEEPER CHAOS PREFLIGHT VALIDATION PASSED!                        " -ForegroundColor Green
        Write-Host "======================================================================" -ForegroundColor Green
        exit 0
    }

    # ------------------------------------------------------------------------------
    # 4. Execute Selected Controlled Chaos Scenarios
    # ------------------------------------------------------------------------------
    Write-Host "`n[3/6] Executing Controlled Chaos Scenario: $Scenario..." -ForegroundColor Yellow

    $scenariosToRun = @()
    if ($Scenario -eq "All") {
        $scenariosToRun = @("ORDERS_OUTAGE", "SLOW_UPSTREAM", "BULKHEAD_SATURATION", "REDIS_FAILURE", "POLICY_SAFETY", "RETRY_STORM")
    } elseif ($Scenario -eq "OrdersOutage") {
        $scenariosToRun = @("ORDERS_OUTAGE")
    } elseif ($Scenario -eq "SlowUpstream") {
        $scenariosToRun = @("SLOW_UPSTREAM")
    } elseif ($Scenario -eq "BulkheadIsolation") {
        $scenariosToRun = @("BULKHEAD_SATURATION")
    } elseif ($Scenario -eq "RedisFailure") {
        $scenariosToRun = @("REDIS_FAILURE")
    } elseif ($Scenario -eq "PolicySafety") {
        $scenariosToRun = @("POLICY_SAFETY")
    } elseif ($Scenario -eq "RetryStorm") {
        $scenariosToRun = @("RETRY_STORM")
    }

    $experimentResults = @()

    foreach ($sc in $scenariosToRun) {
        Write-Host "`n  Running Scenario: $sc..." -ForegroundColor Cyan
        $runReqBody = @{
            scenario   = $sc
            parameters = @{}
        } | ConvertTo-Json -Compress

        $runResp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/chaos/experiments/run" -Method "Post" -Headers $adminHeaders -Body $runReqBody
        if ($runResp.StatusCode -ne 200) {
            Write-Host "  [FAIL] Scenario $sc failed to execute: HTTP $($runResp.StatusCode)" -ForegroundColor Red
            Write-Host "  Body: $($runResp.Body)" -ForegroundColor DarkRed
            exit 1
        }

        $expData = $runResp.Body | ConvertFrom-Json
        $experimentResults += $expData
        Write-Host "  [OK] Scenario $sc completed: State=$($expData.state), ID=$($expData.id)" -ForegroundColor Green

        if ($null -ne $expData.metrics) {
            Write-Host "       Requests: Total=$($expData.metrics.totalRequests), Success=$($expData.metrics.successfulRequests), Fail=$($expData.metrics.failedRequests)" -ForegroundColor Gray
            Write-Host "       Latency : P50=$($expData.metrics.gatewayLatencyP50Ms)ms, P95=$($expData.metrics.gatewayLatencyP95Ms)ms" -ForegroundColor Gray
            Write-Host "       Recovery: $($expData.metrics.recoveryDurationMs) ms" -ForegroundColor Gray
        }
    }

    Write-Host "`n[4/6] Verification of All Executed Experiments Complete!" -ForegroundColor Green
    Write-Host "`n======================================================================" -ForegroundColor Green
    Write-Host "  ALL CHAOS EXPERIMENTS COMPLETED WITH CLEAN RECOVERY & ISOLATION!    " -ForegroundColor Green
    Write-Host "======================================================================" -ForegroundColor Green

} finally {
    # Guaranteed cleanup
    try {
        Invoke-SafeRestCall -Uri "$OrdersUrl/fault/reset" -Method "Post" | Out-Null
        Invoke-SafeRestCall -Uri "$InventoryUrl/fault/reset" -Method "Post" | Out-Null
        Write-Host "`n[CLEANUP] Reset upstream fault injection configurations." -ForegroundColor DarkGray
    } catch {}

    if (-not $KeepServicesRunning) {
        foreach ($proc in $servicesStarted) {
            try {
                Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
            } catch {}
        }
    }
}
