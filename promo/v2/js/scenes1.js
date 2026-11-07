// Global layers + scenes 1–4: hook, logo, platforms, radar.
'use strict';

// ------------------------------------------------------------ globals
const IMPACTS = [[EV.impact, 15], [EV.drop, 11], [EV.justDrop, 9], [EV.finale, 6]];
const GROOVE = [[bar(7), EV.drop], [bar(24), EV.words[0]], [bar(35), bar(37)]];
const G = { pulse: 0 };
const kickPulse = T => (GROOVE.some(([a, b]) => T >= a && T < b) ? Math.exp(-(T % BEAT) * 7) : 0);

layer('clock', () => ({}), T => {
  let sx = 0, sy = 0;
  for (const [t0, a] of IMPACTS) {
    const dt = T - t0;
    if (dt >= 0 && dt < 0.7) {
      const d = (1 - dt / 0.7) ** 2.2;
      sx += (noise1(T * 34, 3) - 0.5) * 2 * a * d;
      sy += (noise1(T * 34, 9) - 0.5) * 2 * a * d;
    }
  }
  SHAKE.x = sx; SHAKE.y = sy;
  G.pulse = kickPulse(T);
}, -10, { noShake: true });

layer('sky', root => { initSky(); return { c: makeCanvas(root, 1920, 1080, 1) }; }, (T, s) => {
  const level = kf(T, [[0, 0], [1.2, 0.38], [6, 0.48], [7.45, 0.55], [7.97, 0.15], [8.0, 1.3], [9.6, 0.95], [12, 0.85], [44.45, 0.85], [44.55, 0.3], [45.5, 0.45], [46.2, 0.85], [64, 0.85], [66, 1.15], [67, 0.95], [76, 0.9]]);
  const curtain = kf(T, [[0, 0.15], [7.9, 0.35], [8.0, 1.0], [12, 0.55], [63, 0.6], [66, 1.0], [68, 0.75]]);
  drawSky(s.c.ctx, 1920, 1080, T, { level, pulse: G.pulse * 0.45, drift: -T * 9, curtain, stars: kf(T, [[0, 0], [1.4, 1]]) });
}, 0, { noShake: true });

const UNDER = {};
layer('under', root => { UNDER.c = makeCanvas(root, 1920, 1080, 1); return {}; }, () => UNDER.c.ctx.clearRect(0, 0, 1920, 1080), 15);

// ------------------------------------------------------------ actors
const ACT = {};
const LINE = {          // lineup (platforms) — bottoms on a shared floor at y=880
  mac: { x: 372, y: 693, s: 0.6 }, android: { x: 833, y: 651, s: 0.72 },
  iphone: { x: 1099, y: 656, s: 0.72 }, win: { x: 1554, y: 696, s: 0.6 },
};
const OUT = {           // outro lineup — smaller, floor at y=968
  mac: { x: 473, y: 819, s: 0.48 }, android: { x: 853, y: 784, s: 0.58 },
  iphone: { x: 1077, y: 787, s: 0.58 }, win: { x: 1451, y: 821, s: 0.48 },
};
const oc = E.outCubic, ic = E.inCubic, io = E.inOutCubic, ob = E.outBackSoft, ioq = E.inOutQuart;

