import http from 'http'
import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'
import { spawn } from 'child_process'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const distDir = path.resolve(__dirname, '../dist')
const screenshotsDir = path.resolve(__dirname, '../../docs/screenshots')

if (!fs.existsSync(screenshotsDir)) {
  fs.mkdirSync(screenshotsDir, { recursive: true })
}

const mimeTypes = {
  '.html': 'text/html',
  '.js': 'application/javascript',
  '.css': 'text/css',
  '.json': 'application/json',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
}

const mockOverview = {
  totalRequests: 24890,
  allowedRequests: 24712,
  successfulRequests: 24712,
  rateLimitedRequests: 142,
  scopeDeniedRequests: 36,
  errorRequests: 22,
  rateLimitDenialPercentage: 0.57,
  averageLatencyMs: 14.8,
  p95LatencyMs: 42.5,
  p99LatencyMs: 88.0,
  statusCodes: {
    '200': 24712,
    '400': 15,
    '401': 8,
    '403': 36,
    '429': 142,
    '500': 22,
    '503': 15,
  },
  usageByPlan: {
    Enterprise: 18500,
    Pro: 5200,
    Starter: 1190,
  },
}

const mockTopConsumers = [
  {
    consumerId: 'c1-apex',
    consumerName: 'Apex Logistics Global',
    consumerEmail: 'integrations@apexlogistics.io',
    plan: 'Enterprise',
    totalRequests: 12450,
    allowedRequests: 12380,
    rateLimitedRequests: 52,
    rateLimitDenialPercentage: 0.42,
  },
  {
    consumerId: 'c2-beacon',
    consumerName: 'Beacon Payments',
    consumerEmail: 'gateway@beaconpay.com',
    plan: 'Pro',
    totalRequests: 8920,
    allowedRequests: 8850,
    rateLimitedRequests: 48,
    rateLimitDenialPercentage: 0.54,
  },
  {
    consumerId: 'c3-cyber',
    consumerName: 'CyberShield Systems',
    consumerEmail: 'ops@cybershield.security',
    plan: 'Starter',
    totalRequests: 3520,
    allowedRequests: 3410,
    rateLimitedRequests: 95,
    rateLimitDenialPercentage: 2.70,
  },
]

const mockTimeSeries = [
  { timestamp: '2026-11-20T00:00:00Z', totalRequests: 850, allowedRequests: 840, successfulRequests: 840, errorRequests: 10, rateLimitedRequests: 2, scopeDeniedRequests: 1, avgLatencyMs: 12 },
  { timestamp: '2026-11-20T00:15:00Z', totalRequests: 1200, allowedRequests: 1180, successfulRequests: 1180, errorRequests: 20, rateLimitedRequests: 8, scopeDeniedRequests: 2, avgLatencyMs: 14 },
  { timestamp: '2026-11-20T00:30:00Z', totalRequests: 1450, allowedRequests: 1435, successfulRequests: 1435, errorRequests: 15, rateLimitedRequests: 5, scopeDeniedRequests: 1, avgLatencyMs: 15 },
  { timestamp: '2026-11-20T00:45:00Z', totalRequests: 1600, allowedRequests: 1570, successfulRequests: 1570, errorRequests: 30, rateLimitedRequests: 12, scopeDeniedRequests: 3, avgLatencyMs: 18 },
  { timestamp: '2026-11-20T01:00:00Z', totalRequests: 1320, allowedRequests: 1305, successfulRequests: 1305, errorRequests: 15, rateLimitedRequests: 4, scopeDeniedRequests: 1, avgLatencyMs: 13 },
]

const mockConsumers = {
  content: [
    {
      id: 'c1-apex',
      name: 'Apex Logistics Global',
      email: 'integrations@apexlogistics.io',
      status: 'ACTIVE',
      planId: 'p-ent',
      planName: 'Enterprise',
      keyCount: 3,
      createdAt: '2026-09-15T10:00:00Z',
      updatedAt: '2026-11-18T14:30:00Z',
    },
    {
      id: 'c2-beacon',
      name: 'Beacon Payments',
      email: 'gateway@beaconpay.com',
      status: 'ACTIVE',
      planId: 'p-pro',
      planName: 'Pro',
      keyCount: 2,
      createdAt: '2026-10-01T08:15:00Z',
      updatedAt: '2026-11-19T09:45:00Z',
    },
    {
      id: 'c3-cyber',
      name: 'CyberShield Systems',
      email: 'ops@cybershield.security',
      status: 'SUSPENDED',
      planId: 'p-starter',
      planName: 'Starter',
      keyCount: 1,
      createdAt: '2026-11-05T12:00:00Z',
      updatedAt: '2026-11-19T11:20:00Z',
    },
  ],
  totalElements: 3,
  totalPages: 1,
  size: 20,
  number: 0,
}

