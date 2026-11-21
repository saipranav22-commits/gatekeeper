import { spawn } from 'child_process'
import http from 'http'
import fs from 'fs'
import path from 'path'

const CHROME_PATH = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const PREVIEW_URL = 'http://127.0.0.1:4173/plans'
const DEBUG_PORT = 9222
const SCREENSHOT_DIR = 'D:\\Development\\Gatekeeper-Phase9C\\docs\\screenshots\\phase-9d2'

if (!fs.existsSync(SCREENSHOT_DIR)) {
  fs.mkdirSync(SCREENSHOT_DIR, { recursive: true })
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function fetchJson(url) {
  return new Promise((resolve, reject) => {
    http.get(url, (res) => {
      let data = ''
      res.on('data', (chunk) => (data += chunk))
      res.on('end', () => {
        try {
          resolve(JSON.parse(data))
        } catch (e) {
          reject(e)
        }
      })
    }).on('error', reject)
  })
}

class CdpClient {
  constructor(wsUrl) {
    this.wsUrl = wsUrl
    this.ws = null
    this.id = 1
    this.callbacks = new Map()
  }

  async connect() {
    this.ws = new globalThis.WebSocket(this.wsUrl)
    await new Promise((resolve, reject) => {
      this.ws.onopen = resolve
      this.ws.onerror = reject
    })

    this.ws.onmessage = (event) => {
      const msg = JSON.parse(event.data)
      if (msg.id && this.callbacks.has(msg.id)) {
        const { resolve, reject } = this.callbacks.get(msg.id)
        this.callbacks.delete(msg.id)
        if (msg.error) {
          reject(new Error(msg.error.message || JSON.stringify(msg.error)))
        } else {
          resolve(msg.result)
        }
      }
    }
  }

  send(method, params = {}) {
    return new Promise((resolve, reject) => {
      const id = this.id++
      this.callbacks.set(id, { resolve, reject })
      this.ws.send(JSON.stringify({ id, method, params }))
    })
  }

  close() {
    if (this.ws) this.ws.close()
  }
}

async function run() {
  console.log('--- Phase 9D.2 UI Verification & Screenshot Capture ---')
  
  // Launch Chrome
  const chromeArgs = [
    `--remote-debugging-port=${DEBUG_PORT}`,
    '--headless=new',
    '--disable-gpu',
    '--no-sandbox',
    '--disable-dev-shm-usage',
    '--user-data-dir=C:\\Users\\Hp\\AppData\\Local\\Temp\\chrome-debug-9d2',
    PREVIEW_URL
  ]

  const chromeProc = spawn(CHROME_PATH, chromeArgs, { stdio: 'ignore' })
  await sleep(2000)

  let cdp = null
  try {
    const targets = await fetchJson(`http://127.0.0.1:${DEBUG_PORT}/json`)
    console.log(`Found ${targets.length} targets`)
    const pageTarget = targets.find((t) => t.type === 'page') || targets[0]
    if (!pageTarget || !pageTarget.webSocketDebuggerUrl) {
      throw new Error('No page target with webSocketDebuggerUrl found')
    }
    console.log('Connecting to target:', pageTarget.title, pageTarget.url)

    cdp = new CdpClient(pageTarget.webSocketDebuggerUrl)
    await cdp.connect()

    await cdp.send('Page.enable')
    await cdp.send('DOM.enable')
    await cdp.send('Runtime.enable')

    const viewports = [
      { name: '1440-desktop', width: 1440, height: 900, deviceScaleFactor: 1 },
      { name: '1280-laptop', width: 1280, height: 800, deviceScaleFactor: 1 },
      { name: '768-tablet', width: 768, height: 1024, deviceScaleFactor: 1 },
      { name: '390-mobile', width: 390, height: 844, deviceScaleFactor: 2, isMobile: true }
    ]

    const results = []

    for (const vp of viewports) {
      console.log(`\nEvaluating viewport: ${vp.name} (${vp.width}x${vp.height})...`)

      await cdp.send('Emulation.setDeviceMetricsOverride', {
        width: vp.width,
        height: vp.height,
        deviceScaleFactor: vp.deviceScaleFactor,
        mobile: !!vp.isMobile
      })

      await cdp.send('Page.navigate', { url: PREVIEW_URL })
      await sleep(1200)

      // Evaluate geometry and DOM properties
      const evalRes = await cdp.send('Runtime.evaluate', {
        expression: `(() => {
          const badge = document.querySelector('[data-testid="enterprise-highlight-badge"]');
          const cards = Array.from(document.querySelectorAll('.grid > div'));
          
          if (!badge) {
            return { error: 'Badge not found' };
          }

          const badgeRect = badge.getBoundingClientRect();
          const enterpriseCard = badge.closest('.bg-surface') || cards[0];
          const cardRect = enterpriseCard ? enterpriseCard.getBoundingClientRect() : null;
          
          const cardRects = cards.map((c, i) => {
            const r = c.getBoundingClientRect();
            const titleEl = c.querySelector('h3');
            return {
              index: i,
              title: titleEl ? titleEl.textContent : '',
              top: r.top,
              left: r.left,
              width: r.width,
              height: r.height
            };
          });

          // Find title element in Enterprise card
          const titleEl = enterpriseCard ? enterpriseCard.querySelector('h3') : null;
          const titleRect = titleEl ? titleEl.getBoundingClientRect() : null;

          const badgeText = badge.textContent.trim();
          const badgeStyles = window.getComputedStyle(badge);

          const isInsideCard = cardRect ? (
            badgeRect.top >= cardRect.top &&
            badgeRect.bottom <= cardRect.bottom &&
            badgeRect.left >= cardRect.left &&
            badgeRect.right <= cardRect.right
          ) : false;

          const doesNotOverlapBorder = cardRect ? (
            (badgeRect.top - cardRect.top) >= 8
          ) : false;

          const doesNotCoverTitle = titleRect ? (
            titleRect.top >= badgeRect.bottom
          ) : false;

          return {
            badgeText,
            badgeRect: {
              top: badgeRect.top,
              bottom: badgeRect.bottom,
              left: badgeRect.left,
              right: badgeRect.right,
              width: badgeRect.width,
              height: badgeRect.height
            },
            cardRect: cardRect ? {
              top: cardRect.top,
              bottom: cardRect.bottom,
              left: cardRect.left,
              right: cardRect.right,
              width: cardRect.width,
              height: cardRect.height
            } : null,
            titleRect: titleRect ? {
              top: titleRect.top,
              bottom: titleRect.bottom
            } : null,
            cardCount: cards.length,
            cardRects,
            isInsideCard,
            doesNotOverlapBorder,
            doesNotCoverTitle,
            color: badgeStyles.color,
            backgroundColor: badgeStyles.backgroundColor,
            borderColor: badgeStyles.borderColor
          };
        })()`,
        returnByValue: true
      })

      const data = evalRes.result.value
      console.log('DOM Evaluation Result:', JSON.stringify(data, null, 2))

      // Capture screenshot
      const ssRes = await cdp.send('Page.captureScreenshot', {
        format: 'png',
        captureBeyondViewport: false
      })

      const ssPath = path.join(SCREENSHOT_DIR, `plans-${vp.name}.png`)
      fs.writeFileSync(ssPath, Buffer.from(ssRes.data, 'base64'))
      console.log(`Saved screenshot: ${ssPath} (${(ssRes.data.length * 0.75 / 1024).toFixed(1)} KB)`)

      results.push({
        viewport: vp.name,
        width: vp.width,
        height: vp.height,
        data,
        screenshot: ssPath
      })
    }

    fs.writeFileSync(
      path.join(SCREENSHOT_DIR, 'verification-results.json'),
      JSON.stringify(results, null, 2)
    )
    console.log('\nAll viewports evaluated successfully!')
  } finally {
    if (cdp) cdp.close()
    chromeProc.kill()
  }
}

run().catch((err) => {
  console.error('UI Verification failed:', err)
  process.exit(1)
})