const TRACKS = {
  android: {
    x: [[12.25, LINE.android.x], [16.95, LINE.android.x], [18.15, 1300, ioq], [25.5, 1300], [26.55, 470, ioq], [35.55, 470], [36.25, 140, ic], [47.7, 380], [48.6, 1010, oc], [55.5, 1010], [56.45, 1330, ioq], [57.55, 1330], [58.6, 300, ioq], [62.5, 300], [63.0, 260, ic], [67.9, OUT.android.x], [76, OUT.android.x]],
    y: [[12.25, 1010], [13.1, LINE.android.y, ob], [16.95, LINE.android.y], [18.15, 548, ioq], [25.5, 548], [26.55, 592, ioq], [35.55, 592], [36.25, 592], [47.7, 600], [48.6, 600], [55.5, 600], [56.45, 568, ioq], [57.55, 568], [58.6, 652, ioq], [63.0, 652], [67.9, OUT.android.y + 320], [68.8, OUT.android.y, ob], [76, OUT.android.y]],
    s: [[12.25, LINE.android.s], [16.95, LINE.android.s], [18.15, 1.33, ioq], [25.5, 1.33], [26.55, 1.15, ioq], [35.55, 1.15], [36.25, 1.0], [47.7, 1.2], [55.5, 1.2], [56.45, 1.3, ioq], [57.55, 1.3], [58.6, 0.95, ioq], [63.0, 0.95], [67.9, OUT.android.s], [76, OUT.android.s]],
    ry: [[12.25, 0], [16.95, 0], [18.15, -16, ioq], [25.5, -7], [26.55, 9, ioq], [35.55, 5], [36.25, 30], [47.7, 30], [48.6, 9, oc], [55.5, 5], [56.45, -12, ioq], [57.55, -9], [58.6, 13, ioq], [63.0, 10], [67.9, 0]],
    rx: [[16.95, 0], [18.15, 4], [25.5, 2], [26.55, 0]],
    o: [[12.2, 0], [12.5, 1], [35.6, 1], [36.15, 0], [47.75, 0], [48.25, 1], [62.55, 1], [63.0, 0], [67.95, 0], [68.35, 1], [76, 1]],
    blur: [[16.95, 0], [17.55, 7], [18.1, 0], [35.55, 0], [36.2, 16], [47.7, 16], [48.5, 0], [62.5, 0], [63.0, 12], [67.9, 12], [68.6, 0]],
  },
  iphone: {
    x: [[12.5, LINE.iphone.x], [16.95, LINE.iphone.x], [17.9, 1720, ic], [67.9, OUT.iphone.x], [76, OUT.iphone.x]],
    y: [[12.5, 1010], [13.35, LINE.iphone.y, ob], [16.95, LINE.iphone.y], [17.9, 760, ic], [67.95, OUT.iphone.y + 320], [69.0, OUT.iphone.y, ob], [76, OUT.iphone.y]],
    s: [[12.5, LINE.iphone.s], [17.9, 0.8], [67.9, OUT.iphone.s]],
    ry: [[12.5, 0], [16.95, 0], [17.9, -28], [67.9, 0]],
    o: [[12.45, 0], [12.75, 1], [17.3, 1], [17.85, 0], [68.15, 0], [68.55, 1]],
    blur: [[16.95, 0], [17.9, 18], [68.0, 12], [68.8, 0]],
  },
  mac: {
    x: [[12.0, LINE.mac.x], [16.95, LINE.mac.x], [17.9, -320, ic], [25.6, 2350], [26.65, 1330, E.outQuart], [35.55, 1330], [36.25, 1820, ic], [67.9, OUT.mac.x], [76, OUT.mac.x]],
    y: [[12.0, 1010], [12.85, LINE.mac.y, ob], [16.95, LINE.mac.y], [17.9, 650], [25.6, 625], [35.55, 625], [67.85, OUT.mac.y + 320], [68.75, OUT.mac.y, ob], [76, OUT.mac.y]],
    s: [[12.0, LINE.mac.s], [17.9, 0.64], [25.6, 0.86], [35.55, 0.86], [36.25, 0.92], [67.9, OUT.mac.s]],
    ry: [[12.0, 0], [16.95, 0], [17.9, 22], [25.6, -34], [26.65, -9, E.outQuart], [35.55, -5], [36.25, -22], [67.9, 0]],
    o: [[11.95, 0], [12.25, 1], [17.3, 1], [17.85, 0], [25.6, 0], [26.1, 1], [35.6, 1], [36.15, 0], [67.9, 0], [68.3, 1]],
    blur: [[16.95, 0], [17.9, 18], [25.6, 18], [26.5, 0], [35.55, 0], [36.2, 16], [67.85, 12], [68.6, 0]],
  },
  win: {
    x: [[12.75, LINE.win.x], [16.95, LINE.win.x], [17.9, 2250, ic], [67.9, OUT.win.x], [76, OUT.win.x]],
    y: [[12.75, 1010], [13.6, LINE.win.y, ob], [16.95, LINE.win.y], [17.9, 650], [68.05, OUT.win.y + 320], [69.1, OUT.win.y, ob], [76, OUT.win.y]],
    s: [[12.75, LINE.win.s], [67.9, OUT.win.s]],
    ry: [[12.75, 0], [16.95, 0], [17.9, -22], [67.9, 0]],
    o: [[12.7, 0], [13.0, 1], [17.3, 1], [17.85, 0], [68.25, 0], [68.65, 1]],
    blur: [[16.95, 0], [17.9, 18], [68.1, 12], [68.9, 0]],
  },
  friend: {
    x: [[47.9, 2250], [48.8, 1500, E.outQuart], [55.5, 1500], [56.3, 2350, ic]],
    y: [[47.9, 600]],
    s: [[47.9, 1.2]],
    ry: [[47.9, -32], [48.8, -9, E.outQuart], [55.5, -5], [56.3, -32]],
    o: [[47.9, 0], [48.3, 1], [55.6, 1], [56.2, 0]],
    blur: [[47.9, 14], [48.7, 0], [55.6, 0], [56.3, 14]],
  },
};
const SWEEPS = { android: [13.2, 18.3, 26.6, 48.7, 56.5, 68.9], iphone: [13.45, 69.1], mac: [12.95, 26.7, 68.85], win: [13.7, 69.2], friend: [48.9] };

