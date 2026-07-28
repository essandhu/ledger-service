// Screenshot capture for the README's three web surfaces (docs/media/README.md has the runbook).
//
//   cd docs/media && npm install && npx playwright install chromium
//   node capture.mjs                 # against a stack already up on localhost
//
// Every shot comes from a live stack:
//
//   openapi.png   Swagger UI rendering /v3/api-docs. The whole API is behind a bearer token
//                 (only /actuator/health is anonymous), so the browser context carries a real
//                 client_credentials token from the compose Keycloak.
//
//   grafana.png   The provisioned dashboard with the drift gauges actually firing. A dashboard
//                 screenshot reading zero would say nothing about I15, so this script induces
//                 the same out-of-band corruption scripts/demo.sh does — a superuser UPDATE the
//                 application's own role is not granted — waits for Prometheus to scrape it,
//                 shoots, then repairs the snapshot by recomputation and re-runs the sweep. The
//                 stack is left CLEAN; nothing here is mocked or hand-drawn.
//
//   console.png   The read-only console showing that same drifted run: snapshot vs computed vs
//                 delta, which is I15 on a screen. Shot INSIDE the drift window the Grafana step
//                 already opened — one induced corruption, two images of the same true event —
//                 and reached through the real Keycloak login form, because the console has no
//                 anonymous surface but its health endpoint.
import { execFileSync } from 'node:child_process';
import { chromium } from 'playwright';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const BASE = process.env.BASE ?? 'http://localhost:8080';
const KC = process.env.KC ?? 'http://localhost:8081';
const GRAFANA = process.env.GRAFANA ?? 'http://localhost:3000';
const CONSOLE = process.env.CONSOLE_URL ?? 'http://localhost:8090';
const SCRAPE_SETTLE_MS = 35_000; // prometheus.yml scrape_interval is 15s — two scrapes plus slack

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function token(clientId, clientSecret) {
  const res = await fetch(`${KC}/realms/ledger/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'client_credentials', client_id: clientId, client_secret: clientSecret }),
  });
  if (!res.ok) throw new Error(`Keycloak refused a token for ${clientId}: HTTP ${res.status}`);
  return (await res.json()).access_token;
}

async function sweep(bearer) {
  const res = await fetch(`${BASE}/api/v1/reconciliation-runs`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${bearer}` },
  });
  if (res.status !== 201) throw new Error(`reconciliation run: HTTP ${res.status}`);
  return res.json();
}

// Superuser psql through the compose service — deliberately NOT the app's ledger_app role,
// which lacks the grant (I3). Repo root is two levels up from docs/media.
const psql = (sql) =>
  execFileSync('docker', ['compose', 'exec', '-T', 'postgres', 'psql', '-U', 'postgres', '-d', 'ledger',
    '-v', 'ON_ERROR_STOP=1', '-c', sql], { cwd: join(HERE, '..', '..'), encoding: 'utf8' });

const RECOMPUTE_ALL = `UPDATE account_balance ab
   SET balance = COALESCE((SELECT SUM(p.amount) FROM posting p WHERE p.account_id = ab.account_id), 0)`;

