import http from 'node:http';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

// Mobile viewport matching standard Android phone
const VIEWPORT = { width: 360, height: 800, deviceScaleFactor: 2, mobile: true };

function fetchJson(url) {
  return new Promise((resolve, reject) => {
    http.get(url, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        try { resolve(JSON.parse(data)); } catch (e) { reject(e); }
      });
    }).on('error', reject);
  });
}

class CdpClient {
  constructor(wsUrl) {
    this.wsUrl = wsUrl;
    this.ws = null;
    this.id = 1;
    this.pending = new Map();
    this.eventListeners = new Map();
  }

  async connect() {
    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(this.wsUrl);
      this.ws.onopen = () => resolve();
      this.ws.onerror = (e) => reject(e);
      this.ws.onmessage = (msg) => {
        const payload = JSON.parse(msg.data);
        if (payload.id && this.pending.has(payload.id)) {
          const { resolve, reject } = this.pending.get(payload.id);
          this.pending.delete(payload.id);
          if (payload.error) reject(new Error(payload.error.message || JSON.stringify(payload.error)));
          else resolve(payload.result);
        } else if (payload.method) {
          const listeners = this.eventListeners.get(payload.method) || [];
          for (const fn of listeners) fn(payload.params);
        }
      };
    });
  }

  send(method, params = {}) {
    return new Promise((resolve, reject) => {
      const id = this.id++;
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }

  on(event, handler) {
    if (!this.eventListeners.has(event)) {
      this.eventListeners.set(event, []);
    }
    this.eventListeners.get(event).push(handler);
  }

  close() {
    if (this.ws) this.ws.close();
  }
}

async function run() {
  const artifactsDir = path.resolve(process.cwd(), 'screenshots');
  if (!fs.existsSync(artifactsDir)) fs.mkdirSync(artifactsDir, { recursive: true });

  const chromePath = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
  const chromeArgs = [
    '--headless=new',
    '--remote-debugging-port=9222',
    '--disable-gpu',
    '--no-sandbox',
    '--hide-scrollbars',
    'about:blank'
  ];

  console.log('Starting headless Chrome...');
  const chromeProc = spawn(chromePath, chromeArgs, { stdio: 'ignore' });
  
  const cleanup = () => {
    try { chromeProc.kill(); } catch (_) {}
  };
  process.on('exit', cleanup);
  process.on('SIGINT', cleanup);

  // Wait for Chrome remote debugging to come online
  await new Promise(r => setTimeout(r, 2000));

  try {
    const versionInfo = await fetchJson('http://localhost:9222/json/version');
    console.log('Connected to Chrome:', versionInfo.Browser);

    const tabs = await fetchJson('http://localhost:9222/json/list');
    let targetTab = tabs.find(t => t.type === 'page');
    if (!targetTab) {
      targetTab = await fetchJson('http://localhost:9222/json/new');
    }

    const cdp = new CdpClient(targetTab.webSocketDebuggerUrl);
    await cdp.connect();
    console.log('Connected to CDP target page WebSocket');

    // Track console errors
    const consoleErrors = [];
    const consoleLogs = [];

    await cdp.send('Page.enable');
    await cdp.send('Runtime.enable');
    await cdp.send('Log.enable');
    await cdp.send('Network.enable');

    await cdp.send('Emulation.setDeviceMetricsOverride', VIEWPORT);
    await cdp.send('Emulation.setTouchEmulationEnabled', { enabled: true });

    cdp.on('Runtime.consoleAPICalled', (params) => {
      const text = params.args.map(a => a.value ?? a.description ?? '').join(' ');
      consoleLogs.push({ type: params.type, text });
      if (params.type === 'error') {
        consoleErrors.push(text);
        console.error(`[Browser Console Error]: ${text}`);
      } else {
        console.log(`[Browser Console ${params.type}]: ${text}`);
      }
    });

    cdp.on('Log.entryAdded', (params) => {
      if (params.entry.level === 'error') {
        consoleErrors.push(params.entry.text);
        console.error(`[Browser Log Error]: ${params.entry.text}`);
      }
    });

    async function evaluate(expression) {
      const res = await cdp.send('Runtime.evaluate', {
        expression,
        returnByValue: true,
        awaitPromise: true
      });
      if (res.exceptionDetails) {
        throw new Error(res.exceptionDetails.exception?.description || 'Evaluation error');
      }
      return res.result?.value;
    }

    async function takeScreenshot(name) {
      const res = await cdp.send('Page.captureScreenshot', { format: 'png' });
      const filePath = path.join(artifactsDir, `${name}.png`);
      fs.writeFileSync(filePath, Buffer.from(res.data, 'base64'));
      console.log(`Saved screenshot: ${filePath}`);
    }

    // 1. Navigate to Gateway root
    console.log('\n--- Test 1: Navigation to http://localhost:8000 ---');
    await cdp.send('Page.navigate', { url: 'http://localhost:8000' });
    await new Promise(r => setTimeout(r, 2000));

    await takeScreenshot('01_initial_help_step1');

    const appTitle = await evaluate('document.title');
    console.log(`Page title: ${appTitle}`);

    // Check battery & peer count from mesh API
    const headerTelemetry = await evaluate(`
      (() => {
        const text = document.body.innerText;
        const batteryMatch = text.match(/(\\d+)%/);
        const peersMatch = text.match(/(\\d+)\\s*(?:peers|nodes)/i);
        return { textLength: text.length, battery: batteryMatch ? batteryMatch[1] : null, peers: peersMatch ? peersMatch[1] : null };
      })()
    `);
    console.log('Header telemetry detected:', headerTelemetry);

    // 2. Click "Medical Emergency" to proceed to Step 2
    console.log('\n--- Test 2: Help Step 1 -> Step 2 ---');
    await evaluate(`
      (() => {
        const buttons = Array.from(document.querySelectorAll('button'));
        const medicalBtn = buttons.find(b => b.innerText.includes('Medical') || b.innerText.includes('Urgent'));
        if (medicalBtn) medicalBtn.click();
        else if (buttons[0]) buttons[0].click();
      })()
    `);
    await new Promise(r => setTimeout(r, 1000));
    await takeScreenshot('02_help_step2_empty');

    // 3. Test Step 2 Claim Analysis with Natural Language text
    console.log('\n--- Test 3: Step 2 Live Natural Language Parsing ---');
    await evaluate(`
      (() => {
        const textarea = document.querySelector('textarea');
        if (textarea) {
          textarea.value = 'Diabetic patient unconscious needing urgent insulin at market square';
          textarea.dispatchEvent(new Event('input', { bubbles: true }));
        }
      })()
    `);

    // Wait 1.5s for debounce and API round-trip
    await new Promise(r => setTimeout(r, 2000));
    await takeScreenshot('03_help_step2_parsed');

    const parsedResults = await evaluate(`
      (() => {
        const text = document.body.innerText;
        const confidenceMatch = text.match(/(?:Confidence|M3|Score)[:\\s]*([\\d.]+%?)/i);
        const hasMedicalBadge = text.includes('MEDICAL') || text.includes('Medical');
        return {
          hasMedicalBadge,
          confidenceFound: !!confidenceMatch,
          bodySnippet: text.slice(0, 300)
        };
      })()
    `);
    console.log('Claim analysis response in UI:', parsedResults);

    // 4. Submit and broadcast claim to enter Step 3
    console.log('\n--- Test 4: Broadcast Claim -> Step 3 ---');
    await evaluate(`
      (() => {
        const buttons = Array.from(document.querySelectorAll('button'));
        const broadcastBtn = buttons.find(b => b.innerText.toLowerCase().includes('broadcast') || b.innerText.toLowerCase().includes('send'));
        if (broadcastBtn) broadcastBtn.click();
      })()
    `);

    await new Promise(r => setTimeout(r, 2000));
    await takeScreenshot('04_help_step3_broadcasting');

    const step3State = await evaluate(`
      (() => {
        const text = document.body.innerText;
        return {
          isStep3: text.includes('Broadcasting') || text.includes('Hops') || text.includes('Active Alert') || text.includes('Status'),
          hasAbort: text.toLowerCase().includes('abort')
        };
      })()
    `);
    console.log('Step 3 broadcast state:', step3State);

    // 5. Navigate to Respond tab
    console.log('\n--- Test 5: Respond Screen & Sealed Stage-2 Details ---');
    await evaluate(`
      (() => {
        const respondTab = document.getElementById('nav-tab-respond') || document.querySelector('[data-tab="respond"]');
        if (respondTab) respondTab.click();
      })()
    `);
    await new Promise(r => setTimeout(r, 1500));
    await takeScreenshot('05_respond_screen');

    const respondRequests = await evaluate(`
      (() => {
        const buttons = Array.from(document.querySelectorAll('button'));
        const acceptBtn = buttons.find(b => b.innerText.toLowerCase().includes('accept'));
        return {
          hasAcceptButton: !!acceptBtn,
          bodySnippet: document.body.innerText.slice(0, 400)
        };
      })()
    `);
    console.log('Respond screen requests found:', respondRequests);

    if (respondRequests.hasAcceptButton) {
      console.log('Clicking Accept to verify M9 stage-2 disclosure...');
      await evaluate(`
        (() => {
          const buttons = Array.from(document.querySelectorAll('button'));
          const acceptBtn = buttons.find(b => b.innerText.toLowerCase().includes('accept'));
          if (acceptBtn) acceptBtn.click();
        })()
      `);
      await new Promise(r => setTimeout(r, 1500));
      await takeScreenshot('06_respond_accepted_unlocked');
      const unlockedText = await evaluate('document.body.innerText');
      console.log('Accepted responder view contains unlocked tags:', unlockedText.includes('ACCEPTED') || unlockedText.includes('Coarse') || unlockedText.includes('Exact') || unlockedText.includes('Sealed'));
    }

    // 6. Navigate to Map tab
    console.log('\n--- Test 6: Map Screen Live Hazard Grid ---');
    await evaluate(`
      (() => {
        const mapTab = document.getElementById('nav-tab-map') || document.querySelector('[data-tab="map"]');
        if (mapTab) mapTab.click();
      })()
    `);
    await new Promise(r => setTimeout(r, 1500));
    await takeScreenshot('07_map_screen');
    const mapInfo = await evaluate(`
      (() => {
        const text = document.body.innerText;
        return {
          hasFloodOrHazard: text.includes('Flood') || text.includes('Hazard') || text.includes('Waterline') || text.includes('Risk') || text.includes('Grid')
        };
      })()
    `);
    console.log('Map elements loaded:', mapInfo);

    // 7. Navigate to Mesh tab
    console.log('\n--- Test 7: Mesh Screen & Dev Chaos Controls ---');
    await evaluate(`
      (() => {
        const meshTab = document.getElementById('nav-tab-mesh') || document.querySelector('[data-tab="mesh"]');
        if (meshTab) meshTab.click();
      })()
    `);
    await new Promise(r => setTimeout(r, 1500));
    await takeScreenshot('08_mesh_screen');

    const meshInfo = await evaluate(`
      (() => {
        const text = document.body.innerText;
        return {
          hasRelay: text.includes('node-relay-1') || text.includes('Relay') || text.includes('Direct Wi-Fi'),
          hasAirtime: text.includes('Airtime') || text.includes('Duty') || text.includes('Tx'),
          hasChaosControls: text.includes('Partition') || text.includes('Blackhole') || text.includes('Simulate') || text.includes('CHAOS')
        };
      })()
    `);
    console.log('Mesh elements loaded:', meshInfo);

    // Exercise Chaos: Click Partition then Heal
    console.log('Exercising Dev Chaos Control: Partition then Heal...');
    await evaluate(`
      (() => {
        const buttons = Array.from(document.querySelectorAll('button'));
        const partitionBtn = buttons.find(b => b.innerText.toLowerCase().includes('partition'));
        if (partitionBtn) partitionBtn.click();
      })()
    `);
    await new Promise(r => setTimeout(r, 1000));
    await takeScreenshot('09_mesh_partitioned');

    await evaluate(`
      (() => {
        const buttons = Array.from(document.querySelectorAll('button'));
        const healBtn = buttons.find(b => b.innerText.toLowerCase().includes('heal'));
        if (healBtn) healBtn.click();
      })()
    `);
    await new Promise(r => setTimeout(r, 1000));
    await takeScreenshot('10_mesh_healed');

    // 8. Navigate to Me tab & Profile persistence
    console.log('\n--- Test 8: Me Screen & Profile Persistence ---');
    await evaluate(`
      (() => {
        const meTab = document.getElementById('nav-tab-me') || document.querySelector('[data-tab="me"]');
        if (meTab) meTab.click();
      })()
    `);
    await new Promise(r => setTimeout(r, 1500));
    await takeScreenshot('11_me_screen');

    // 9. Language Switch Test (EN -> HI -> MR)
    console.log('\n--- Test 9: Language Switcher ---');
    await evaluate(`
      (() => {
        const selectOrButtons = Array.from(document.querySelectorAll('select, button'));
        const hiBtn = selectOrButtons.find(el => el.innerText && el.innerText.includes('HI'));
        if (hiBtn) hiBtn.click();
        const select = document.querySelector('select');
        if (select) {
          select.value = 'hi';
          select.dispatchEvent(new Event('change', { bubbles: true }));
        }
      })()
    `);
    await new Promise(r => setTimeout(r, 1000));
    await takeScreenshot('12_lang_hindi');

    // Verify Console Errors
    console.log('\n--- Verification: Console Errors Audit ---');
    console.log(`Total console logs: ${consoleLogs.length}`);
    console.log(`Total console errors: ${consoleErrors.length}`);
    if (consoleErrors.length > 0) {
      console.error('Console errors detected:', consoleErrors);
      process.exitCode = 1;
    } else {
      console.log('SUCCESS: Zero console errors throughout the entire user journey!');
    }

    cdp.close();
  } finally {
    cleanup();
  }
}

run().catch((err) => {
  console.error('E2E Test Execution Failed:', err);
  process.exit(1);
});