function poseOf(name, T) {
  const tr = TRACKS[name];
  const g = (k, d) => (tr[k] ? kf(T, tr[k]) : d);
  return { x: g('x', 960), y: g('y', 540), s: g('s', 1), ry: g('ry', 0), rx: g('rx', 0), o: g('o', 1), blur: g('blur', 0) };
}

layer('actors', root => {
  ACT.mac = buildMac(root);
  ACT.win = buildWin(root);
  ACT.android = buildAndroid(root);
  ACT.iphone = buildIPhone(root);
  ACT.friend = buildFriendPhone(root);
  Object.entries(ACT).forEach(([k, a], i) => { a.name = k; a.ph = i * 1.7; });
  if (typeof buildScreens === 'function') buildScreens();
  return {};
}, T => {
  for (const [name, a] of Object.entries(ACT)) {
    const p = poseOf(name, T);
    a.cur = p;
    if (p.o <= 0.002) {
      if (a.vis !== false) { a.el.style.visibility = 'hidden'; a.vis = false; }
      continue;
    }
    a.vis = true;
    const fy = Math.sin(T * 0.9 + a.ph) * 7, fr = Math.sin(T * 0.55 + a.ph) * 1.6;
    tf(a.el, { x: p.x - a.w / 2, y: p.y - a.h / 2 + fy, p: 2400, ry: p.ry + fr, rx: p.rx, s: p.s, o: p.o, blur: p.blur });
    // specular sweep across the glass on landings
    let sw = -1;
    for (const t0 of SWEEPS[name] || []) if (T >= t0 && T < t0 + 1.1) sw = (T - t0) / 1.1;
    if (sw >= 0) { a.sweep.style.visibility = ''; a.sweep.style.transform = `translateX(${lerp(-70, 70, E.inOutSine(sw))}%)`; }
    else a.sweep.style.visibility = 'hidden';
    if (a.render) a.render(T);
  }
}, 20);

/** Top-center anchor of an actor in stage space (for beams). */
function actorTop(a, pad = 14) {
  const p = a.cur;
  return { x: p.x, y: p.y - (a.h * p.s) / 2 - pad + Math.sin(STAGE.T * 0.9 + a.ph) * 7 };
}