const browser = await chromium.launch();
try {
  // --- openapi.png -----------------------------------------------------------------------
  const admin = await token('ledger-cli', 'ledger-cli-dev-secret');
  const api = await browser.newContext({
    viewport: { width: 1280, height: 940 },
    deviceScaleFactor: 2,
    extraHTTPHeaders: { Authorization: `Bearer ${admin}` }, // covers the page AND its /v3/api-docs fetch
  });
  const swagger = await api.newPage();
  await swagger.goto(`${BASE}/swagger-ui/index.html`, { waitUntil: 'networkidle' });
  await swagger.waitForSelector('.opblock-summary-path', { timeout: 30_000 });
  // Open the money mover: its Idempotency-Key parameter is the point of ADR-0004.
  await swagger.locator('.opblock-summary', { hasText: '/api/v1/transfers' }).first().click();
  const params = swagger.locator('.opblock.is-open table.parameters').first();
  await params.waitFor({ timeout: 15_000 });
  await sleep(600);
  // Cut at the bottom of the parameter table rather than wherever the viewport happens to end.
  const box = await params.boundingBox();
  await swagger.screenshot({
    path: join(HERE, 'openapi.png'),
    clip: { x: 0, y: 0, width: 1280, height: Math.ceil(box.y + box.height + 24) },
  });
  console.log('openapi.png');
  await api.close();

  // --- grafana.png -----------------------------------------------------------------------
  const before = await sweep(admin);
  console.log(`baseline sweep: ${before.status}`);
  psql(`UPDATE account_balance SET balance = balance + 4200
          WHERE account_id = (SELECT account_id FROM account_balance
                              WHERE balance <> 0 ORDER BY account_id LIMIT 1)`);
  const drifted = await sweep(admin);
  if (drifted.status !== 'DRIFT') {
    // Almost always means the ledger is empty: the UPDATE above matches on a NON-ZERO balance,
    // so with no postings it corrupts nothing and the sweep is honestly CLEAN. Name the fix
    // rather than leaving a bare status to interpret.
    throw new Error(`expected DRIFT, got ${drifted.status} — has anything been posted? `
      + 'Run docs/media/tour.sh (or scripts/demo.sh) against this stack first.');
  }
  console.log(`drift induced and detected: ${drifted.driftCount} account(s)`);

  console.log(`waiting ${SCRAPE_SETTLE_MS / 1000}s for Prometheus to scrape the gauges...`);
  await sleep(SCRAPE_SETTLE_MS);

  // 1200px tall fits all ten panels (28 grid units) without clipping the bottom row.
  const graf = await browser.newContext({ viewport: { width: 1440, height: 1200 }, deviceScaleFactor: 2 });
  const dash = await graf.newPage();
  // kiosk hides Grafana's own chrome; anonymous Viewer is enabled in compose.yaml.
  await dash.goto(`${GRAFANA}/d/ledger?orgId=1&from=now-15m&to=now&kiosk`, { waitUntil: 'networkidle' });
  await dash.waitForSelector('text=Accounts adrift', { timeout: 30_000 });
  await sleep(6_000); // let every panel finish its first query and animate in
  await dash.screenshot({ path: join(HERE, 'grafana.png') });
  console.log('grafana.png');
  await graf.close();

  // --- console.png -----------------------------------------------------------------------
  // Still inside the drift window: the run Grafana just charted is the run this page explains.
  // The console has no anonymous surface beyond health, so this is a REAL authorization-code
  // login against the compose Keycloak — same stock-theme selectors the e2e lane drives.
  const ui = await browser.newContext({ viewport: { width: 1280, height: 900 }, deviceScaleFactor: 2 });
  const consolePage = await ui.newPage();
  await consolePage.goto(`${CONSOLE}/reconciliation/runs/${drifted.id}`);
  await consolePage.waitForURL('**/realms/ledger/protocol/openid-connect/auth**', { timeout: 30_000 });
  // ops, not viewer: the topbar of a viewer session is the same, but ops is the role the README
  // tells a reader to sign in as, and a screenshot should show what they will see.
  await consolePage.fill('#username', 'ops');
  await consolePage.fill('#password', 'ops');
  await consolePage.click('#kc-login');
  // Trailing glob, not an exact URL: Spring Security replays the saved request with a
  // `?continue` marker appended, so an exact match waits forever on a page that already loaded.
  await consolePage.waitForURL(`${CONSOLE}/reconciliation/runs/${drifted.id}**`, { timeout: 30_000 });
  // The topbar badge is polled in after the page renders (by design — no page pays for a
  // reconciliation read it did not ask for), so wait for it or the shot catches an empty slot.
  await consolePage.waitForSelector('.drift-badge-slot .status-badge', { timeout: 30_000 });
  await consolePage.waitForSelector('td.delta', { timeout: 15_000 });
  await sleep(400); // let app.js localize the timestamps; UTC ISO text otherwise flashes
  // Cut just below the findings panel instead of at the viewport edge, as the openapi shot does.
  const lastPanel = consolePage.locator('section.panel').last();
  const panelBox = await lastPanel.boundingBox();
  await consolePage.screenshot({
    path: join(HERE, 'console.png'),
    clip: { x: 0, y: 0, width: 1280, height: Math.ceil(panelBox.y + panelBox.height + 24) },
  });
  console.log('console.png');
  await ui.close();

  // --- leave the stack as we found it ------------------------------------------------------
  psql(RECOMPUTE_ALL);
  const after = await sweep(admin);
  if (after.status !== 'CLEAN') throw new Error(`repair failed, sweep says ${after.status}`);
  console.log('snapshots recomputed, final sweep: CLEAN');
} finally {
  await browser.close();
}
