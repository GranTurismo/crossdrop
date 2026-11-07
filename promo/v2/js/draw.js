// Canvas renderers — faithful ports of the app's Compose canvases
// (RadarCanvas.kt, LiquidOrb.kt) plus promo-only ambience.
'use strict';

const C = {
  deep: '#030014', soft: '#0A0620', raised: '#141033', high: '#201851',
  teal: '#3BFFB0', tealDim: '#1FBF8A', cyan: '#45E5FF', violet: '#9D6BFF', pink: '#FF4FD8',
  phos: '#B8FFE9', text: '#EEF2FF', dim: '#8B93BB', danger: '#FF5470',
};
const rgba = (hex, a) => {
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${clamp(a).toFixed(4)})`;
};
const AURORA = [C.teal, C.cyan, C.violet, C.pink];

/** Backing-store helper: canvas with CSS size (w,h) and pixel ratio k. */
function makeCanvas(parent, w, h, k = 2, cls = '') {
  const c = h_canvas(parent, cls);
  c.width = Math.round(w * k); c.height = Math.round(h * k);
  c.style.width = w + 'px'; c.style.height = h + 'px';
  c.style.position = 'absolute'; c.style.left = '0'; c.style.top = '0';
  c.k = k; c.cw = w; c.ch = h;
  c.ctx = c.getContext('2d');
  return c;
}
function h_canvas(parent, cls) { const c = document.createElement('canvas'); if (cls) c.className = cls; if (parent) parent.appendChild(c); return c; }

function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

// ---------------------------------------------------------------- the radar

const RADAR_STARS = (() => {
  const out = [];
  for (let i = 0; i < 90; i++) {
    const hh = BigInt.asUintN(64, (BigInt(i) * 2654435761n) ^ 0x9E3779B9n);
    out.push({
      x: Number((hh >> 33n) % 1000n) / 1000,
      y: Number((hh >> 11n) % 1023n) / 1023,
      r: 0.8 + (i % 3) * 0.5, sp: 0.8 + (i % 7) * 0.22, ph: i * 1.7,
    });
  }
  return out;
})();

/**
 * Sonar radar. `o.peers`: [{name, ang (deg), dist (0..1), at (appear T),
 * kind: 'peer'|'vault', tap (T), hot (0..1 highlight)}].
 */
function drawRadar(ctx, w, h, T, o = {}) {
  const dp = o.dp || 1;
  const cx = o.cx ?? w / 2, cy = o.cy ?? h / 2;
  const maxR = o.maxR ?? Math.min(w, h) / 2 * 0.86;
  const tA = (T / 9) * TAU;
  ctx.save();
  if (o.bg !== false) {
    const g = ctx.createRadialGradient(cx, cy, 0, cx, cy, Math.max(w, h) / 1.6);
    g.addColorStop(0, '#0C0628'); g.addColorStop(1, C.deep);
    ctx.fillStyle = g; ctx.fillRect(0, 0, w, h);
    // nebula washes
    const blobs = [[C.violet, -0.9, -0.5, 1.1], [C.cyan, 0.8, -0.9, 0.9], [C.teal, 0.4, 0.95, 1.0], [C.pink, -0.75, 0.6, 0.7]];
    blobs.forEach(([col, ox, oy, sc], i) => {
      const dx = Math.sin(tA * 0.4 + i * 2.1) * 0.08, dy = Math.cos(tA * 0.3 + i) * 0.06;
      const px = cx + (ox + dx) * maxR, py = cy + (oy + dy) * maxR, rr = maxR * sc;
      const bg = ctx.createRadialGradient(px, py, 0, px, py, rr);
      bg.addColorStop(0, rgba(col, 0.085)); bg.addColorStop(1, rgba(col, 0));
      ctx.fillStyle = bg; ctx.fillRect(px - rr, py - rr, rr * 2, rr * 2);
    });
    for (const s of RADAR_STARS) {
      const tw = 0.5 + 0.5 * Math.sin(tA * s.sp + s.ph);
      ctx.fillStyle = `rgba(255,255,255,${(0.05 + 0.1 * tw).toFixed(3)})`;
      ctx.beginPath(); ctx.arc(s.x * w, s.y * h, s.r * dp * 0.9, 0, TAU); ctx.fill();
    }
  }
  // grid
  ctx.lineWidth = 1.2 * dp; ctx.strokeStyle = rgba(C.teal, 0.10);
  for (let i = 1; i <= 4; i++) { ctx.beginPath(); ctx.arc(cx, cy, (maxR * i) / 4, 0, TAU); ctx.stroke(); }
  ctx.strokeStyle = rgba(C.teal, 0.05); ctx.lineWidth = 1 * dp;
  ctx.beginPath(); ctx.moveTo(cx - maxR, cy); ctx.lineTo(cx + maxR, cy); ctx.moveTo(cx, cy - maxR); ctx.lineTo(cx, cy + maxR); ctx.stroke();
  // sweep (6 s per revolution) — bright leading edge with an aurora trail
  const th = ((T / 6) * TAU + (o.sweepOff || 0)) % TAU;
  const cg = ctx.createConicGradient(th + 0.0001, cx, cy);
  cg.addColorStop(0, rgba(C.teal, 0.0));
  cg.addColorStop(0.80, rgba(C.violet, 0.0));
  cg.addColorStop(0.87, rgba(C.violet, 0.07));
  cg.addColorStop(0.94, rgba(C.cyan, 0.16));
  cg.addColorStop(1, rgba(C.teal, 0.32));
  ctx.fillStyle = cg;
  ctx.beginPath(); ctx.moveTo(cx, cy); ctx.arc(cx, cy, maxR, th - 1.2, th); ctx.closePath(); ctx.fill();
  ctx.strokeStyle = rgba(C.teal, 0.45); ctx.lineWidth = 1.4 * dp;
  ctx.beginPath(); ctx.moveTo(cx, cy); ctx.lineTo(cx + Math.cos(th) * maxR, cy + Math.sin(th) * maxR); ctx.stroke();
  // ripples (4.2 s loop, four phases)
  const rt = (T / 4.2) % 1;
  for (let i = 0; i < 4; i++) {
    const ph = (rt + i / 4) % 1;
    ctx.strokeStyle = rgba(C.teal, (1 - ph) * 0.35);
    ctx.lineWidth = Math.max(0.6, 3 - ph * 2) * dp;
    ctx.beginPath(); ctx.arc(cx, cy, ph * maxR, 0, TAU); ctx.stroke();
  }
  // peers
  const bobT = (T / 2.6) * TAU;
  for (const p of o.peers || []) {
    if (T < p.at) continue;
    const dt = T - p.at;
    const pop = spring(dt, 2.4, 0.42);
    const a = (p.ang * Math.PI) / 180;
    const bob = Math.sin(bobT + (p.seed || 0)) * 6 * dp;
    const r = maxR * p.dist + bob;
    const px = cx + r * Math.cos(a), py = cy + r * Math.sin(a);
    p._x = px; p._y = py;
    // discovery ping
    if (dt < 1.1) {
      const q = dt / 1.1;
      ctx.strokeStyle = rgba(p.kind === 'vault' ? C.violet : C.teal, 0.8 * (1 - q) ** 1.5);
      ctx.lineWidth = 2 * dp * (1 - q) + 0.5;
      ctx.beginPath(); ctx.arc(px, py, 30 * dp * (0.6 + 2.6 * E.outCubic(q)), 0, TAU); ctx.stroke();
    }
    let hot = p.hot || 0;
    if (p.tap !== undefined && T >= p.tap) {
      const q = (T - p.tap) / 0.9;
      if (q < 1) {
        ctx.strokeStyle = rgba(C.phos, 0.9 * (1 - q));
        ctx.lineWidth = 3 * dp * (1 - q) + 0.6;
        ctx.beginPath(); ctx.arc(px, py, 30 * dp * (0.7 + 2.2 * E.outCubic(q)), 0, TAU); ctx.stroke();
      }
      hot = Math.max(hot, 1 - clamp(q * 0.6));
    }
    ctx.save();
    ctx.translate(px, py); ctx.scale(pop, pop); ctx.translate(-px, -py);
    ctx.globalAlpha = clamp(dt / 0.12) * (o.alpha ?? 1);
    if (p.kind === 'vault') drawVaultBeacon(ctx, px, py, p, dp, T);
    else drawPeerNode(ctx, px, py, p, dp, bobT, hot);
    ctx.restore();
  }
  drawSelfNode(ctx, cx, cy, o.self || 'Pixel 9', dp, T);
  ctx.restore();
}

function pill(ctx, label, x, y, dp, { size = 12, weight = 500, color = C.text, stroke = 'rgba(139,255,220,0.24)', fill = 'rgba(10,6,32,0.85)', padH = 10, padV = 4 } = {}) {
  ctx.font = `${weight} ${size * dp}px Inter`;
  const tw = ctx.measureText(label).width;
  const ph = size * 1.25 * dp + padV * 2 * dp, pw = tw + padH * 2 * dp;
  roundRect(ctx, x - pw / 2, y, pw, ph, ph / 2);
  ctx.fillStyle = fill; ctx.fill();
  ctx.lineWidth = 1 * dp; ctx.strokeStyle = stroke; ctx.stroke();
  ctx.fillStyle = color; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
  ctx.fillText(label, x, y + ph / 2 + 0.5 * dp);
  return { w: pw, h: ph };
}

function drawPeerNode(ctx, x, y, p, dp, bobT, hot) {
  const nodeR = 30 * dp;
  const pulse = 1 + 0.08 * Math.sin(bobT + (p.seed || 0) * 1.3);
  const halo = nodeR * 2 * pulse * (1 + 0.35 * hot);
  const g = ctx.createRadialGradient(x, y, 0, x, y, halo);
  g.addColorStop(0, rgba(C.teal, 0.40 + 0.35 * hot)); g.addColorStop(1, rgba(C.teal, 0));
  ctx.fillStyle = g; ctx.beginPath(); ctx.arc(x, y, halo, 0, TAU); ctx.fill();
  const b = ctx.createRadialGradient(x, y - nodeR * 0.3, 0, x, y - nodeR * 0.3, nodeR);
  b.addColorStop(0, C.phos); b.addColorStop(1, rgba(C.teal, 0.85));
  ctx.fillStyle = b; ctx.beginPath(); ctx.arc(x, y, nodeR * 0.62, 0, TAU); ctx.fill();
  ctx.strokeStyle = rgba(C.teal, 0.6 + 0.4 * hot); ctx.lineWidth = 1.4 * dp * (1 + hot);
  ctx.beginPath(); ctx.arc(x, y, nodeR * 0.8, 0, TAU); ctx.stroke();
  pill(ctx, p.name, x, y + nodeR * 0.85, dp);
}

function drawVaultBeacon(ctx, x, y, p, dp, T) {
  const nodeR = 30 * dp;
  const pulse = 1 + 0.10 * Math.sin((T / 2.6) * TAU + (p.seed || 0));
  const halo = nodeR * 2.2 * pulse;
  const g = ctx.createRadialGradient(x, y, 0, x, y, halo);
  g.addColorStop(0, rgba(C.violet, 0.45)); g.addColorStop(1, rgba(C.violet, 0));
  ctx.fillStyle = g; ctx.beginPath(); ctx.arc(x, y, halo, 0, TAU); ctx.fill();
  const b = ctx.createRadialGradient(x, y - nodeR * 0.3, 0, x, y - nodeR * 0.3, nodeR);
  b.addColorStop(0, C.phos); b.addColorStop(1, rgba(C.violet, 0.85));
  ctx.fillStyle = b; ctx.beginPath(); ctx.arc(x, y, nodeR * 0.58, 0, TAU); ctx.fill();
  const ringR = nodeR * 1.02, rot = (T / 9) * TAU;
  ctx.lineWidth = 2 * dp;
  for (let i = 0; i < 12; i++) {
    ctx.strokeStyle = rgba(i % 3 === 0 ? C.pink : C.violet, i % 2 === 0 ? 0.9 : 0.42);
    const a0 = rot + (i * 30 * Math.PI) / 180;
    ctx.beginPath(); ctx.arc(x, y, ringR, a0, a0 + (16 * Math.PI) / 180); ctx.stroke();
  }
  pill(ctx, 'RW', x, y - nodeR * 1.35 - 16 * dp, dp, { size: 9, weight: 700, color: C.phos, stroke: rgba(C.violet, 0.7), padH: 6, padV: 2 });
  pill(ctx, p.name, x, y + nodeR * 0.85, dp, { stroke: rgba(C.violet, 0.6) });
}

function drawSelfNode(ctx, x, y, name, dp, T) {
  const ph = (T / 4.2) % 1;
  ctx.strokeStyle = rgba(C.teal, (1 - ph) * 0.30);
  ctx.lineWidth = (1.2 + (1 - ph) * 1.4) * dp;
  ctx.beginPath(); ctx.arc(x, y, 14 * dp + ph * 34 * dp, 0, TAU); ctx.stroke();
  const g = ctx.createRadialGradient(x, y, 0, x, y, 44 * dp);
  g.addColorStop(0, rgba(C.teal, 0.5)); g.addColorStop(1, rgba(C.teal, 0));
  ctx.fillStyle = g; ctx.beginPath(); ctx.arc(x, y, 44 * dp, 0, TAU); ctx.fill();
  ctx.fillStyle = C.phos; ctx.beginPath(); ctx.arc(x, y, 9 * dp, 0, TAU); ctx.fill();
  ctx.strokeStyle = C.teal; ctx.lineWidth = 1.6 * dp;
  ctx.beginPath(); ctx.arc(x, y, 14 * dp, 0, TAU); ctx.stroke();
  ctx.font = `400 ${11 * dp}px Inter`; ctx.fillStyle = C.dim; ctx.textAlign = 'center'; ctx.textBaseline = 'top';
  ctx.fillText(name, x, y + 18 * dp);
}

// ------------------------------------------------------------ the liquid orb

/** LiquidOrb.kt port. o: level, turb, flare, pct (bool), dp, slosh (deg). */
function drawOrb(ctx, cx, cy, r, T, o = {}) {
  const level = clamp(o.level ?? 0), turb = clamp(o.turb ?? 0), flare = clamp(o.flare ?? 0);
  const dp = o.dp || 1;
  const tA = (T / 3) * TAU, tB = (T / 4.7) * TAU, bubT = (T / 9) % 1;
  // outer energy glow (promo: visible halo behind the glass)
  const glowA = 0.12 + 0.30 * turb + 0.45 * flare;
  const og = ctx.createRadialGradient(cx, cy, r * 0.7, cx, cy, r * 1.45);
  og.addColorStop(0, rgba(C.teal, glowA)); og.addColorStop(0.5, rgba(C.violet, glowA * 0.45)); og.addColorStop(1, rgba(C.violet, 0));
  ctx.fillStyle = og; ctx.beginPath(); ctx.arc(cx, cy, r * 1.45, 0, TAU); ctx.fill();
  // glass shell
  ctx.fillStyle = '#0C0626'; ctx.beginPath(); ctx.arc(cx, cy, r, 0, TAU); ctx.fill();
  if (turb > 0.02) {
    const ig = ctx.createRadialGradient(cx, cy, 0, cx, cy, r);
    ig.addColorStop(0, rgba(C.teal, 0.20 * turb)); ig.addColorStop(0.6, rgba(C.violet, 0.12 * turb)); ig.addColorStop(1, rgba(C.violet, 0));
    ctx.fillStyle = ig; ctx.beginPath(); ctx.arc(cx, cy, r, 0, TAU); ctx.fill();
  }
  const sg = ctx.createLinearGradient(cx - r, cy - r, cx + r, cy + r);
  sg.addColorStop(0, 'rgba(234,249,255,0.40)'); sg.addColorStop(0.5, 'rgba(234,249,255,0.10)'); sg.addColorStop(1, 'rgba(234,249,255,0.27)');
  ctx.strokeStyle = sg; ctx.lineWidth = 2.4 * dp;
  ctx.beginPath(); ctx.arc(cx, cy, r, 0, TAU); ctx.stroke();
  // liquid
  const amp = r * 0.045 * (0.4 + turb * 1.8);
  const surfaceY = cy + r - level * 2 * r;
  ctx.save();
  ctx.beginPath(); ctx.arc(cx, cy, r * 0.965, 0, TAU); ctx.clip();
  if (level > 0.005) {
    ctx.translate(cx, cy); ctx.rotate(((o.slosh || 0) * Math.PI) / 180); ctx.translate(-cx, -cy);
    wave(ctx, cx, cy, r, surfaceY + r * 0.03, amp * 0.7, tA + 1.7, tB, rgba(C.violet, 0.42));
    const fg = ctx.createLinearGradient(0, surfaceY - amp * 2, 0, cy + r);
    fg.addColorStop(0, rgba(C.phos, 0.92)); fg.addColorStop(0.25, rgba(C.teal, 0.95));
    fg.addColorStop(0.5, rgba(C.cyan, 0.90)); fg.addColorStop(0.75, rgba(C.violet, 0.88)); fg.addColorStop(1, '#241052');
    wave(ctx, cx, cy, r, surfaceY, amp, tA, tB + 0.9, fg);
  }
  if (turb > 0.22 && level > 0.02) {
    const count = Math.floor(6 + turb * 14);
    for (let i = 0; i < count; i++) {
      const seed = i * 137.5;
      const x = cx + Math.cos(seed) * r * 0.72 * ((seed % 97) / 97);
      const span = cy + r * 0.95 - surfaceY;
      if (span <= 0) continue;
      const cyc = (bubT * (1 + (i % 5) * 0.35) + i * 0.618) % 1;
      const y = cy + r * 0.95 - cyc * span;
      const wob = Math.sin(cyc * 6.28 + i) * r * 0.02;
      ctx.fillStyle = rgba(C.phos, (0.35 + 0.3 * turb) * Math.sin(cyc * Math.PI));
      ctx.beginPath(); ctx.arc(x + wob, y, (1.5 + (i % 4)) * (r / 260) * 1.6, 0, TAU); ctx.fill();
    }
  }
  ctx.restore();
  // glass specular
  ctx.strokeStyle = 'rgba(223,252,243,0.32)'; ctx.lineWidth = 3 * dp; ctx.lineCap = 'round';
  ctx.beginPath(); ctx.arc(cx, cy, r * 0.86, Math.PI * 1.08, Math.PI * 1.36); ctx.stroke();
  ctx.lineCap = 'butt';
  if (flare > 0) {
    ctx.strokeStyle = rgba(C.phos, 0.9 * flare); ctx.lineWidth = (3 + 8 * flare) * dp;
    ctx.beginPath(); ctx.arc(cx, cy, r, 0, TAU); ctx.stroke();
    ctx.strokeStyle = rgba(C.phos, 0.25 * flare); ctx.lineWidth = 1.5 * dp;
    ctx.beginPath(); ctx.arc(cx, cy, r * (1 + 0.12 * flare), 0, TAU); ctx.stroke();
  }
  if (o.pct && level > 0.005 && level < 0.995) {
    ctx.font = `300 ${r * 2 * 0.17 / 0.94}px Inter`;
    ctx.fillStyle = rgba(C.phos, 0.92); ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    ctx.shadowColor = 'rgba(3,0,20,0.55)'; ctx.shadowBlur = 12 * dp;
    ctx.fillText(Math.round(level * 100) + '%', cx, cy + 2 * dp);
    ctx.shadowBlur = 0;
  }
}

function wave(ctx, cx, cy, r, sy, amp, tA, tB, fill) {
  const left = cx - r * 1.15, right = cx + r * 1.15, steps = 64;
  const at = x => { const u = (x - cx) / r; return (Math.sin(u * 2.1 + tA) * 0.6 + Math.sin(u * 3.7 - tB * 1.3) * 0.4) * amp; };
  ctx.beginPath();
  ctx.moveTo(left, sy + at(left));
  for (let i = 1; i <= steps; i++) { const x = left + ((right - left) * i) / steps; ctx.lineTo(x, sy + at(x)); }
  ctx.lineTo(right, cy + r * 1.3); ctx.lineTo(left, cy + r * 1.3); ctx.closePath();
  ctx.fillStyle = fill; ctx.fill();
}

// --------------------------------------------------------- aurora backdrop

const SKY = { off: null, stars: null };

function initSky() {
  SKY.off = document.createElement('canvas');
  SKY.off.width = 384; SKY.off.height = 216;
  SKY.octx = SKY.off.getContext('2d');
  SKY.soft = document.createElement('canvas');
  SKY.soft.width = 384; SKY.soft.height = 216;
  SKY.sctx = SKY.soft.getContext('2d');
  const r = rng(42);
  SKY.stars = Array.from({ length: 340 }, () => ({
    x: r(), y: r(), z: 0.25 + r() * 0.75, s: 0.5 + r() ** 3 * 1.9, ph: r() * TAU, sp: 0.5 + r() * 1.8,
    tint: r() < 0.12 ? (r() < 0.5 ? C.cyan : C.phos) : '#ffffff',
  }));
}

/**
 * Night sky with aurora curtains. o: level (overall 0..1), pulse (beat 0..1),
 * drift (px camera parallax), warm (0..1 shifts toward violet/pink).
 */
function drawSky(ctx, w, h, T, o = {}) {
  const lv = o.level ?? 1, pulse = o.pulse ?? 0;
  const x = SKY.octx, ow = SKY.off.width, oh = SKY.off.height;
  x.globalCompositeOperation = 'source-over';
  const base = x.createLinearGradient(0, 0, 0, oh);
  base.addColorStop(0, '#05021A'); base.addColorStop(0.55, '#07031F'); base.addColorStop(1, '#030014');
  x.fillStyle = base; x.fillRect(0, 0, ow, oh);
  x.globalCompositeOperation = 'lighter';
  const blobs = [
    [C.teal, 0.18, 0.10, 0.95, 0.15], [C.violet, 0.86, 0.32, 0.85, 0.17], [C.cyan, 0.48, 0.98, 1.1, 0.10],
    [C.pink, 0.78, 0.80, 0.7, 0.09], [C.violet, 0.10, 0.75, 0.6, 0.08],
  ];
  blobs.forEach(([col, bx, by, rs, a], i) => {
    const px = (bx + 0.05 * Math.sin(T * 0.11 + i * 1.9)) * ow;
    const py = (by + 0.05 * Math.cos(T * 0.09 + i * 1.3)) * oh;
    const rr = rs * ow * 0.75;
    const g = x.createRadialGradient(px, py, 0, px, py, rr);
    const aa = a * lv * (1 + 0.35 * pulse);
    g.addColorStop(0, rgba(col, aa)); g.addColorStop(1, rgba(col, 0));
    x.fillStyle = g; x.fillRect(0, 0, ow, oh);
  });
  // curtains: soft vertical ribbons, gently undulating
  const curt = o.curtain ?? 0.55;
  if (curt > 0.01) {
    const bands = [
      { y0: 0.18, amp: 0.07, k: 2.2, sp: 0.23, col: [C.teal, C.cyan], a: 0.40 },
      { y0: 0.30, amp: 0.06, k: 3.1, sp: -0.17, col: [C.cyan, C.violet], a: 0.28 },
      { y0: 0.12, amp: 0.05, k: 1.6, sp: 0.12, col: [C.violet, C.pink], a: 0.24 },
    ];
    const N = 160, sw = ow / N;
    for (const b of bands) {
      for (let i = 0; i < N; i++) {
        const u = (i + 0.5) / N;
        const xx = i * sw;
        const yy = (b.y0 + b.amp * Math.sin(u * b.k * TAU * 0.5 + T * b.sp + b.k) + 0.02 * Math.sin(u * 17 + T * 0.6)) * oh;
        const hgt = oh * (0.30 + 0.12 * Math.sin(u * 5.3 + T * 0.2 + b.k));
        const flick = (0.6 + 0.4 * Math.sin(u * 23.0 + T * 0.9 + b.k * 3)) * (0.72 + 0.28 * Math.sin(u * 91 + T * 0.45 + b.k * 5) * Math.sin(u * 37 - T * 0.3));
        const g = x.createLinearGradient(0, yy, 0, yy + hgt);
        const aa = b.a * curt * lv * flick * (1 + 0.3 * pulse) * Math.sin(u * Math.PI) ** 0.6;
        g.addColorStop(0, rgba(b.col[1], 0)); g.addColorStop(0.18, rgba(b.col[0], aa));
        g.addColorStop(0.55, rgba(b.col[1], aa * 0.35)); g.addColorStop(1, rgba(b.col[1], 0));
        x.fillStyle = g; x.fillRect(xx, yy, sw + 0.35, hgt);
      }
    }
  }
  x.globalCompositeOperation = 'source-over';
  // smooth away Skia's gradient dither before the 5x upscale (it would show as a mesh)
  SKY.sctx.clearRect(0, 0, 384, 216);
  SKY.sctx.filter = 'blur(2px)';
  SKY.sctx.drawImage(SKY.off, -4, -4, 392, 224);
  SKY.sctx.filter = 'none';
  ctx.imageSmoothingEnabled = true; ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(SKY.soft, 0, 0, w, h);
  // stars (parallax)
  const drift = o.drift || 0, sl = o.stars ?? 1;
  for (const s of SKY.stars) {
    const tw = 0.55 + 0.45 * Math.sin(T * s.sp + s.ph);
    let sx = (s.x * w + drift * s.z * 0.6) % w; if (sx < 0) sx += w;
    const sy = s.y * h;
    ctx.globalAlpha = clamp((0.18 + 0.55 * tw) * s.z * sl);
    ctx.fillStyle = s.tint;
    ctx.beginPath(); ctx.arc(sx, sy, s.s * (0.6 + 0.4 * s.z), 0, TAU); ctx.fill();
  }
  ctx.globalAlpha = 1;
}

// ------------------------------------------------------------- film grain

const GRAIN = { tiles: [] };
function initGrain() {
  const r = rng(7);
  for (let k = 0; k < 6; k++) {
    const c = document.createElement('canvas'); c.width = c.height = 256;
    const x = c.getContext('2d'); const img = x.createImageData(256, 256);
    for (let i = 0; i < img.data.length; i += 4) {
      const v = r() * 255;
      img.data[i] = img.data[i + 1] = img.data[i + 2] = v; img.data[i + 3] = 255;
    }
    x.putImageData(img, 0, 0);
    GRAIN.tiles.push(c);
  }
}
function drawGrain(ctx, w, h, frame, alpha = 0.035) {
  ctx.clearRect(0, 0, w, h);
  const tile = GRAIN.tiles[frame % GRAIN.tiles.length];
  const pat = ctx.createPattern(tile, 'repeat');
  const r = rng(frame * 31 + 5);
  const ox = Math.floor(r() * 256), oy = Math.floor(r() * 256);
  ctx.save(); ctx.globalAlpha = alpha; ctx.translate(-ox, -oy);
  ctx.fillStyle = pat; ctx.fillRect(0, 0, w + 256, h + 256); ctx.restore();
}

// ------------------------------------------------------- analytic particles

/** Deterministic burst. Returns draw(ctx, T). */
function makeBurst({ seed = 1, n = 80, t0 = 0, x = 0, y = 0, speed = [200, 900], life = [0.6, 1.4], size = [1.2, 4], colors = AURORA, drag = 2.2, gravity = 0, spread = TAU, dir = 0, glow = true }) {
  const r = rng(seed);
  const ps = Array.from({ length: n }, () => {
    const a = dir + (r() - 0.5) * spread;
    const v = lerp(speed[0], speed[1], r() ** 1.5);
    return { vx: Math.cos(a) * v, vy: Math.sin(a) * v, life: lerp(life[0], life[1], r()), s: lerp(size[0], size[1], r() ** 2), c: colors[Math.floor(r() * colors.length)], tw: r() * TAU };
  });
  return (ctx, T, ox = 0, oy = 0) => {
    const dt = T - t0;
    if (dt < 0) return;
    for (const p of ps) {
      if (dt > p.life) continue;
      const k = (1 - Math.exp(-drag * dt)) / drag;
      const px = x + ox + p.vx * k, py = y + oy + p.vy * k + 0.5 * gravity * dt * dt;
      const a = (1 - dt / p.life) ** 1.4 * (0.7 + 0.3 * Math.sin(dt * 18 + p.tw));
      if (glow) {
        const g = ctx.createRadialGradient(px, py, 0, px, py, p.s * 5);
        g.addColorStop(0, rgba(p.c, a * 0.5)); g.addColorStop(1, rgba(p.c, 0));
        ctx.fillStyle = g; ctx.beginPath(); ctx.arc(px, py, p.s * 5, 0, TAU); ctx.fill();
      }
      ctx.fillStyle = rgba('#ffffff', a * 0.9);
      ctx.beginPath(); ctx.arc(px, py, p.s * 0.7, 0, TAU); ctx.fill();
    }
  };
}

/** Glowing expanding ring (sonar shockwave). */
function shock(ctx, x, y, T, t0, { dur = 1.0, r0 = 10, r1 = 900, w = 6, col = C.teal, a = 0.9 } = {}) {
  const dt = T - t0;
  if (dt < 0 || dt > dur) return;
  const q = dt / dur, e = E.outCubic(q);
  const rr = lerp(r0, r1, e);
  ctx.save();
  ctx.strokeStyle = rgba(col, a * (1 - q) ** 1.3);
  ctx.lineWidth = Math.max(0.5, w * (1 - q));
  ctx.shadowColor = col; ctx.shadowBlur = 18;
  ctx.beginPath(); ctx.arc(x, y, rr, 0, TAU); ctx.stroke();
  ctx.restore();
}

// ------------------------------------------------------------------- QR

function qrMatrix(text) {
  const q = qrcode(0, 'M');
  q.addData(text); q.make();
  const n = q.getModuleCount();
  const m = [];
  for (let r = 0; r < n; r++) { m.push([]); for (let c = 0; c < n; c++) m[r].push(q.isDark(r, c)); }
  return m;
}
function qrSvg(text, size, { fg = '#030014', bg = '#ffffff', quiet = 2, round = 0.0 } = {}) {
  const m = qrMatrix(text), n = m.length, tot = n + quiet * 2, u = size / tot;
  let d = '';
  for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) if (m[r][c]) d += `M${((c + quiet) * u).toFixed(2)} ${((r + quiet) * u).toFixed(2)}h${u.toFixed(2)}v${u.toFixed(2)}h-${u.toFixed(2)}z`;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 ${size} ${size}" shape-rendering="crispEdges"><rect width="100%" height="100%" rx="${size * round}" fill="${bg}"/><path d="${d}" fill="${fg}"/></svg>`;
}