// ------------------------------------------------------------ glyphs
const GL = (body, w = 150, hh = 150) => `<svg width="${w}" height="${hh}" viewBox="0 0 150 150" fill="none" stroke="#EEF2FF" stroke-width="4" stroke-linecap="round" stroke-linejoin="round">${body}</svg>`;
const GLYPH = [
  GL('<rect x="44" y="18" width="62" height="114" rx="13"/><circle cx="75" cy="30" r="3.2" fill="#EEF2FF" stroke="none"/><path d="M64 120h22" stroke-opacity=".6"/>'),
  GL('<rect x="44" y="18" width="62" height="114" rx="16"/><rect x="62" y="25" width="26" height="8" rx="4" fill="#EEF2FF" stroke="none"/><path d="M64 122h22" stroke-opacity=".6"/>'),
  GL('<rect x="22" y="34" width="106" height="68" rx="7"/><path d="M68 34h14v4a3 3 0 0 1-3 3h-8a3 3 0 0 1-3-3z" fill="#EEF2FF" stroke="none"/><path d="M10 110h130l-6 8H16z"/>'),
  GL('<rect x="18" y="28" width="114" height="74" rx="6"/><path d="M18 90h114" stroke-opacity=".7"/><circle cx="63" cy="96" r="2" fill="#EEF2FF" stroke="none"/><circle cx="75" cy="96" r="2" fill="#EEF2FF" stroke="none"/><circle cx="87" cy="96" r="2" fill="#EEF2FF" stroke="none"/><path d="M75 102v18M54 122h42"/>'),
];

