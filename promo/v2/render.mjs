// Frame-exact renderer: headless Chromium seeks the page to each frame time,
// captures a PNG, and pipes it into ffmpeg. Workers render contiguous chunks
// in parallel; chunks are concatenated without re-encoding.
//
//   node render.mjs preview 1.0 8.6 27.4 ...    -> out/preview/*.png
//   node render.mjs video [--workers 6] [--from 0 --to 76] [--crf 17]
//   node render.mjs audio                       -> out/music.wav (see audio.html)
import { chromium } from 'playwright-core';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { spawn, execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.join(ROOT, 'out');
const FPS = 60, DURATION = 76;
const CHROME = process.env.CHROME || path.join(os.homedir(),
  'Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing');

const args = process.argv.slice(2);
const mode = args[0] || 'preview';
const opt = (k, d) => { const i = args.indexOf('--' + k); return i >= 0 ? args[i + 1] : d; };

function serve() {
  const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.woff2': 'font/woff2', '.png': 'image/png', '.svg': 'image/svg+xml', '.json': 'application/json', '.wav': 'audio/wav' };
  const srv = http.createServer((req, res) => {
    const p = path.join(ROOT, decodeURIComponent(new URL(req.url, 'http://x').pathname));
    if (!p.startsWith(ROOT) || !fs.existsSync(p) || fs.statSync(p).isDirectory()) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { 'Content-Type': types[path.extname(p)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
    fs.createReadStream(p).pipe(res);
  });
  return new Promise(r => srv.listen(0, '127.0.0.1', () => r(srv)));
}

async function openPage(port, page = 'index.html') {
  const browser = await chromium.launch({
    executablePath: CHROME, headless: true,
    args: ['--headless=new', '--hide-scrollbars', '--force-color-profile=srgb', '--force-device-scale-factor=1',
      '--disable-lcd-text', '--font-render-hinting=none', '--ignore-gpu-blocklist', '--enable-gpu-rasterization',
      '--disable-background-timer-throttling', '--disable-renderer-backgrounding', '--autoplay-policy=no-user-gesture-required'],
  });
  const pg = await browser.newPage({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
  pg.on('console', m => { if (m.type() === 'error' || m.type() === 'warning') console.log('[page]', m.type(), m.text()); });
  pg.on('pageerror', e => console.log('[pageerror]', e.message));
  await pg.goto(`http://127.0.0.1:${port}/${page}`);
  await pg.waitForFunction(() => window.__ready === true || window.__audioReady === true, null, { timeout: 60000 });
  const cdp = await pg.context().newCDPSession(pg);
  return { browser, pg, cdp };
}

async function grab(w, t) {
  await w.pg.evaluate(tt => window.seek(tt), t);
  const r = await w.cdp.send('Page.captureScreenshot', { format: 'png', optimizeForSpeed: true, fromSurface: true });
  return Buffer.from(r.data, 'base64');
}

async function preview() {
  const times = args.slice(1).filter(a => !a.startsWith('--')).map(Number);
  const dir = path.join(OUT, 'preview');
  fs.mkdirSync(dir, { recursive: true });
  const srv = await serve();
  const w = await openPage(srv.address().port);
  for (const t of times) {
    const buf = await grab(w, t);
    const f = path.join(dir, `t${t.toFixed(2).padStart(6, '0')}.png`);
    fs.writeFileSync(f, buf);
    console.log(f);
  }
  await w.browser.close(); srv.close();
}

async function video() {
  const workers = Number(opt('workers', 6));
  const from = Number(opt('from', 0)), to = Number(opt('to', DURATION));
  const crf = opt('crf', '17');
  const name = opt('name', 'video');
  const f0 = Math.round(from * FPS), f1 = Math.round(to * FPS);
  const segDir = path.join(OUT, 'seg-' + name);
  fs.rmSync(segDir, { recursive: true, force: true });
  fs.mkdirSync(segDir, { recursive: true });
  const srv = await serve();
  const per = Math.ceil((f1 - f0) / workers);
  const t0 = Date.now();
  let done = 0;
  const jobs = Array.from({ length: workers }, async (_, k) => {
    const a = f0 + k * per, b = Math.min(f1, a + per);
    if (a >= b) return null;
    const file = path.join(segDir, `seg${String(k).padStart(2, '0')}.mp4`);
    const ff = spawn('ffmpeg', ['-v', 'error', '-y', '-f', 'image2pipe', '-framerate', String(FPS), '-c:v', 'png', '-i', '-',
      '-vf', 'scale=out_color_matrix=bt709:out_range=tv,format=yuv420p',
      '-c:v', 'libx264', '-preset', 'slow', '-crf', crf, '-profile:v', 'high', '-level:v', '4.2',
      '-x264-params', 'aq-mode=3:aq-strength=0.9:deblock=-1,-1:keyint=120:min-keyint=1',
      '-colorspace', 'bt709', '-color_primaries', 'bt709', '-color_trc', 'bt709', '-color_range', 'tv', file], { stdio: ['pipe', 'inherit', 'inherit'] });
    const w = await openPage(srv.address().port);
    for (let f = a; f < b; f++) {
      const buf = await grab(w, f / FPS);
      if (!ff.stdin.write(buf)) await new Promise(r => ff.stdin.once('drain', r));
      done++;
      if (done % 120 === 0) {
        const el = (Date.now() - t0) / 1000;
        console.log(`frames ${done}/${f1 - f0}  ${(done / el).toFixed(1)} fps  eta ${((f1 - f0 - done) / (done / el)).toFixed(0)}s`);
      }
    }
    ff.stdin.end();
    await new Promise(r => ff.on('close', r));
    await w.browser.close();
    return file;
  });
  const files = (await Promise.all(jobs)).filter(Boolean);
  srv.close();
  const list = path.join(segDir, 'list.txt');
  fs.writeFileSync(list, files.map(f => `file '${f}'`).join('\n'));
  const outFile = path.join(OUT, `${name}.mp4`);
  execFileSync('ffmpeg', ['-v', 'error', '-y', '-f', 'concat', '-safe', '0', '-i', list, '-c', 'copy', outFile]);
  console.log(`done ${outFile} in ${((Date.now() - t0) / 1000).toFixed(0)}s`);
}

async function audio() {
  const srv = await serve();
  const w = await openPage(srv.address().port, 'audio.html');
  const t0 = Date.now();
  const res = await w.pg.evaluate(async () => {
    const out = await window.renderAudio();
    const enc = a => { const b = new Uint8Array(a.buffer); let s = ''; for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode.apply(null, b.subarray(i, i + 0x8000)); return btoa(s); };
    return { sr: out.sampleRate, L: enc(out.L), R: enc(out.R), log: out.log };
  });
  console.log(res.log);
  const L = new Float32Array(Buffer.from(res.L, 'base64').buffer.slice(0));
  const R = new Float32Array(Buffer.from(res.R, 'base64').buffer.slice(0));
  fs.mkdirSync(OUT, { recursive: true });
  writeWav(path.join(OUT, 'mix.wav'), res.sr, L, R);
  console.log(`audio rendered in ${((Date.now() - t0) / 1000).toFixed(1)}s -> out/mix.wav`);
  await w.browser.close(); srv.close();
}

function writeWav(file, sr, L, R) {
  const n = L.length, buf = Buffer.alloc(44 + n * 8);
  buf.write('RIFF', 0); buf.writeUInt32LE(36 + n * 8, 4); buf.write('WAVE', 8);
  buf.write('fmt ', 12); buf.writeUInt32LE(16, 16); buf.writeUInt16LE(3, 20); buf.writeUInt16LE(2, 22);
  buf.writeUInt32LE(sr, 24); buf.writeUInt32LE(sr * 8, 28); buf.writeUInt16LE(8, 32); buf.writeUInt16LE(32, 34);
  buf.write('data', 36); buf.writeUInt32LE(n * 8, 40);
  for (let i = 0; i < n; i++) { buf.writeFloatLE(L[i], 44 + i * 8); buf.writeFloatLE(R[i], 48 + i * 8); }
  fs.writeFileSync(file, buf);
}

({ preview, video, audio })[mode]().catch(e => { console.error(e); process.exit(1); });