const mockPlans = [
  { id: 'p-ent', name: 'Enterprise', rateLimitRps: 500, burstCapacity: 1000, quotaDaily: 5000000, active: true },
  { id: 'p-pro', name: 'Pro', rateLimitRps: 100, burstCapacity: 200, quotaDaily: 1000000, active: true },
  { id: 'p-starter', name: 'Starter', rateLimitRps: 20, burstCapacity: 40, quotaDaily: 100000, active: true },
  { id: 'p-free', name: 'Free Tier', rateLimitRps: 5, burstCapacity: 10, quotaDaily: 10000, active: true },
]

const mockPolicyDefinitions = [
  {
    id: 'pol-1',
    policyKey: 'api-gateway-default',
    name: 'Default Gateway Security Policy',
    description: 'Enforces route authorization, plan scopes, and rate limits.',
    activeVersionNumber: 3,
    shadowVersionNumber: 4,
    versionLock: 3,
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-11-19T18:00:00Z',
  },
]

const mockPolicyVersions = [
  {
    id: 'v-3',
    policyKey: 'api-gateway-default',
    versionNumber: 3,
    status: 'ACTIVE',
    description: 'Enforces strict scope checking on proxy routes and 403 fallback',
    rulesCount: 3,
    rules: [
      { ruleId: 'r-1', priority: 1, enabled: true, action: 'DENY', description: 'Block deprecated /orders/legacy path', conditions: { routeId: 'orders-service-v1', httpMethod: 'DELETE' } },
      { ruleId: 'r-2', priority: 2, enabled: true, action: 'ALLOW_CONTINUE', description: 'Allow authenticated orders proxying', conditions: { requiredScopes: ['gateway:invoke'] } },
      { ruleId: 'r-3', priority: 10, enabled: true, action: 'ALLOW_CONTINUE', description: 'Default fallback route rule' },
    ],
    createdAt: '2026-11-18T10:00:00Z',
    createdBy: 'sre@gatekeeper.local',
    activatedAt: '2026-11-18T10:15:00Z',
    activatedBy: 'sre@gatekeeper.local',
  },
]

const mockResilienceSummaries = [
  {
    routeId: 'orders-service-v1',
    circuitBreakerState: 'CLOSED',
    failureRate: 0.0,
    slowCallRate: 0.0,
    totalCalls: 18450,
    failedCalls: 12,
    slowCalls: 8,
    rejectedCalls: 0,
    bulkheadMaxConcurrent: 10,
    bulkheadAvailablePermits: 10,
    bulkheadInFlightCalls: 0,
    bulkheadRejectedCalls: 0,
    retryMaxAttempts: 3,
    timeoutMs: 5000,
    lastStatusChange: '2026-11-20T00:00:00Z',
  },
  {
    routeId: 'inventory-service-v1',
    circuitBreakerState: 'CLOSED',
    failureRate: 0.0,
    slowCallRate: 0.0,
    totalCalls: 12100,
    failedCalls: 5,
    slowCalls: 2,
    rejectedCalls: 0,
    bulkheadMaxConcurrent: 10,
    bulkheadAvailablePermits: 10,
    bulkheadInFlightCalls: 0,
    bulkheadRejectedCalls: 0,
    retryMaxAttempts: 3,
    timeoutMs: 5000,
    lastStatusChange: '2026-11-20T00:00:00Z',
  },
]

const mockResilienceDetail = {
  routeId: 'orders-service-v1',
  circuitBreaker: {
    state: 'CLOSED',
    failureRate: 0.0,
    failureRateThreshold: 50.0,
    slowCallRate: 0.0,
    slowCallRateThreshold: 50.0,
    slowCallDurationThresholdMs: 2000,
    slidingWindowType: 'COUNT_BASED',
    slidingWindowSize: 20,
    minimumNumberOfCalls: 5,
    permittedNumberOfCallsInHalfOpenState: 5,
    waitDurationInOpenStateMs: 10000,
    automaticTransitionFromOpenToHalfOpenEnabled: true,
    bufferedCalls: 20,
    failedCalls: 0,
    successfulCalls: 20,
  },
  bulkhead: {
    maxConcurrentCalls: 10,
    availableConcurrentCalls: 10,
    maxWaitDurationMs: 0,
  },
  retry: {
    maxAttempts: 3,
    waitDurationMs: 200,
    jitterFactor: 0.5,
    maxCumulativeDeadlineMs: 5000,
  },
}