// ======================================================= 1. hook (0–8)
scene('hook', [0, 8.05], root => {
  const s = { fx: makeCanvas(root, 1920, 1080, 1) };
  const X = [360, 760, 1160, 1560], Y = 610, cols = [C.teal, C.cyan, C.violet, C.pink];
  const names = ['ANDROID', 'IPHONE', 'MAC', 'WINDOWS'];
  s.b = X.map((x, i) => {
    const b = h('div', 'bubble', root);
    css(b, { left: x + 'px', top: Y + 'px' });
    const rim = h('div', 'rim', b);
    css(rim, { background: `radial-gradient(circle at 50% 38%, ${rgba(cols[i], 0.17)}, ${rgba(cols[i], 0.05)} 58%, ${rgba(cols[i], 0)} 72%)`, boxShadow: `inset 0 0 0 2px ${rgba(cols[i], 0.6)}, inset 0 0 50px ${rgba(cols[i], 0.22)}, 0 0 70px ${rgba(cols[i], 0.16)}` });
    const hit = h('div', 'rim', b);
    css(hit, { boxShadow: `inset 0 0 0 3px ${rgba(C.danger, 0.95)}, 0 0 60px ${rgba(C.danger, 0.55)}, inset 0 0 60px ${rgba(C.danger, 0.3)}` });
    const g = h('div', 'glyph', b, GLYPH[i]);
    const l = h('div', 'lbl', b, names[i]);
    return { el: b, rim, hit, g, l, x, y: Y };
  });
  s.files = EV.bounce.map((bn, i) => h('div', 'filechip', root, photoThumb(60, 60, 7 + i, 12)));
  const mk = html => { const e = h('div', 'h2 center abs mask', root, html); css(e, { left: '0', width: '1920px', top: '118px' }); return e; };
  s.h1 = mk('Your devices live in<br><span class="dim">different worlds.</span>');
  s.w1 = splitWords(s.h1);
  s.h2 = mk('Sharing between them<br><span class="dim">shouldn’t be this hard.</span>');
  s.w2 = splitWords(s.h2);
  return s;
}, (T, s) => {
  const x = s.fx.ctx;
  x.clearRect(0, 0, 1920, 1080);
  // opening sonar ping
  shock(x, 960, 610, T, 0.05, { dur: 2.2, r0: 6, r1: 980, w: 3, col: C.teal, a: 0.5 });
  const dotA = env(T, 0, 2.0, 0.15, 1.2);
  if (dotA > 0) {
    const g = x.createRadialGradient(960, 610, 0, 960, 610, 46);
    g.addColorStop(0, rgba(C.phos, 0.9 * dotA)); g.addColorStop(1, rgba(C.teal, 0));
    x.fillStyle = g; x.beginPath(); x.arc(960, 610, 46, 0, TAU); x.fill();
  }
  const imp = E.inExpo(ramp(T, EV.implode, 7.98));
  const tension = ramp(T, 6.0, EV.implode);
  s.b.forEach((b, i) => {
    const t0 = EV.bubbles[i];
    const a = clamp((T - t0) / 0.3);
    const sp = 0.55 + 0.45 * spring(T - t0, 1.9, 0.5);
    // hits on this bubble
    let hit = 0, sq = 0, dir = 1;
    for (const [from, to, , th] of EV.bounce) {
      if (to !== i || T < th) continue;
      const dt = T - th;
      hit = Math.max(hit, Math.exp(-dt * 5));
      sq += Math.exp(-dt * 7) * Math.cos(dt * 34) * 0.075;
      dir = Math.sign(s.b[to].x - s.b[from].x);
    }
    const jit = tension * 6;
    const jx = (noise1(T * 22, i * 5) - 0.5) * 2 * jit, jy = (noise1(T * 22, i * 5 + 2) - 0.5) * 2 * jit;
    const wob = 1 + 0.012 * Math.sin(T * 2.3 + i * 1.3);
    const shrink = lerp(1, 0.93, E.inOutSine(tension));
    const px = lerp(b.x + jx, 960, imp), py = lerp(b.y + jy, 600, imp);
    const sc = sp * wob * shrink * lerp(1, 0.08, imp);
    tf(b.el, { x: px - b.x + sq * 40 * dir, y: py - b.y, sx: sc * (1 - sq), sy: sc * (1 + sq * 0.8), o: a * (1 - E.inQuad(ramp(T, 7.75, 7.98))), blur: (1 - a) * 8 + imp * 10 });
    op(b.hit, hit * 0.9 + tension * 0.25 * (0.5 + 0.5 * Math.sin(T * 40 + i)));
    op(b.l, clamp((T - t0 - 0.15) / 0.4) * (1 - imp));
  });
  // bouncing files: launch -> hit the wall -> fall back
  EV.bounce.forEach(([from, to, tl, th], i) => {
    const f = s.files[i];
    const A = s.b[from], B = s.b[to];
    const dir = Math.sign(B.x - A.x);
    const x0 = A.x + dir * 40, xr = B.x - dir * 162;
    if (T < tl - 0.18 || T > th + 0.6) { op(f, 0); return; }
    let px, py, o = 1, r = (T - tl) * 260 * dir, sc = 1;
    if (T < tl) { const q = E.outBack(ramp(T, tl - 0.18, tl)); px = x0; py = A.y; sc = q; o = clamp(q); }
    else if (T < th) { const q = E.inQuad(ramp(T, tl, th)); px = lerp(x0, xr, q); py = A.y - 90 * Math.sin(Math.PI * q); }
    else { const q = E.outCubic(ramp(T, th, th + 0.55)); px = xr - dir * 150 * q; py = A.y + 60 * q * q; o = 1 - q; r += 160 * q * -dir; }
    tf(f, { x: px, y: py, r, s: sc, o });
  });
  wordsIn(s.w1, T, 1.0, { stagger: 0.075, dur: 0.75 });
  wordsOut(s.w1, T, 3.62, { stagger: 0.025 });
  if (T < EV.hook2 - 0.05) s.w2.forEach(w => op(w, 0));
  else { wordsIn(s.w2, T, EV.hook2, { stagger: 0.075, dur: 0.75 }); wordsOut(s.w2, T, 7.15, { stagger: 0.02 }); }
});

