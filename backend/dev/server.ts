/**
 * LOCAL DEV SERVER — excluded from deploys via .vercelignore.
 *
 * Runs the real `api/**` handlers over plain Node http so the backend can be
 * exercised without the Vercel CLI, and serves a small manual test console at
 * `/` for the checks in docs/testing.md.
 *
 *   npm run dev      # http://localhost:3000
 */
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { enhanceRequest, enhanceResponse, type VercelHandler } from './vercel-shim';

const here = dirname(fileURLToPath(import.meta.url));

/** Minimal .env loader — avoids a dotenv dependency for a dev-only concern. */
function loadEnvFile(filename: string): void {
  try {
    const contents = readFileSync(resolve(here, '..', filename), 'utf8');
    for (const line of contents.split('\n')) {
      const trimmed = line.trim();
      if (trimmed.length === 0 || trimmed.startsWith('#')) continue;
      const index = trimmed.indexOf('=');
      if (index === -1) continue;
      const key = trimmed.slice(0, index).trim();
      let value = trimmed.slice(index + 1).trim();
      if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
        value = value.slice(1, -1);
      }
      if (process.env[key] === undefined) process.env[key] = value;
    }
    console.log(`[dev] loaded ${filename}`);
  } catch {
    // Absent file is fine.
  }
}

loadEnvFile('.env.local');
loadEnvFile('.env');

const ROUTES: Record<string, () => Promise<{ default: VercelHandler }>> = {
  '/api/health': () => import('../api/health/index'),
  '/api/ai/command': () => import('../api/ai/command'),
  '/api/ai/command/stream': () => import('../api/ai/command/stream'),
  '/api/commands': () => import('../api/commands/index'),
  '/api/memory': () => import('../api/memory/index'),
  '/api/devices': () => import('../api/devices/index'),
  '/api/screenshots': () => import('../api/screenshots/index'),
};