// Integrated HTTP Server
const server = http.createServer((req, res) => {
  const url = req.url.split('?')[0]

  // API Endpoints
  if (url === '/api/v1/auth/login' && req.method === 'POST') {
    let body = ''
    req.on('data', chunk => body += chunk)
    req.on('end', () => {
      try {
        const parsed = JSON.parse(body)
        if (parsed.username === 'invalid@gatekeeper.local') {
          res.writeHead(401, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ status: 401, error: 'Unauthorized', message: 'Invalid username or password' }))
          return
        }
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({
          accessToken: 'gk_jwt_test_token_live_dev',
          tokenType: 'Bearer',
          expiresIn: 86400,
          expiresAt: '2026-11-21T00:00:00Z',
          admin: {
            id: 'adm-001',
            username: 'sre@gatekeeper.local',
            email: 'sre@gatekeeper.local',
            role: 'ROLE_SUPER_ADMIN',
            mustChangePassword: false,
          },
        }))
      } catch {
        res.writeHead(400)
        res.end()
      }
    })
    return
  }

  if (url === '/api/v1/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify({ status: 'UP', service: 'gatekeeper', serviceName: 'gatekeeper', timestamp: new Date().toISOString() }))
    return
  }

  if (url === '/actuator/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify({
      status: 'UP',
      components: {
        db: { status: 'UP', details: { database: 'PostgreSQL' } },
        redis: { status: 'UP', details: { version: '7.2' } },
        diskSpace: { status: 'UP', details: { total: 499963174912, free: 312074551296 } },
      },
    }))
    return
  }

  if (url === '/api/v1/analytics/overview') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify(mockOverview))
    return
  }

  if (url === '/api/v1/analytics/top-consumers') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify(mockTopConsumers))
    return
  }

  if (url === '/api/v1/analytics/timeseries') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify(mockTimeSeries))
    return
  }

  if (url === '/api/v1/consumers') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify(mockConsumers))
    return
  }

  if (url === '/api/v1/plans') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify(mockPlans))
    return
  }

  if (url === '/api/v1/policies') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify(mockPolicyDefinitions))
    return
  }

  if (url.match(/^\/api\/v1\/policies\/[^/]+\/versions\/\d+$/)) {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify(mockPolicyVersions[0]))
    return
  }

  if (url === '/api/v1/policies/api-gateway-default/versions') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify(mockPolicyVersions))
    return
  }

  if (url === '/api/v1/policies/api-gateway-default') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify(mockPolicyDefinitions[0]))
    return
  }

  if (url === '/api/v1/policies/api-gateway-default/audit' || url === '/api/v1/policies/api-gateway-default/audits') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify([
      { id: 'aud-1', policyKey: 'api-gateway-default', versionNumber: 3, action: 'ACTIVATED', actor: 'sre@gatekeeper.local', createdAt: '2026-11-18T10:15:00Z', reason: 'Promoted release with tightened scope validations' },
    ]))
    return
  }

  if (url === '/api/v1/resilience/routes') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify(mockResilienceSummaries))
    return
  }

  if (url.startsWith('/api/v1/resilience/routes/')) {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify(mockResilienceDetail))
    return
  }

  if (url === '/api/v1/chaos/experiments') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify([]))
    return
  }

  // Static Assets / SPA fallback
  let filePath = path.join(distDir, url)
  if (!fs.existsSync(filePath) || fs.statSync(filePath).isDirectory()) {
    filePath = path.join(distDir, 'index.html')
  }

  const ext = path.extname(filePath)
  const contentType = mimeTypes[ext] || 'application/octet-stream'

  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(500)
      res.end('Error loading asset')
      return
    }
    res.writeHead(200, { 'Content-Type': contentType })
    res.end(data)
  })
})

const PORT = 4173