// ======================================================= 2. logo (8–12)
scene('logo', [7.9, 12.3], async root => {
  const s = { fx: makeCanvas(root, 1920, 1080, 1) };
  s.burst = makeBurst({ seed: 3, n: 150, t0: EV.impact, x: 960, y: 470, speed: [380, 1700], life: [0.7, 1.7], size: [1.2, 4.6], drag: 2.3 });
  s.burst2 = makeBurst({ seed: 9, n: 60, t0: EV.impact, x: 960, y: 470, speed: [120, 520], life: [1.2, 2.4], size: [1, 3], drag: 1.4 });
  s.orb = makeCanvas(root, 420, 420, 1.5);
  css(s.orb, { position: 'absolute', left: 750 + 'px', top: 260 + 'px' });
  s.icon = h('div', 'appicon abs', root, APP_ICON_SVG);
  css(s.icon, { width: '220px', height: '220px', left: '850px', top: '360px' });
  s.word = h('div', 'wordmark abs mask', root);
  'CrossDrop'.split('').forEach(ch => { const m = h('span', 'wm', s.word); h('span', 'w g', m, ch); });
  s.chars = [...s.word.querySelectorAll('.w')];
  s.tag = h('div', 'sub center abs mask', root, 'Share anything with any device nearby.');
  css(s.tag, { left: '0', width: '1920px', top: '640px', fontSize: '38px', color: '#B9BFE3' });
  s.tw = splitWords(s.tag);
  s.afterLayout = () => {
    s.ww = s.word.offsetWidth;
    const total = 220 + 54 + s.ww;
    s.left = (1920 - total) / 2;
    s.iconX = s.left + 110;
    css(s.word, { left: (s.left + 274) + 'px', top: '392px' });
  };
  return s;
}, (T, s) => {
  const x = s.fx.ctx;
  x.clearRect(0, 0, 1920, 1080);
  shock(x, 960, 470, T, EV.impact, { dur: 1.15, r0: 40, r1: 1350, w: 12, col: C.teal, a: 0.95 });
  shock(x, 960, 470, T, EV.impact + 0.07, { dur: 1.5, r0: 30, r1: 1550, w: 7, col: C.violet, a: 0.7 });
  shock(x, 960, 470, T, EV.impact + 0.16, { dur: 1.7, r0: 20, r1: 1700, w: 4, col: C.pink, a: 0.5 });
  s.burst(x, T); s.burst2(x, T);
  const out = E.inCubic(ramp(T, 11.55, 12.15));
  // the orb: springs in, then shrinks into the icon's orb
  const o = s.orb.ctx;
  o.clearRect(0, 0, s.orb.width, s.orb.height);
  const k = s.orb.k;
  const pop = spring(T - EV.impact, 1.7, 0.42);
  const shrink = E.inOutCubic(ramp(T, 8.45, 8.85));
  const r = lerp(118 * pop, 28.5, shrink);
  const orbA = 1 - E.inQuad(ramp(T, 8.68, 8.9));
  if (orbA > 0 && T >= EV.impact) {
    o.globalAlpha = orbA;
    drawOrb(o, 210 * k, 210 * k, r * k, T, { level: 0.56, turb: lerp(1, 0.35, ramp(T, 8, 9)), flare: 1 - ramp(T, 8, 8.6), dp: k * 0.8 });
    o.globalAlpha = 1;
  }
  // icon appears around the orb, then slides left for the wordmark
  const ia = E.outCubic(ramp(T, 8.62, 8.95));
  const slide = E.inOutQuart(ramp(T, 8.95, 9.55));
  const ix = lerp(960, s.iconX, slide);
  const isc = lerp(0.8, 1, E.outBack(ramp(T, 8.62, 9.05)));
  tf(s.icon, { x: ix - 960, y: -out * 70, s: isc, o: ia * (1 - out), blur: out * 8 });
  tf(s.orb, { x: ix - 960, y: 0 });
  s.chars.forEach((c, i) => {
    const p = E.outQuart(ramp(T, EV.wordmark + 0.12 + i * 0.045, EV.wordmark + 0.85 + i * 0.045));
    tf(c, { y: (1 - p) * 150, o: p, blur: (1 - p) * 6 });
  });
  const glow = 0.35 + 0.65 * env(T, 9.2, 11.6, 0.6, 1.5);
  s.word.style.filter = `drop-shadow(0 0 ${(26 * glow).toFixed(1)}px rgba(69,229,255,${(0.35 * glow).toFixed(3)})) blur(${(out * 8).toFixed(2)}px)`;
  s.word.style.transform = `translateY(${(-out * 70).toFixed(1)}px)`;
  s.word.style.opacity = 1 - out;
  wordsIn(s.tw, T, EV.tagline, { stagger: 0.06, dur: 0.7 });
  wordsOut(s.tw, T, 11.45, { stagger: 0.02 });
  // soft sonar from the icon on the beat
  for (const t0 of [10.0, 11.0]) shock(x, ix, 470, T, t0, { dur: 1.6, r0: 120, r1: 640, w: 2.5, col: C.teal, a: 0.35 * (1 - out) });
});

