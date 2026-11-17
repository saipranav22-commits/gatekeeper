param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$OrdersUrl = "http://localhost:8081",
    [string]$InventoryUrl = "http://localhost:8082",
    [string]$Username = "admin"
)

$ErrorActionPreference = "Stop"

Write-Host "======================================================" -ForegroundColor Cyan
Write-Host " Gatekeeper Real API Routing & Upstream Verification  " -ForegroundColor Green
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

$servicesStarted = @()

try {
    # ------------------------------------------------------------------------------
    # 1. Health Checks & Upstream Availability
    # ------------------------------------------------------------------------------
    Write-Host "`n[1/19] Performing Gatekeeper Backend Health Check ($BaseUrl)..." -ForegroundColor Yellow
    $gwHealth = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/health" -Method "Get"
    if ($gwHealth.StatusCode -ne 200) {
        Write-Host "  [FAIL] Gatekeeper backend health check failed: HTTP $($gwHealth.StatusCode)" -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Gatekeeper backend is healthy (HTTP 200)." -ForegroundColor Green

    # Check Orders Service
    Write-Host "`n[2/19] Verifying Upstream Orders Service ($OrdersUrl)..." -ForegroundColor Yellow
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
    Write-Host "`n[3/19] Verifying Upstream Inventory Service ($InventoryUrl)..." -ForegroundColor Yellow
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
    # 4. Operator Authentication
    # ------------------------------------------------------------------------------
    Write-Host "`n[4/19] Authenticating Administrator ($Username)..." -ForegroundColor Yellow
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
    $adminHeaders = @{ "Authorization" = "Bearer $jwtToken" }
    Write-Host "  [OK] Administrator authenticated. JWT acquired." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 5. Create Test Consumer
    # ------------------------------------------------------------------------------
    Write-Host "`n[5/19] Creating Dedicated Test Consumer with PRO Plan..." -ForegroundColor Yellow
    $plansRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/plans" -Method "Get" -Headers $adminHeaders
    $plansJson = $plansRes.Body | ConvertFrom-Json
    $proPlan = $plansJson | Where-Object { $_.name -eq "PRO" } | Select-Object -First 1

    if (-not $proPlan) {
        Write-Host "  [FAIL] PRO plan not found in database." -ForegroundColor Red
        exit 1
    }

    $uniqueId = [System.Guid]::NewGuid().ToString().Substring(0, 8)
    $createConsumerBody = @{
        name   = "Routing Test Consumer $uniqueId"
        email  = "routing_$uniqueId@example.com"
        planId = $proPlan.id
    } | ConvertTo-Json -Compress

    $consumerRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/consumers" -Method "Post" -Headers $adminHeaders -Body $createConsumerBody
    if ($consumerRes.StatusCode -ne 201) {
        Write-Host "  [FAIL] Failed to create consumer: HTTP $($consumerRes.StatusCode)" -ForegroundColor Red
        exit 1
    }

    $consumerJson = $consumerRes.Body | ConvertFrom-Json
    $consumerId = $consumerJson.id
    Write-Host "  [OK] Test consumer created: ID=$consumerId" -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 6. Generate Key A (orders:read only)
    # ------------------------------------------------------------------------------
    Write-Host "`n[6/19] Generating Key A (Scope: orders:read only)..." -ForegroundColor Yellow
    $keyABody = @{
        name   = "Orders Read Key"
        scopes = @("orders:read")
    } | ConvertTo-Json -Compress

    $keyARes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/consumers/$consumerId/keys" -Method "Post" -Headers $adminHeaders -Body $keyABody
    if ($keyARes.StatusCode -ne 201) {
        Write-Host "  [FAIL] Failed to generate Key A: HTTP $($keyARes.StatusCode)" -ForegroundColor Red
        exit 1
    }
    $keyAJson = $keyARes.Body | ConvertFrom-Json
    $rawKeyA = $keyAJson.plaintextKey
    Write-Host "  [OK] Key A generated with scope orders:read." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 7. Generate Key B (orders:read, orders:write, inventory:read)
    # ------------------------------------------------------------------------------
    Write-Host "`n[7/19] Generating Key B (Scope: orders:read, orders:write, inventory:read)..." -ForegroundColor Yellow
    $keyBBody = @{
        name   = "Full Proxy Access Key"
        scopes = @("orders:read", "orders:write", "inventory:read")
    } | ConvertTo-Json -Compress

    $keyBRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/consumers/$consumerId/keys" -Method "Post" -Headers $adminHeaders -Body $keyBBody
    if ($keyBRes.StatusCode -ne 201) {
        Write-Host "  [FAIL] Failed to generate Key B: HTTP $($keyBRes.StatusCode)" -ForegroundColor Red
        exit 1
    }
    $keyBJson = $keyBRes.Body | ConvertFrom-Json
    $rawKeyB = $keyBJson.plaintextKey
    Write-Host "  [OK] Key B generated with full proxy scopes." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 8. Proxy GET /api/v1/proxy/orders with Key A
    # ------------------------------------------------------------------------------
    Write-Host "`n[8/19] Testing Proxy GET /api/v1/proxy/orders with Key A..." -ForegroundColor Yellow
    $res8 = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/proxy/orders" -Method "Get" -Headers @{ "X-API-Key" = $rawKeyA }
    if ($res8.StatusCode -ne 200) {
        Write-Host "  [FAIL] Expected HTTP 200, got $($res8.StatusCode): $($res8.Body)" -ForegroundColor Red
        exit 1
    }
    if (-not $res8.Headers.ContainsKey("RateLimit-Remaining")) {
        Write-Host "  [FAIL] Missing RateLimit-Remaining header in response." -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Proxy GET /orders succeeded (HTTP 200). RateLimit-Remaining: $($res8.Headers['RateLimit-Remaining'])" -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 9. Proxy GET /api/v1/proxy/orders/ord-101 with Key A
    # ------------------------------------------------------------------------------
    Write-Host "`n[9/19] Testing Proxy GET /api/v1/proxy/orders/ord-101 with Key A..." -ForegroundColor Yellow
    $res9 = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/proxy/orders/ord-101" -Method "Get" -Headers @{ "X-API-Key" = $rawKeyA }
    if ($res9.StatusCode -ne 200) {
        Write-Host "  [FAIL] Expected HTTP 200, got $($res9.StatusCode): $($res9.Body)" -ForegroundColor Red
        exit 1
    }
    $ordJson = $res9.Body | ConvertFrom-Json
    if ($ordJson.itemSku -ne "SKU-LAPTOP-01") {
        Write-Host "  [FAIL] Unexpected order returned: SKU=$($ordJson.itemSku)" -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Successfully retrieved order ord-101 via proxy (SKU=$($ordJson.itemSku))." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 10. Scope Enforcement: POST /api/v1/proxy/orders with Key A -> Expect 403
    # ------------------------------------------------------------------------------
    Write-Host "`n[10/19] Testing Scope Enforcement: POST /orders with Key A (orders:read only)..." -ForegroundColor Yellow
    $newOrderReq = @{
        consumerId = $consumerId
        itemSku    = "SKU-DESK-09"
        quantity   = 1
        totalPrice = 349.99
    } | ConvertTo-Json -Compress

    $res10 = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/proxy/orders" -Method "Post" -Headers @{ "X-API-Key" = $rawKeyA } -Body $newOrderReq
    if ($res10.StatusCode -ne 403) {
        Write-Host "  [FAIL] Expected HTTP 403 Forbidden, got $($res10.StatusCode): $($res10.Body)" -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Key A blocked with HTTP 403 Forbidden (insufficient scope for POST)." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 11. POST /api/v1/proxy/orders with Key B (has orders:write) -> Expect 201
    # ------------------------------------------------------------------------------
    Write-Host "`n[11/19] Testing Authorized POST /orders with Key B (has orders:write)..." -ForegroundColor Yellow
    $res11 = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/proxy/orders" -Method "Post" -Headers @{ "X-API-Key" = $rawKeyB } -Body $newOrderReq
    if ($res11.StatusCode -ne 201) {
        Write-Host "  [FAIL] Expected HTTP 201 Created, got $($res11.StatusCode): $($res11.Body)" -ForegroundColor Red
        exit 1
    }
    $createdOrd = $res11.Body | ConvertFrom-Json
    Write-Host "  [OK] Order successfully created via proxy (ID=$($createdOrd.id), SKU=$($createdOrd.itemSku))." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 12. Proxy GET /api/v1/proxy/inventory with Key B
    # ------------------------------------------------------------------------------
    Write-Host "`n[12/19] Testing Proxy GET /api/v1/proxy/inventory with Key B..." -ForegroundColor Yellow
    $res12 = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/proxy/inventory" -Method "Get" -Headers @{ "X-API-Key" = $rawKeyB }
    if ($res12.StatusCode -ne 200) {
        Write-Host "  [FAIL] Expected HTTP 200, got $($res12.StatusCode): $($res12.Body)" -ForegroundColor Red
        exit 1
    }
    $invList = $res12.Body | ConvertFrom-Json
    Write-Host "  [OK] Successfully retrieved inventory items (count=$($invList.Count))." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 13. Proxy GET /api/v1/proxy/inventory/SKU-LAPTOP-01 with Key B
    # ------------------------------------------------------------------------------
    Write-Host "`n[13/19] Testing Proxy GET /api/v1/proxy/inventory/SKU-LAPTOP-01..." -ForegroundColor Yellow
    $res13 = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/proxy/inventory/SKU-LAPTOP-01" -Method "Get" -Headers @{ "X-API-Key" = $rawKeyB }
    if ($res13.StatusCode -ne 200) {
        Write-Host "  [FAIL] Expected HTTP 200, got $($res13.StatusCode): $($res13.Body)" -ForegroundColor Red
        exit 1
    }
    $laptopItem = $res13.Body | ConvertFrom-Json
    Write-Host "  [OK] Item retrieved (SKU=$($laptopItem.sku), Qty=$($laptopItem.quantity), InStock=$($laptopItem.inStock))." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 14. Method Enforcement: DELETE /inventory/SKU-LAPTOP-01 -> Expect 405
    # ------------------------------------------------------------------------------
    Write-Host "`n[14/19] Testing Disallowed Method: DELETE /inventory/SKU-LAPTOP-01..." -ForegroundColor Yellow
    $res14 = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/proxy/inventory/SKU-LAPTOP-01" -Method "Delete" -Headers @{ "X-API-Key" = $rawKeyB }
    if ($res14.StatusCode -ne 405) {
        Write-Host "  [FAIL] Expected HTTP 405 Method Not Allowed, got $($res14.StatusCode)" -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Disallowed method rejected with HTTP 405 Method Not Allowed." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 15. Missing Authentication: GET /api/v1/proxy/orders without key -> Expect 401
    # ------------------------------------------------------------------------------
    Write-Host "`n[15/19] Testing Missing Authentication on Proxy Endpoint..." -ForegroundColor Yellow
    $res15 = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/proxy/orders" -Method "Get"
    if ($res15.StatusCode -ne 401) {
        Write-Host "  [FAIL] Expected HTTP 401 Unauthorized, got $($res15.StatusCode)" -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Unauthenticated request rejected with HTTP 401." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 16. Invalid Key: GET /api/v1/proxy/orders with forged key -> Expect 401
    # ------------------------------------------------------------------------------
    Write-Host "`n[16/19] Testing Invalid API Key on Proxy Endpoint..." -ForegroundColor Yellow
    $res16 = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/proxy/orders" -Method "Get" -Headers @{ "X-API-Key" = "gk_live_ffffffffffffffffffffffffffffffff" }
    if ($res16.StatusCode -ne 401) {
        Write-Host "  [FAIL] Expected HTTP 401 Unauthorized, got $($res16.StatusCode)" -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Forged API key rejected with HTTP 401." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 17. Unknown Route: GET /api/v1/proxy/unknown/path -> Expect 404
    # ------------------------------------------------------------------------------
    Write-Host "`n[17/19] Testing Unknown Proxy Route..." -ForegroundColor Yellow
    $res17 = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/proxy/unknown/path" -Method "Get" -Headers @{ "X-API-Key" = $rawKeyB }
    if ($res17.StatusCode -ne 404) {
        Write-Host "  [FAIL] Expected HTTP 404 Not Found, got $($res17.StatusCode)" -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Unknown route rejected with HTTP 404 Not Found." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 18. SSRF / Path Traversal Attack -> Expect 400
    # ------------------------------------------------------------------------------
    Write-Host "`n[18/19] Testing Path Traversal / SSRF Protection..." -ForegroundColor Yellow
    $res18 = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/proxy/orders/..%2fadmin" -Method "Get" -Headers @{ "X-API-Key" = $rawKeyB }
    if ($res18.StatusCode -ne 400) {
        Write-Host "  [FAIL] Expected HTTP 400 Bad Request, got $($res18.StatusCode)" -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Path traversal sequence blocked with HTTP 400 Bad Request." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 19. Asynchronous Telemetry & Analytics Verification
    # ------------------------------------------------------------------------------
    Write-Host "`n[19/19] Verifying Asynchronous Routing Telemetry Ingestion..." -ForegroundColor Yellow
    Write-Host "  Waiting 4 seconds for Redis Streams batch processor to persist events..." -ForegroundColor Cyan
    Start-Sleep -Seconds 4

    $analyticsRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/analytics/overview" -Method "Get" -Headers $adminHeaders
    if ($analyticsRes.StatusCode -eq 200) {
        $stats = $analyticsRes.Body | ConvertFrom-Json
        Write-Host "  [OK] Analytics overview retrieved: TotalRequests=$($stats.totalRequests), Allowed=$($stats.allowedRequests)" -ForegroundColor Green
    } else {
        Write-Host "  [WARN] Analytics overview returned HTTP $($analyticsRes.StatusCode)" -ForegroundColor Yellow
    }

    # ------------------------------------------------------------------------------
    # Cleanup
    # ------------------------------------------------------------------------------
    Write-Host "`nCleaning up verification consumer and keys..." -ForegroundColor Cyan
    $delRes = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/consumers/$consumerId" -Method "Delete" -Headers $adminHeaders
    Write-Host "  [OK] Test consumer cleanup completed (HTTP $($delRes.StatusCode))." -ForegroundColor Green

    Write-Host "`n======================================================" -ForegroundColor Cyan
    Write-Host " ALL 19 REAL API ROUTING VERIFICATION STEPS PASSED!   " -ForegroundColor Green
    Write-Host "======================================================" -ForegroundColor Cyan

} finally {
    if ($servicesStarted.Count -gt 0) {
        Write-Host "`nStopping background upstream services..." -ForegroundColor Cyan
        foreach ($proc in $servicesStarted) {
            try {
                Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
                Write-Host "  Stopped background process PID $($proc.Id)." -ForegroundColor Gray
            } catch {}
        }
    }
}
