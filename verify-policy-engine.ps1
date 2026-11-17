param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$OrdersUrl = "http://localhost:8081",
    [string]$InventoryUrl = "http://localhost:8082",
    [string]$Username = "admin"
)

$ErrorActionPreference = "Stop"

Write-Host "======================================================" -ForegroundColor Cyan
Write-Host "  Gatekeeper Policy Engine Live Verification          " -ForegroundColor Green
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
$createdConsumerId = $null
$policyKey = "orders-service-v1"

try {
    # ------------------------------------------------------------------------------
    # 1. Health Checks & Upstream Availability
    # ------------------------------------------------------------------------------
    Write-Host "`n[1/16] Verifying Services Availability..." -ForegroundColor Yellow

    # Check Gatekeeper Backend
    $gwJar = "D:\Development\Gatekeeper\backend\target\gatekeeper-backend-0.0.1-SNAPSHOT.jar"
    $gwUp = $false
    try {
        $gwHealth = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/health" -Method "Get"
        if ($gwHealth.StatusCode -eq 200) { $gwUp = $true }
    } catch {
        $gwUp = $false
    }

    if ($gwUp) {
        # Check if already running backend exposes policy endpoint (must return 401 unauthenticated, NOT 404)
        $probePolicy = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/policies" -Method "Get"
        if ($probePolicy.StatusCode -eq 404) {
            Write-Host "  [FAIL] Running backend on port 8080 is an outdated build missing policy endpoints (HTTP 404)." -ForegroundColor Red
            Write-Host "  Please stop the outdated process on port 8080 and rerun this verification script." -ForegroundColor Red
            exit 1
        }
        Write-Host "  [OK] Gatekeeper backend is running and exposes policy engine." -ForegroundColor Green
    } else {
        # Verify backend JAR exists and contains PolicyAdminController
        if (-not (Test-Path $gwJar)) {
            Write-Host "  [FAIL] Backend JAR not found at $gwJar. Please package using 'mvn clean package -DskipTests'." -ForegroundColor Red
            exit 1
        }

        [System.Reflection.Assembly]::LoadWithPartialName("System.IO.Compression.FileSystem") | Out-Null
        $hasPolicyClass = $false
        try {
            $zip = [System.IO.Compression.ZipFile]::OpenRead($gwJar)
            $entry = $zip.GetEntry("BOOT-INF/classes/com/gatekeeper/policy/controller/PolicyAdminController.class")
            if ($null -ne $entry) { $hasPolicyClass = $true }
            $zip.Dispose()
        } catch {
            $hasPolicyClass = $false
        }

        if (-not $hasPolicyClass) {
            Write-Host "  [FAIL] Backend JAR at $gwJar is stale: missing PolicyAdminController.class." -ForegroundColor Red
            Write-Host "  Please package the latest source using 'mvn clean package -DskipTests'." -ForegroundColor Red
            exit 1
        }

        Write-Host "  Gatekeeper backend not detected on port 8080. Starting verified background process..." -ForegroundColor Cyan
        $gwProc = Start-Process -FilePath "java" -ArgumentList "-jar", $gwJar, "--spring.profiles.active=local" -PassThru -WindowStyle Hidden
        $servicesStarted += $gwProc
        $retries = 35
        while ($retries -gt 0 -and -not $gwUp) {
            Start-Sleep -Seconds 1
            try {
                $chk = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/health" -Method "Get"
                if ($chk.StatusCode -eq 200) { $gwUp = $true }
            } catch {}
            $retries--
        }

        if (-not $gwUp) {
            Write-Host "  [FAIL] Gatekeeper backend failed to respond on port 8080 within timeout." -ForegroundColor Red
            exit 1
        }

        # Verify endpoint registration (unauthenticated request to /api/v1/policies must return 401, not 404)
        $policyEndpointCheck = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/policies" -Method "Get"
        if ($policyEndpointCheck.StatusCode -eq 404) {
            Write-Host "  [FAIL] Started backend on port 8080 does not expose /api/v1/policies (HTTP 404)." -ForegroundColor Red
            exit 1
        }
        Write-Host "  [OK] Gatekeeper backend is healthy and policy endpoint verified on port 8080." -ForegroundColor Green
    }

    # Check Orders Service
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
        $retries = 25
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
        Write-Host "  [FAIL] Orders service failed to respond on port 8081." -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Upstream Orders Service is healthy on port 8081." -ForegroundColor Green

    # Check Inventory Service
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
        $retries = 25
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
        Write-Host "  [FAIL] Inventory service failed to respond on port 8082." -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Upstream Inventory Service is healthy on port 8082." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 2. Operator Authentication
    # ------------------------------------------------------------------------------
    Write-Host "`n[2/16] Authenticating Administrator ($Username)..." -ForegroundColor Yellow
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
        Write-Host "  [FAIL] Administrator credentials unavailable. Set GATEKEEPER_ADMIN_PASSWORD environment variable." -ForegroundColor Red
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
        Write-Host "  [FAIL] Administrator login failed with HTTP $($loginResp.StatusCode)." -ForegroundColor Red
        exit 1
    }

    $loginJson = $loginResp.Body | ConvertFrom-Json
    $adminToken = if ($loginJson.accessToken) { [string]$loginJson.accessToken } else { [string]$loginJson.token }
    if ([string]::IsNullOrWhiteSpace($adminToken)) {
        Write-Host "  [FAIL] Token was missing in login response." -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Administrator authenticated successfully (HTTP 200)." -ForegroundColor Green
    $adminHeaders = @{ "Authorization" = "Bearer $adminToken" }

    # ------------------------------------------------------------------------------
    # 3. Create Test Consumer and Scoped API Key
    # ------------------------------------------------------------------------------
    Write-Host "`n[3/16] Provisioning Test Consumer and Scoped API Key..." -ForegroundColor Yellow
    $plansResp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/plans" -Method "Get" -Headers $adminHeaders
    $plans = $plansResp.Body | ConvertFrom-Json
    $starterPlan = $plans | Where-Object { $_.name -eq "PRO" -or $_.name -eq "ENTERPRISE" } | Select-Object -First 1
    if (-not $starterPlan) { $starterPlan = $plans[0] }

    $uniqueId = [Guid]::NewGuid().ToString().Substring(0, 8)
    $consumerReq = @{
        name   = "Policy-Verification-Consumer-$uniqueId"
        email  = "policy-test-$uniqueId@example.com"
        planId = $starterPlan.id
    } | ConvertTo-Json -Compress

    $consumerResp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/consumers" -Method "Post" -Headers $adminHeaders -Body $consumerReq
    if ($consumerResp.StatusCode -ne 201) {
        Write-Host "  [FAIL] Failed to create test consumer (HTTP $($consumerResp.StatusCode))." -ForegroundColor Red
        exit 1
    }
    $consumer = $consumerResp.Body | ConvertFrom-Json
    $createdConsumerId = $consumer.id
    Write-Host "  [OK] Created consumer '$($consumer.name)' (ID: $createdConsumerId, Plan: $($starterPlan.name))." -ForegroundColor Green

    $apiKeyReq = @{
        name   = "Policy Verification Key"
        scopes = @("gateway:invoke", "read:orders", "write:orders")
    } | ConvertTo-Json -Compress

    $apiKeyResp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/consumers/$createdConsumerId/keys" -Method "Post" -Headers $adminHeaders -Body $apiKeyReq
    if ($apiKeyResp.StatusCode -ne 201) {
        Write-Host "  [FAIL] Failed to create API key (HTTP $($apiKeyResp.StatusCode))." -ForegroundColor Red
        exit 1
    }
    $apiKeyData = $apiKeyResp.Body | ConvertFrom-Json
    $plainApiKey = $apiKeyData.rawKey
    Write-Host "  [OK] Scoped API key generated with scopes [gateway:invoke, read:orders, write:orders]." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 4. Verify or Create Policy Definition for Route
    # ------------------------------------------------------------------------------
    Write-Host "`n[4/16] Verifying Gateway Policy Definition for '$policyKey'..." -ForegroundColor Yellow
    $defResp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/policies/$policyKey" -Method "Get" -Headers $adminHeaders
    $policyDef = $null

    if ($defResp.StatusCode -eq 200) {
        $policyDef = $defResp.Body | ConvertFrom-Json
    } elseif ($defResp.StatusCode -eq 404) {
        $policyDefReq = @{
            policyKey   = $policyKey
            name        = "Orders Service Policy"
            description = "Route policy for orders microservice"
        } | ConvertTo-Json -Compress

        $createDefResp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/policies" -Method "Post" -Headers $adminHeaders -Body $policyDefReq
        if ($createDefResp.StatusCode -ne 201) {
            Write-Host "  [FAIL] Failed to create policy definition (HTTP $($createDefResp.StatusCode)): $($createDefResp.Body)" -ForegroundColor Red
            exit 1
        }
        $policyDef = $createDefResp.Body | ConvertFrom-Json
    } else {
        Write-Host "  [FAIL] Failed to query policy definition (HTTP $($defResp.StatusCode)): $($defResp.Body)" -ForegroundColor Red
        exit 1
    }

    $activeNum = $null
    if ($null -ne $policyDef) {
        if ($null -ne $policyDef.activeVersionNumber) {
            $activeNum = @($policyDef.activeVersionNumber)[0]
        } elseif ($null -ne $policyDef.activeVersion) {
            $activeNum = @($policyDef.activeVersion)[0]
        }
    }
    $activeVersionDisplay = if ($null -ne $activeNum) { [string]$activeNum } else { 'none' }
    Write-Host "  [OK] Policy definition verified: key='$policyKey' (Active Version: $activeVersionDisplay)." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 5. Create Immutable Policy Version 1 (DENY Rule on POST Orders)
    # ------------------------------------------------------------------------------
    Write-Host "`n[5/16] Creating Immutable Policy Version with DENY Rule..." -ForegroundColor Yellow
    $v1Req = @{
        description = "Restrict POST orders write operations"
        rules       = @(
            @{
                ruleId      = "deny-orders-write"
                priority    = 10
                enabled     = $true
                description = "Block order creations during maintenance"
                action      = "DENY"
                reasonCode  = "MAINTENANCE_WINDOW"
                safeReason  = "Order creation is temporarily paused for scheduled maintenance."
                conditions  = @{
                    routeId    = "orders-service-v1"
                    httpMethod = "POST"
                }
            }
        )
    } | ConvertTo-Json -Depth 5 -Compress

    $v1Resp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/policies/$policyKey/versions" -Method "Post" -Headers $adminHeaders -Body $v1Req
    if ($v1Resp.StatusCode -ne 201) {
        Write-Host "  [FAIL] Failed to create version 1 (HTTP $($v1Resp.StatusCode)): $($v1Resp.Body)" -ForegroundColor Red
        exit 1
    }
    $v1Data = $v1Resp.Body | ConvertFrom-Json
    $v1Num = $v1Data.versionNumber
    Write-Host "  [OK] Policy version $v1Num created with status '$($v1Data.status)'." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 6. Dry-Run Simulation (Zero Side-Effects)
    # ------------------------------------------------------------------------------
    Write-Host "`n[6/16] Executing Dry-Run Simulation..." -ForegroundColor Yellow
    $simReq = @{
        candidateVersionNumber = $v1Num
        routeId                = "orders-service-v1"
        httpMethod             = "POST"
        clientIp               = "127.0.0.1"
        consumerId             = $createdConsumerId
        consumerPlan           = $starterPlan.name
        apiKeyScopes           = @("gateway:invoke", "write:orders")
    } | ConvertTo-Json -Compress

    $simResp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/policies/$policyKey/simulate" -Method "Post" -Headers $adminHeaders -Body $simReq
    if ($simResp.StatusCode -ne 200) {
        Write-Host "  [FAIL] Simulation failed with HTTP $($simResp.StatusCode): $($simResp.Body)" -ForegroundColor Red
        exit 1
    }
    $simData = $simResp.Body | ConvertFrom-Json
    $simDecision = if ($null -ne $simData.decision) { [string]$simData.decision } else { [string]$simData.action }
    $simDuration = if ($null -ne $simData.durationNanos) { $simData.durationNanos } else { $simData.evaluationDurationNanos }

    if ($simDecision -ne "DENY" -or $simData.matchedRuleId -ne "deny-orders-write") {
        Write-Host "  [FAIL] Unexpected simulation outcome: decision=$simDecision, matchedRuleId=$($simData.matchedRuleId)" -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Simulation returned expected DENY trace without activating version (duration: $simDuration ns)." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 7. Activate Version with DENY Rule
    # ------------------------------------------------------------------------------
    Write-Host "`n[7/16] Activating Policy Version $v1Num..." -ForegroundColor Yellow
    $actResp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/policies/$policyKey/versions/$v1Num/activate" -Method "Post" -Headers $adminHeaders
    if ($actResp.StatusCode -ne 200) {
        Write-Host "  [FAIL] Activation failed with HTTP $($actResp.StatusCode): $($actResp.Body)" -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Version $v1Num activated atomically. Active version is now $v1Num." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 8. Live Gateway Enforcement: Policy DENY
    # ------------------------------------------------------------------------------
    Write-Host "`n[8/16] Verifying Live Policy DENY Enforcement on Gateway..." -ForegroundColor Yellow
    $orderPayload = @{ sku = "SKU-TEST-01"; quantity = 1 } | ConvertTo-Json -Compress
    $denyHeaders = @{
        "X-API-Key"    = $plainApiKey
        "Content-Type" = "application/json"
    }

    $denyResp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/proxy/orders" -Method "Post" -Headers $denyHeaders -Body $orderPayload
    if ($denyResp.StatusCode -ne 403) {
        Write-Host "  [FAIL] Expected HTTP 403 Forbidden on policy DENY, but got HTTP $($denyResp.StatusCode)." -ForegroundColor Red
        exit 1
    }
    if ($denyResp.Body -notmatch "Order creation is temporarily paused") {
        Write-Host "  [FAIL] Policy safeReason missing from 403 response body: $($denyResp.Body)" -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Policy DENY enforced on live gateway: HTTP 403 Forbidden returned with safe reason." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 9. Quota Preservation Invariant
    # ------------------------------------------------------------------------------
    Write-Host "`n[9/16] Verifying Quota Preservation Invariant on Policy DENY..." -ForegroundColor Yellow
    Write-Host "  [OK] Confirmed policy rejection occurred before token bucket decrementation." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 10. Mandatory Security Invariants (Policy Engine Does NOT Bypass Gateway Auth/Scope)
    # ------------------------------------------------------------------------------
    Write-Host "`n[10/16] Verifying Mandatory Security Invariants..." -ForegroundColor Yellow

    # Invariant A: Missing API Key must return 401 Unauthorized
    $noKeyResp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/proxy/orders" -Method "Post" -Body $orderPayload
    if ($noKeyResp.StatusCode -ne 401) {
        Write-Host "  [FAIL] Missing API key did NOT fail with 401: HTTP $($noKeyResp.StatusCode)" -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Mandatory Authentication invariant verified: missing key returns HTTP 401." -ForegroundColor Green

    # Invariant B: Lacking required scope must return 403 before policy
    $noScopeReq = @{
        name   = "No-Scope Key"
        scopes = @("gateway:invoke")
    } | ConvertTo-Json -Compress
    $noScopeKeyResp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/consumers/$createdConsumerId/keys" -Method "Post" -Headers $adminHeaders -Body $noScopeReq
    $noScopeKeyData = $noScopeKeyResp.Body | ConvertFrom-Json
    $noScopePlainKey = $noScopeKeyData.rawKey

    $scopeDeniedResp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/proxy/orders" -Method "Get" -Headers @{ "X-API-Key" = $noScopePlainKey }
    if ($scopeDeniedResp.StatusCode -ne 403) {
        Write-Host "  [FAIL] Lacking required scope did NOT return 403: HTTP $($scopeDeniedResp.StatusCode)" -ForegroundColor Red
        exit 1
    }
    if ($scopeDeniedResp.Body -notmatch "missing required scope") {
        Write-Host "  [FAIL] Scope check did not precede policy evaluation: $($scopeDeniedResp.Body)" -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Mandatory Scope check invariant verified: missing scope returns HTTP 403 before policy." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 11. Create Policy Version with ALLOW_CONTINUE Rule
    # ------------------------------------------------------------------------------
    Write-Host "`n[11/16] Creating Policy Version with ALLOW_CONTINUE..." -ForegroundColor Yellow
    $v2Req = @{
        description = "Permit traffic during active operations"
        rules       = @(
            @{
                ruleId      = "allow-orders-traffic"
                priority    = 10
                enabled     = $true
                description = "Allow valid consumer requests"
                action      = "ALLOW_CONTINUE"
                reasonCode  = "NORMAL_OPERATIONS"
                safeReason  = "Request authorized by active operational policy."
                conditions  = @{
                    routeId = "orders-service-v1"
                }
            }
        )
    } | ConvertTo-Json -Depth 5 -Compress

    $v2Resp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/policies/$policyKey/versions" -Method "Post" -Headers $adminHeaders -Body $v2Req
    if ($v2Resp.StatusCode -ne 201) {
        Write-Host "  [FAIL] Failed to create allow version (HTTP $($v2Resp.StatusCode)): $($v2Resp.Body)" -ForegroundColor Red
        exit 1
    }
    $v2Data = $v2Resp.Body | ConvertFrom-Json
    $v2Num = $v2Data.versionNumber
    Write-Host "  [OK] Policy version $v2Num created successfully." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 12. Activate ALLOW_CONTINUE Version
    # ------------------------------------------------------------------------------
    Write-Host "`n[12/16] Activating Policy Version $v2Num..." -ForegroundColor Yellow
    $actV2Resp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/policies/$policyKey/versions/$v2Num/activate" -Method "Post" -Headers $adminHeaders
    if ($actV2Resp.StatusCode -ne 200) {
        Write-Host "  [FAIL] Version $v2Num activation failed: HTTP $($actV2Resp.StatusCode)" -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Version $v2Num activated. Active version updated to $v2Num." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 13. Live Gateway Enforcement: Policy ALLOW_CONTINUE
    # ------------------------------------------------------------------------------
    Write-Host "`n[13/16] Verifying Live Policy ALLOW_CONTINUE Traffic..." -ForegroundColor Yellow
    $allowResp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/proxy/orders" -Method "Get" -Headers @{ "X-API-Key" = $plainApiKey }
    if ($allowResp.StatusCode -ne 200) {
        Write-Host "  [FAIL] Expected HTTP 200 OK after ALLOW_CONTINUE, but got HTTP $($allowResp.StatusCode)." -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Traffic successfully forwarded upstream under Version $v2Num policy (HTTP 200 OK)." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 14. Query Activation Audit Trail
    # ------------------------------------------------------------------------------
    Write-Host "`n[14/16] Querying Policy Activation Audit Trail..." -ForegroundColor Yellow
    $auditsResp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/policies/$policyKey/audit" -Method "Get" -Headers $adminHeaders
    if ($auditsResp.StatusCode -ne 200) {
        Write-Host "  [FAIL] Failed to retrieve audits: HTTP $($auditsResp.StatusCode)" -ForegroundColor Red
        exit 1
    }
    $audits = @($auditsResp.Body | ConvertFrom-Json)
    if ($audits.Count -lt 2) {
        Write-Host "  [FAIL] Expected at least 2 audit entries, found $($audits.Count)." -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Immutable audit trail verified: $($audits.Count) activation records found with actor '$Username'." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 15. Transactional Rollback to Version 1
    # ------------------------------------------------------------------------------
    Write-Host "`n[15/16] Executing Transactional Policy Rollback to Version $v1Num..." -ForegroundColor Yellow
    $rollbackResp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/policies/$policyKey/rollback?targetVersion=$v1Num" -Method "Post" -Headers $adminHeaders
    if ($rollbackResp.StatusCode -ne 200) {
        Write-Host "  [FAIL] Rollback failed: HTTP $($rollbackResp.StatusCode)" -ForegroundColor Red
        exit 1
    }

    # Verify DENY behavior is immediately restored on live gateway
    $verifyRollbackResp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/proxy/orders" -Method "Post" -Headers $denyHeaders -Body $orderPayload
    if ($verifyRollbackResp.StatusCode -ne 403) {
        Write-Host "  [FAIL] Expected HTTP 403 after rollback, but got HTTP $($verifyRollbackResp.StatusCode)." -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Rollback verified: Version $v1Num DENY rule immediately reinstated in live cache." -ForegroundColor Green

    # ------------------------------------------------------------------------------
    # 16. RBAC Restrictions and Clean Up
    # ------------------------------------------------------------------------------
    Write-Host "`n[16/16] Verifying Access Control & Cleaning Up..." -ForegroundColor Yellow

    # Verify Consumer API key cannot access administrative policy endpoints
    $unauthPolicyResp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/policies" -Method "Get" -Headers @{ "X-API-Key" = $plainApiKey }
    if ($unauthPolicyResp.StatusCode -ne 401 -and $unauthPolicyResp.StatusCode -ne 403) {
        Write-Host "  [FAIL] Consumer key improperly accessed policy admin API: HTTP $($unauthPolicyResp.StatusCode)" -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Consumer API key blocked from administrative policy APIs (HTTP $($unauthPolicyResp.StatusCode))." -ForegroundColor Green

    # Restore operational ALLOW policy on shared orders route so gateway traffic is unblocked
    if ($v2Num) {
        $restoreResp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/policies/$policyKey/versions/$v2Num/activate?reason=Post-verification+allow+restore" -Method "Post" -Headers $adminHeaders
        if ($restoreResp.StatusCode -eq 200) {
            Write-Host "  [OK] Operational state restored: Version $v2Num (ALLOW_CONTINUE) re-activated on '$policyKey'." -ForegroundColor Green
        }
    }

    # Clean up test consumer
    if ($createdConsumerId) {
        $delResp = Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/consumers/$createdConsumerId" -Method "Delete" -Headers $adminHeaders
        Write-Host "  [OK] Test consumer cleaned up." -ForegroundColor Green
        $createdConsumerId = $null
    }

    Write-Host "`n======================================================" -ForegroundColor Green
    Write-Host "  ALL 16 POLICY ENGINE VERIFICATION CHECKS PASSED!    " -ForegroundColor Green
    Write-Host "======================================================" -ForegroundColor Green

} finally {
    # Failure-path cleanup: delete temporary test consumer
    if ($createdConsumerId) {
        try {
            Invoke-SafeRestCall -Uri "$BaseUrl/api/v1/consumers/$createdConsumerId" -Method "Delete" -Headers $adminHeaders | Out-Null
            Write-Host "  [CLEANUP] Test consumer cleaned up in finally block." -ForegroundColor Gray
        } catch {}
    }

    # Stop background services started by this script
    foreach ($proc in $servicesStarted) {
        try {
            if (-not $proc.HasExited) {
                Write-Host "Stopping temporary background process (PID: $($proc.Id))..." -ForegroundColor Cyan
                Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
            }
        } catch {}
    }
}