// ================================================== 3. platforms (12–18)
scene('platforms', [11.8, 18.2], root => {
  const s = { fx: makeCanvas(root, 1920, 1080, 1) };
  s.head = h('div', 'h2 center abs mask', root, 'One app. <span class="g">Every device.</span>');
  css(s.head, { left: '0', width: '1920px', top: '128px', fontSize: '86px' });
  s.hw = splitWords(s.head);
  const names = [['mac', 'macOS', 'MacBook'], ['android', 'Android', 'phones & tablets'], ['iphone', 'iOS', 'iPhone & iPad'], ['win', 'Windows', 'laptops & PCs']];
  s.labels = names.map(([k, n, sub]) => {
    const l = h('div', 'plabel', root, `${n}<small>${sub}</small>`);
    css(l, { left: (LINE[k].x - 150) + 'px', width: '300px', top: '916px' });
    return l;
  });
  s.pairs = [['mac', 'android'], ['android', 'iphone'], ['iphone', 'win'], ['mac', 'iphone'], ['android', 'win'], ['mac', 'win']];
  s.cols = { mac: C.violet, android: C.teal, iphone: C.cyan, win: C.pink };
  return s;
}, (T, s) => {
  wordsIn(s.hw, T, 12.15, { stagger: 0.11, dur: 0.8 });
  wordsOut(s.hw, T, 16.85, { stagger: 0.03 });
  s.labels.forEach((l, i) => {
    const t0 = EV.labels[i];
    const p = spring(T - t0, 2.2, 0.55), a = clamp((T - t0) / 0.25);
    const out = E.inCubic(ramp(T, 16.9 + i * 0.04, 17.3 + i * 0.04));
    tf(l, { y: (1 - p) * 26 + out * 20, s: 0.85 + 0.15 * p, o: a * (1 - out), blur: out * 6 });
  });
  // beams: luminous arcs between every pair, drawn on, then flowing
  const x = s.fx.ctx;
  x.clearRect(0, 0, 1920, 1080);
  const fade = 1 - E.inCubic(ramp(T, 16.7, 17.25));
  if (T < EV.beams || fade <= 0) return;
  s.pairs.forEach(([a, b], i) => {
    const t0 = EV.beams + i * 0.22;
    const draw = E.inOutCubic(ramp(T, t0, t0 + 0.7));
    if (draw <= 0) return;
    const A = actorTop(ACT[a]), B = actorTop(ACT[b]);
    const d = Math.abs(B.x - A.x);
    const cx = (A.x + B.x) / 2, cy = Math.min(A.y, B.y) - 70 - d * 0.22;
    const pt = u => ({ x: (1 - u) ** 2 * A.x + 2 * (1 - u) * u * cx + u * u * B.x, y: (1 - u) ** 2 * A.y + 2 * (1 - u) * u * cy + u * u * B.y });
    const g = x.createLinearGradient(A.x, 0, B.x, 0);
    g.addColorStop(0, rgba(s.cols[a], 0.85 * fade)); g.addColorStop(1, rgba(s.cols[b], 0.85 * fade));
    x.save();
    x.strokeStyle = g; x.lineWidth = 2.4; x.shadowColor = s.cols[a]; x.shadowBlur = 14;
    x.beginPath();
    const N = 60;
    for (let k = 0; k <= N * draw; k++) { const p = pt(k / N); if (k === 0) x.moveTo(p.x, p.y); else x.lineTo(p.x, p.y); }
    x.stroke();
    x.restore();
    // flowing sparks
    for (let k = 0; k < 4; k++) {
      const u = ((T - t0) * 0.55 + k / 4 + i * 0.13) % 1;
      if (u > draw) continue;
      const p = pt(u);
      const gg = x.createRadialGradient(p.x, p.y, 0, p.x, p.y, 12);
      gg.addColorStop(0, rgba('#ffffff', 0.95 * fade)); gg.addColorStop(0.3, rgba(s.cols[b], 0.6 * fade)); gg.addColorStop(1, rgba(s.cols[b], 0));
      x.fillStyle = gg; x.beginPath(); x.arc(p.x, p.y, 12, 0, TAU); x.fill();
    }
    // endpoint pulses
    const land = ramp(T, t0 + 0.6, t0 + 1.2);
    if (land > 0 && land < 1) {
      x.strokeStyle = rgba(s.cols[b], (1 - land) * 0.9 * fade); x.lineWidth = 2;
      x.beginPath(); x.arc(B.x, B.y, 6 + 40 * E.outCubic(land), 0, TAU); x.stroke();
    }
  });
});

