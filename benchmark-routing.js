/**
 * Gatekeeper Routing Latency Benchmark Script
 * Usage: node benchmark-routing.js <API_KEY> [BASE_URL] [REQUESTS]
 */

const apiKey = process.argv[2] || process.env.GATEKEEPER_BENCHMARK_API_KEY;
const baseUrl = process.argv[3] || 'http://localhost:8080';
const totalRequests = parseInt(process.argv[4] || '50', 10);

if (!apiKey) {
    console.error('Error: API key required.');
    console.error('Usage: node benchmark-routing.js <API_KEY> [BASE_URL] [REQUESTS]');
    process.exit(1);
}

async function runBenchmark() {
    console.log(`Starting benchmark against ${baseUrl}/api/v1/proxy/orders (${totalRequests} requests)...`);
    const latencies = [];
    let success = 0;
    let failed = 0;

    for (let i = 0; i < totalRequests; i++) {
        const start = performance.now();
        try {
            const res = await fetch(`${baseUrl}/api/v1/proxy/orders`, {
                headers: { 'X-API-Key': apiKey }
            });
            const elapsed = performance.now() - start;
            latencies.push(elapsed);
            if (res.status === 200) {
                success++;
            } else {
                failed++;
            }
        } catch (err) {
            failed++;
        }
    }

    latencies.sort((a, b) => a - b);
    const avg = latencies.reduce((sum, v) => sum + v, 0) / latencies.length;
    const p50 = latencies[Math.floor(latencies.length * 0.50)];
    const p95 = latencies[Math.floor(latencies.length * 0.95)];
    const p99 = latencies[Math.floor(latencies.length * 0.99)];
    const min = latencies[0];
    const max = latencies[latencies.length - 1];

    console.log('\n--- Benchmark Results ---');
    console.log(`Success: ${success}, Failed: ${failed}`);
    console.log(`Min:  ${min.toFixed(2)} ms`);
    console.log(`Avg:  ${avg.toFixed(2)} ms`);
    console.log(`p50:  ${p50.toFixed(2)} ms`);
    console.log(`p95:  ${p95.toFixed(2)} ms`);
    console.log(`p99:  ${p99.toFixed(2)} ms`);
    console.log(`Max:  ${max.toFixed(2)} ms`);
}

runBenchmark();