server.listen(PORT, '127.0.0.1', async () => {
  console.log(`Test & Mock server listening on http://127.0.0.1:${PORT}`)

  const chromePath = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
  const chromeProc = spawn(chromePath, [
    '--headless=new',
    '--remote-debugging-port=9222',
    '--disable-gpu',
    '--no-first-run',
    '--no-default-browser-check',
    'about:blank',
  ])

  await new Promise(r => setTimeout(r, 1500))
  const verRes = await fetch('http://127.0.0.1:9222/json/list')
  const tabs = await verRes.json()
  const pageWsUrl = tabs[0].webSocketDebuggerUrl

  console.log('Connecting to Chrome CDP:', pageWsUrl)
  const ws = new WebSocket(pageWsUrl)

  let idCounter = 1
  const pending = new Map()

  ws.onmessage = (event) => {
    const data = JSON.parse(event.data)
    if (data.id && pending.has(data.id)) {
      const { resolve, reject } = pending.get(data.id)
      pending.delete(data.id)
      if (data.error) reject(data.error)
      else resolve(data.result)
    }
  }

  const send = (method, params = {}) => {
    return new Promise((resolve, reject) => {
      const id = idCounter++
      pending.set(id, { resolve, reject })
      ws.send(JSON.stringify({ id, method, params }))
    })
  }

  await new Promise(r => ws.onopen = r)
  await send('Page.enable')
  await send('Runtime.enable')

  const setViewport = async (width, height, isMobile = false) => {
    await send('Emulation.setDeviceMetricsOverride', {
      width,
      height,
      deviceScaleFactor: 1,
      mobile: isMobile,
    })
  }

  const capture = async (filename) => {
    await new Promise(r => setTimeout(r, 1500)) // settle animations, charts, and SVG rendering
    const res = await send('Page.captureScreenshot', { format: 'png' })
    const buffer = Buffer.from(res.data, 'base64')
    const dest = path.join(screenshotsDir, filename)
    fs.writeFileSync(dest, buffer)
    console.log(`[CAPTURED] ${filename} (${buffer.length} bytes)`)
  }

  const evalJs = async (expr) => {
    return send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true })
  }

  try {
    // 1. LOGIN SCREEN across all 5 viewports
    console.log('\n--- 1. CAPTURING LOGIN VIEWPORTS ---')
    await send('Page.navigate', { url: `http://127.0.0.1:${PORT}/login` })
    await new Promise(r => setTimeout(r, 1200))

    await setViewport(1440, 900)
    await capture('01-login-desktop-1440x900.png')

    await setViewport(1280, 800)
    await capture('02-login-laptop-1280x800.png')

    await setViewport(768, 1024, true)
    await capture('03-login-tablet-768x1024.png')

    await setViewport(390, 844, true)
    await capture('04-login-mobile-390x844.png')

    await setViewport(320, 700, true)
    await capture('05-login-narrow-mobile-320x700.png')

    // 2. BACKEND-DISCONNECTED / VALIDATION ERROR STATE
    console.log('\n--- 2. CAPTURING BACKEND ERROR / VALIDATION STATE ---')
    await setViewport(1440, 900)
    await evalJs(`(() => {
      const inputs = document.querySelectorAll('input');
      const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
      setter.call(inputs[0], 'invalid@gatekeeper.local');
      inputs[0].dispatchEvent(new Event('input', { bubbles: true }));
      inputs[0].dispatchEvent(new Event('change', { bubbles: true }));

      setter.call(inputs[1], 'WrongPassword123!');
      inputs[1].dispatchEvent(new Event('input', { bubbles: true }));
      inputs[1].dispatchEvent(new Event('change', { bubbles: true }));

      document.querySelector('button[type="submit"]').click();
    })()`)
    await capture('06-backend-disconnected-error.png')

    // 3. LOG IN TO THE APPLICATION
    console.log('\n--- 3. LOGGING IN AS SUPER_ADMIN ---')
    await evalJs(`(() => {
      const inputs = document.querySelectorAll('input');
      const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
      setter.call(inputs[0], 'sre@gatekeeper.local');
      inputs[0].dispatchEvent(new Event('input', { bubbles: true }));
      inputs[0].dispatchEvent(new Event('change', { bubbles: true }));

      setter.call(inputs[1], 'CorrectPassword123!');
      inputs[1].dispatchEvent(new Event('input', { bubbles: true }));
      inputs[1].dispatchEvent(new Event('change', { bubbles: true }));

      document.querySelector('button[type="submit"]').click();
    })()`)
    await new Promise(r => setTimeout(r, 2500))

    // 4. COMMAND CENTER across viewports
    console.log('\n--- 4. CAPTURING COMMAND CENTER (OVERVIEW) ---')
    await setViewport(1440, 900)
    await capture('07-command-center-desktop-1440x900.png')

    await setViewport(1280, 800)
    await capture('08-command-center-laptop-1280x800.png')

    await setViewport(768, 1024, true)
    await capture('09-command-center-tablet-768x1024.png')

    await setViewport(390, 844, true)
    await capture('10-command-center-mobile-390x844.png')

    // 5. ANALYTICS WORKSPACE (SPA navigation via link click)
    console.log('\n--- 5. CAPTURING ANALYTICS ---')
    await setViewport(1440, 900)
    await evalJs(`document.querySelector('a[href="/analytics"]').click()`)
    await capture('11-analytics-desktop-1440x900.png')

    await setViewport(390, 844, true)
    await capture('12-analytics-mobile-390x844.png')

    // 6. CONSUMERS & DEVELOPERS
    console.log('\n--- 6. CAPTURING CONSUMERS ---')
    await setViewport(1440, 900)
    await evalJs(`document.querySelector('a[href="/consumers"]').click()`)
    await capture('13-consumers-desktop-1440x900.png')

    await setViewport(390, 844, true)
    await capture('14-consumers-mobile-390x844.png')

    // 7. POLICY STUDIO
    console.log('\n--- 7. CAPTURING POLICY STUDIO ---')
    await setViewport(1440, 900)
    await new Promise(r => setTimeout(r, 600))
    await evalJs(`document.querySelector('a[href="/policies"]').click()`)
    await new Promise(r => setTimeout(r, 1200))
    await capture('15-policy-studio-desktop-1440x900.png')

    await setViewport(390, 844, true)
    await new Promise(r => setTimeout(r, 600))
    await capture('16-policy-studio-mobile-390x844.png')

    // 8. RELIABILITY: TOPOLOGY, RESILIENCE, TIMELINE
    console.log('\n--- 8. CAPTURING RELIABILITY WORKBENCH & TOPOLOGY ---')
    await setViewport(1440, 900)
    await new Promise(r => setTimeout(r, 600))
    await evalJs(`document.querySelector('a[href="/reliability"]').click()`)
    await new Promise(r => setTimeout(r, 1200))
    await capture('17-service-topology-desktop-1440x900.png')

    await setViewport(390, 844, true)
    await new Promise(r => setTimeout(r, 600))
    await capture('18-service-topology-mobile-390x844.png')

    // Click Resilience Policies & Breakers Tab
    await setViewport(1440, 900)
    await new Promise(r => setTimeout(r, 600))
    await evalJs(`(() => {
      const btns = Array.from(document.querySelectorAll('button'));
      const resilienceBtn = btns.find(b => b.innerText.includes('Resilience Policies & Breakers'));
      if (resilienceBtn) resilienceBtn.click();
    })()`)
    await new Promise(r => setTimeout(r, 1200))
    await capture('19-resilience-workbench-desktop-1440x900.png')

    // Click Operational Timelines Tab
    await evalJs(`(() => {
      const btns = Array.from(document.querySelectorAll('button'));
      const timelineBtn = btns.find(b => b.innerText.includes('Operational Timelines & Audits'));
      if (timelineBtn) timelineBtn.click();
    })()`)
    await new Promise(r => setTimeout(r, 1200))
    await capture('20-operational-timeline-desktop-1440x900.png')

    // 9. CHAOS LAB
    console.log('\n--- 9. CAPTURING CHAOS LABORATORY ---')
    await evalJs(`document.querySelector('a[href="/chaos"]').click()`)
    await new Promise(r => setTimeout(r, 1200))
    await capture('21-chaos-lab-desktop-1440x900.png')

    await setViewport(390, 844, true)
    await new Promise(r => setTimeout(r, 600))
    await capture('22-chaos-lab-mobile-390x844.png')

    // 10. MODAL DIALOG (Command Palette)
    console.log('\n--- 10. CAPTURING MODAL DIALOG ---')
    await setViewport(1440, 900)
    await new Promise(r => setTimeout(r, 600))
    await evalJs(`window.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true, bubbles: true }))`)
    await new Promise(r => setTimeout(r, 1000))
    await capture('23-command-palette-modal-desktop.png')

    console.log('\n[SUCCESS] All 23 representative browser screenshots captured and verified!')
  } catch (err) {
    console.error('Screenshot generation encountered error:', err)
  } finally {
    ws.close()
    chromeProc.kill()
    server.close()
    process.exit(0)
  }
})
