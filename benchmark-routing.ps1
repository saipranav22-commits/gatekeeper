param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$ApiKey = $env:GATEKEEPER_BENCHMARK_API_KEY,
    [int]$Requests = 50
)

$ErrorActionPreference = "Stop"

Write-Host "======================================================" -ForegroundColor Cyan
Write-Host " Gatekeeper Real API Routing Latency Benchmark        " -ForegroundColor Green
Write-Host "======================================================" -ForegroundColor Cyan

if (-not $ApiKey) {
    Write-Host "No API key provided via -ApiKey or GATEKEEPER_BENCHMARK_API_KEY." -ForegroundColor Yellow
    Write-Host "Usage: .\benchmark-routing.ps1 -ApiKey 'gk_live_...' -Requests 50" -ForegroundColor Gray
    exit 1
}

Write-Host "Executing $Requests requests to $BaseUrl/api/v1/proxy/orders..." -ForegroundColor Cyan

$latencies = [System.Collections.Generic.List[double]]::new()
$successCount = 0
$failCount = 0

for ($i = 1; $i -le $Requests; $i++) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $req = [System.Net.HttpWebRequest]::Create("$BaseUrl/api/v1/proxy/orders")
    $req.Method = "GET"
    $req.KeepAlive = $true
    $req.Headers.Add("X-API-Key", $ApiKey)

    try {
        $resp = $req.GetResponse()
        $sw.Stop()
        $resp.Close()
        $latencies.Add($sw.Elapsed.TotalMilliseconds)
        $successCount++
    } catch [System.Net.WebException] {
        $sw.Stop()
        if ($_.Exception.Response) {
            $code = [int]$_.Exception.Response.StatusCode
            $_.Exception.Response.Close()
            $latencies.Add($sw.Elapsed.TotalMilliseconds)
            if ($code -eq 200) { $successCount++ } else { $failCount++ }
        } else {
            $failCount++
        }
    }
}

if ($latencies.Count -gt 0) {
    $sorted = $latencies | Sort-Object
    $count = $sorted.Count
    $avg = ($sorted | Measure-Object -Average).Average
    $min = $sorted[0]
    $max = $sorted[-1]
    $p50 = $sorted[[int]($count * 0.50)]
    $p95 = $sorted[[int]($count * 0.95)]
    $p99 = $sorted[[int]($count * 0.99)]

    Write-Host "`nBenchmark Results ($Requests requests):" -ForegroundColor Green
    Write-Host "  Success: $successCount, Failed: $failCount" -ForegroundColor Cyan
    Write-Host "  Min:     $([math]::Round($min, 2)) ms" -ForegroundColor Gray
    Write-Host "  Avg:     $([math]::Round($avg, 2)) ms" -ForegroundColor Gray
    Write-Host "  p50:     $([math]::Round($p50, 2)) ms" -ForegroundColor Gray
    Write-Host "  p95:     $([math]::Round($p95, 2)) ms" -ForegroundColor Gray
    Write-Host "  p99:     $([math]::Round($p99, 2)) ms" -ForegroundColor Gray
    Write-Host "  Max:     $([math]::Round($max, 2)) ms" -ForegroundColor Gray
}
