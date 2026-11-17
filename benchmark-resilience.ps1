param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$ApiKey,
    [int]$Iterations = 100
)

$ErrorActionPreference = "Stop"

Write-Host "======================================================" -ForegroundColor Cyan
Write-Host " Gatekeeper Resilience Benchmark (Fast Rejection)     " -ForegroundColor Green
Write-Host "======================================================" -ForegroundColor Cyan

if (-not $ApiKey) {
    Write-Host "Usage: ./benchmark-resilience.ps1 -ApiKey <RAW_API_KEY> [-Iterations 100]" -ForegroundColor Yellow
    exit 0
}

function Measure-Calls {
    param([string]$Url, [hashtable]$Headers, [int]$Count)
    $latencies = @()
    for ($i = 0; $i -lt $Count; $i++) {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            $req = [System.Net.HttpWebRequest]::Create($Url)
            $req.Method = "GET"
            $req.Timeout = 10000
            foreach ($k in $Headers.Keys) { $req.Headers.Add($k, [string]$Headers[$k]) }
            $resp = $req.GetResponse()
            $resp.Close()
        } catch [System.Net.WebException] {
            if ($_.Exception.Response) { $_.Exception.Response.Close() }
        }
        $sw.Stop()
        $latencies += $sw.Elapsed.TotalMilliseconds
    }

    $sorted = $latencies | Sort-Object
    $avg = ($latencies | Measure-Object -Average).Average
    $p50 = $sorted[[Math]::Floor($Count * 0.50)]
    $p95 = $sorted[[Math]::Floor($Count * 0.95)]
    $p99 = $sorted[[Math]::Floor($Count * 0.99)]
    $min = $sorted[0]
    $max = $sorted[-1]

    return @{
        Avg = [Math]::Round($avg, 2)
        P50 = [Math]::Round($p50, 2)
        P95 = [Math]::Round($p95, 2)
        P99 = [Math]::Round($p99, 2)
        Min = [Math]::Round($min, 2)
        Max = [Math]::Round($max, 2)
    }
}

$headers = @{ "X-API-Key" = $ApiKey }
Write-Host "Measuring $Iterations calls to $BaseUrl/api/v1/proxy/orders..." -ForegroundColor Cyan
$stats = Measure-Calls -Url "$BaseUrl/api/v1/proxy/orders" -Headers $headers -Count $Iterations

Write-Host "`nLatency Results (ms):" -ForegroundColor Green
Write-Host "  Min: $($stats.Min) ms"
Write-Host "  Avg: $($stats.Avg) ms"
Write-Host "  P50: $($stats.P50) ms"
Write-Host "  P95: $($stats.P95) ms"
Write-Host "  P99: $($stats.P99) ms"
Write-Host "  Max: $($stats.Max) ms"