// ===================================================== 4. radar (18–26)
const RADAR_PEERS = [
  { name: "Maya's iPhone", ang: -52, dist: 0.62, at: EV.peers[0], seed: 1 },
  { name: 'MacBook Pro', ang: 203, dist: 0.70, at: EV.peers[1], seed: 2 },
  { name: 'Studio PC', ang: 138, dist: 0.50, at: EV.peers[2], seed: 3 },
  { name: 'Galaxy Tab', ang: 24, dist: 0.80, at: EV.peers[3], seed: 4 },
];

scene('radar', [17.7, 26.4], root => {
  const s = {};
  const L = 150;
  s.kick = h('div', 'kicker abs', root, '01 <b>/</b> Discover');
  css(s.kick, { left: L + 'px', top: '262px' });
  s.head = h('div', 'h1 abs mask', root, 'Nearby devices<br><span class="g">just appear.</span>');
  css(s.head, { left: L + 'px', top: '312px' });
  s.hw = splitWords(s.head);
  s.sub = h('div', 'sub abs mask', root, HAS_VO ? '' : 'CrossDrop spots phones, tablets and computers<br>on your Wi-Fi the moment they’re near.');
  css(s.sub, { left: L + 'px', top: '540px', width: '860px' });
  s.sw = splitWords(s.sub);
  s.chips = ['No pairing', 'No accounts', 'No cables'].map((t, i) => {
    const c = h('div', 'xchip abs', root, `<span class="x">✕</span>${t}`);
    css(c, { left: (L + [0, 256, 538][i]) + 'px', top: HAS_VO ? '566px' : '690px' });
    return c;
  });
  return s;
}, (T, s) => {
  const out = t0 => E.inCubic(ramp(T, t0, t0 + 0.45));
  const ko = out(25.45);
  tf(s.kick, { x: -ko * 60, o: E.outCubic(ramp(T, 18.2, 18.7)) * (1 - ko) });
  wordsIn(s.hw, T, 18.35, { stagger: 0.08, dur: 0.8 });
  wordsOut(s.hw, T, 25.5, { stagger: 0.02, dy: 0, blur: 10 });
  wordsIn(s.sw, T, 19.4, { stagger: 0.025, dur: 0.7 });
  wordsOut(s.sw, T, 25.4, { stagger: 0.01, dy: 0 });
  s.chips.forEach((c, i) => {
    const t0 = EV.chips[i];
    const p = spring(T - t0, 2.3, 0.5), a = clamp((T - t0) / 0.2);
    const o = out(25.35 + i * 0.03);
    tf(c, { y: (1 - p) * 30, s: 0.8 + 0.2 * p, o: a * (1 - o), x: -o * 50, blur: o * 6 });
  });
});