const CONSOLE_HTML = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>NUVA Backend — PHRASE 1</title>
<style>
  :root { color-scheme: dark; --bg:#0a0b0f; --panel:#14161d; --line:#252833; --text:#e8eaf0; --muted:#8b90a0; --accent:#7c5cff; --ok:#3ddc97; --warn:#ffb454; --err:#ff5c72; }
  * { box-sizing:border-box; }
  body { margin:0; background:var(--bg); color:var(--text); font:15px/1.55 ui-sans-serif,system-ui,-apple-system,"Segoe UI",Roboto,"Noto Sans Bengali",sans-serif; }
  .wrap { max-width:940px; margin:0 auto; padding:32px 20px 64px; }
  header { display:flex; align-items:center; gap:14px; margin-bottom:6px; }
  .logo { width:40px; height:40px; border-radius:12px; background:linear-gradient(135deg,var(--accent),#3ddc97); display:grid; place-items:center; font-weight:700; color:#0a0b0f; }
  h1 { font-size:22px; margin:0; letter-spacing:-0.01em; }
  .sub { color:var(--muted); font-size:13px; margin:0 0 26px 52px; }
  .panel { background:var(--panel); border:1px solid var(--line); border-radius:14px; padding:18px; margin-bottom:18px; }
  .panel h2 { font-size:13px; text-transform:uppercase; letter-spacing:0.08em; color:var(--muted); margin:0 0 14px; font-weight:600; }
  input[type=text] { width:100%; padding:12px 14px; border-radius:10px; border:1px solid var(--line); background:#0e1016; color:var(--text); font-size:15px; font-family:inherit; }
  input[type=text]:focus { outline:none; border-color:var(--accent); }
  .row { display:flex; gap:10px; flex-wrap:wrap; margin-top:12px; }
  button { padding:10px 16px; border-radius:10px; border:1px solid var(--line); background:#1c1f29; color:var(--text); cursor:pointer; font-size:14px; font-family:inherit; }
  button:hover { border-color:var(--accent); }
  button.primary { background:var(--accent); border-color:var(--accent); color:#0a0b0f; font-weight:600; }
  .chips { display:flex; gap:8px; flex-wrap:wrap; margin-top:12px; }
  .chip { font-size:13px; padding:7px 11px; border-radius:999px; border:1px solid var(--line); background:#0e1016; cursor:pointer; color:var(--muted); }
  .chip:hover { color:var(--text); border-color:var(--accent); }
  pre { background:#0e1016; border:1px solid var(--line); border-radius:10px; padding:14px; overflow:auto; max-height:440px; font:13px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace; margin:0; white-space:pre-wrap; word-break:break-word; }
  .badges { display:flex; gap:8px; flex-wrap:wrap; }
  .badge { font-size:12px; padding:5px 10px; border-radius:999px; border:1px solid var(--line); color:var(--muted); }
  .badge.ok { color:var(--ok); border-color:#1e5c46; }
  .badge.warn { color:var(--warn); border-color:#5c4620; }
  .badge.err { color:var(--err); border-color:#5c2430; }
  .note { color:var(--muted); font-size:12.5px; margin-top:12px; }
  code { background:#0e1016; padding:2px 6px; border-radius:5px; font-size:13px; }
  a { color:var(--accent); }
</style>
</head>
<body>
<div class="wrap">
  <header>
    <div class="logo">N</div>
    <h1>NUVA Backend</h1>
  </header>
  <p class="sub">PHRASE 1 — Vercel Backend Foundation · local dev console</p>

  <div class="panel">
    <h2>Health</h2>
    <div class="badges" id="badges"><span class="badge">checking…</span></div>
    <div class="row">
      <button onclick="health(false)">GET /api/health</button>
      <button onclick="health(true)">GET /api/health?deep=1</button>
    </div>
    <pre id="healthOut">—</pre>
  </div>

  <div class="panel">
    <h2>POST /api/ai/command</h2>
    <input type="text" id="cmd" value="Nuva YouTube open koro." placeholder="Speak as the user would…">
    <div class="chips">
      <span class="chip" onclick="setCmd('Nuva YouTube open koro.')">Banglish · open app</span>
      <span class="chip" onclick="setCmd('Nuva kal shokal 7 tay alarm dao.')">Banglish · alarm</span>
      <span class="chip" onclick="setCmd('নুভা গুগলে ঢাকার আবহাওয়া সার্চ করো।')">Bangla · search</span>
      <span class="chip" onclick="setCmd('Nuva Rahim ke WhatsApp e message pathao je ami ashchi.')">Banglish · message (confirm)</span>
      <span class="chip" onclick="setCmd('Nuva back jao.')">Banglish · back</span>
      <span class="chip" onclick="setCmd('Nuva ei screen ta poro.')">Banglish · read screen</span>
      <span class="chip" onclick="setCmd('Set a 10 minute timer')">English · timer</span>
      <span class="chip" onclick="setCmd('Nuva bkash diye Karim ke 5000 taka pathao.')">High risk · must refuse</span>
      <span class="chip" onclick="setCmd('Nuva amake ekta kobita likhe dao.')">Out of scope</span>
    </div>
    <div class="row"><button class="primary" onclick="send()">Send command</button></div>
    <pre id="cmdOut">—</pre>
    <p class="note">Without <code>GROQ_API_KEY</code> the deterministic fallback parser answers instead
      (<code>meta.source: "fallback"</code>). <code>/api/commands</code> and <code>/api/memory</code>
      need a Supabase user JWT, so they return 401 here.</p>
  </div>
</div>
<script>
  const badges = document.getElementById('badges');
  function setCmd(v) { document.getElementById('cmd').value = v; }

  function renderBadges(cfg) {
    if (!cfg) { badges.innerHTML = '<span class="badge err">health failed</span>'; return; }
    const b = [];
    b.push('<span class="badge ' + (cfg.groq.configured ? 'ok' : 'warn') + '">Groq: ' + (cfg.groq.configured ? cfg.groq.model : 'not configured') + '</span>');
    b.push('<span class="badge ' + (cfg.supabase.configured ? 'ok' : 'warn') + '">Supabase: ' + (cfg.supabase.configured ? 'configured' : 'not configured') + '</span>');
    b.push('<span class="badge ' + (cfg.persistence ? 'ok' : 'warn') + '">Persistence: ' + (cfg.persistence ? 'on' : 'off') + '</span>');
    b.push('<span class="badge">Auth required: ' + cfg.auth_required + '</span>');
    b.push('<span class="badge">Fallback parser: ' + cfg.fallback_parser + '</span>');
    badges.innerHTML = b.join('');
  }

  async function health(deep) {
    const out = document.getElementById('healthOut');
    out.textContent = 'loading…';
    try {
      const res = await fetch('/api/health' + (deep ? '?deep=1' : ''));
      const json = await res.json();
      out.textContent = res.status + ' ' + JSON.stringify(json, null, 2);
      renderBadges(json.config);
    } catch (err) { out.textContent = String(err); renderBadges(null); }
  }

  async function send() {
    const out = document.getElementById('cmdOut');
    out.textContent = 'thinking…';
    try {
      const res = await fetch('/api/ai/command', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Nuva-Device-Id': 'dev-console' },
        body: JSON.stringify({ text: document.getElementById('cmd').value, language: 'auto' }),
      });
      const json = await res.json();
      out.textContent = res.status + ' ' + JSON.stringify(json, null, 2);
    } catch (err) { out.textContent = String(err); }
  }

  health(false);
</script>
</body>
</html>`;

const server = createServer((req: IncomingMessage, res: ServerResponse) => {
  void (async () => {
    const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`);
    const route = ROUTES[url.pathname];

    if (route) {
      try {
        const module = await route();
        const vercelReq = await enhanceRequest(req, url);
        const vercelRes = enhanceResponse(res);
        await module.default(vercelReq, vercelRes);
      } catch (err) {
        console.error('[dev] handler crashed', err);
        if (!res.headersSent) {
          res.statusCode = 500;
          res.setHeader('Content-Type', 'application/json');
        }
        res.end(JSON.stringify({ ok: false, error: { code: 'INTERNAL', message: 'dev server failure' } }));
      }
      return;
    }

    if (url.pathname === '/' || url.pathname === '/index.html') {
      // Note: no X-Frame-Options here — the console must render inside the
      // Arena live-preview iframe. The API routes still send DENY.
      res.statusCode = 200;
      res.setHeader('Content-Type', 'text/html; charset=utf-8');
      res.setHeader('Cache-Control', 'no-store');
      res.end(CONSOLE_HTML);
      return;
    }

    res.statusCode = 404;
    res.setHeader('Content-Type', 'application/json');
    res.end(
      JSON.stringify({
        ok: false,
        error: { code: 'NOT_FOUND', message: `No route for ${url.pathname}` },
        routes: Object.keys(ROUTES),
      }),
    );
  })();
});

const port = Number.parseInt(process.env['PORT'] ?? '3000', 10);
// 0.0.0.0 so the sandbox live preview can reach it.
server.listen(port, '0.0.0.0', () => {
  console.log(`[dev] NUVA backend listening on http://0.0.0.0:${port}`);
  console.log(`[dev] routes: ${Object.keys(ROUTES).join(', ')}`);
});
