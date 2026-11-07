// Scenes 5–7: send (tap -> accept -> liquid orb), encryption, smart resend.
'use strict';

// ===================================================== 5. send (26–36)
scene('send', [25.4, 36.3], async root => {
  const s = { fx: makeCanvas(root, 1920, 1080, 1) };
  s.kick = h('div', 'kicker abs', root, '02 <b>/</b> Send');
  s.head = h('div', 'h2 abs center', root, '<span class="wm"><span class="w">Tap.</span></span> <span class="wm"><span class="w">Drop.</span></span> <span class="wm"><span class="w g">Done.</span></span>');
  css(s.head, { left: '880px', width: '900px', top: '150px', fontSize: '88px' });
  css(s.kick, { left: '1330px', top: '110px' });
  s.words = [...s.head.querySelectorAll('.w')];
  s.speed = h('div', 'speed', root, `<div class="num"><span class="v">108</span></div><div class="num" style="font-size:40px;color:#45E5FF;margin-top:4px">MB/s</div><div class="lab">local Wi-Fi</div>`);
  css(s.speed, { left: '669px', width: '200px', top: '640px' });
  s.sv = s.speed.querySelector('.v');
  s.capt = h('div', 'sub center abs mask', root, 'Straight from device to device, at <b>full Wi-Fi speed.</b>');
  css(s.capt, { left: '0', width: '1920px', top: '968px' });
  s.cw = splitWords(s.capt);
  // photo sprites for the stream of files
  s.imgs = await Promise.all([0, 1, 2, 3, 4, 5].map(async i => {
    const img = new Image();
    img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(photoThumb(96, 96, 300 + i, 14).replace('<svg ', '<svg xmlns="http://www.w3.org/2000/svg" '));
    await img.decode();
    return img;
  }));
  s.burstA = makeBurst({ seed: 21, n: 70, t0: EV.xferDone, x: 0, y: 0, speed: [200, 700], life: [0.6, 1.3], size: [1, 3.4], colors: [C.teal, C.phos, C.cyan] });
  s.burstB = makeBurst({ seed: 22, n: 90, t0: EV.xferDone + 0.05, x: 0, y: 0, speed: [220, 820], life: [0.6, 1.4], size: [1, 3.6], colors: [C.teal, C.phos, C.cyan, C.violet] });
  return s;
}, (T, s) => {
  const x = s.fx.ctx;
  x.clearRect(0, 0, 1920, 1080);
  const out = E.inCubic(ramp(T, 35.5, 35.95));
  tf(s.kick, { o: E.outCubic(ramp(T, 26.4, 26.9)) * (1 - out), x: -out * 40 });
  const times = [EV.tapPeer, EV.accept, EV.xferDone];
  s.words.forEach((w, i) => {
    const p = E.outQuart(ramp(T, times[i] - 0.05, times[i] + 0.55));
    const pop = 1 + 0.12 * Math.exp(-(T - times[i]) * 6) * (T >= times[i] ? 1 : 0);
    tf(w, { y: (1 - p) * 70 - out * 40, s: pop, o: p * (1 - out), blur: (1 - p) * 10 + out * 8 });
  });
  // speed readout between the devices
  const sp = env(T, EV.xferStart + 0.1, EV.xferDone + 0.75, 0.4, 0.45);
  tf(s.speed, { o: sp, s: 0.9 + 0.1 * sp, y: (1 - sp) * 20 });
  if (sp > 0) { const v = String(Math.round(xferSpeed(T))); if (s.sv.textContent !== v) s.sv.textContent = v; }
  // stream of photos flying phone -> Mac
  const A = ACT.android.cur, M = ACT.mac.cur;
  if (A && M && T > EV.xferStart && T < EV.xferDone + 0.9) {
    const sx = A.x + 105, sy = A.y - 70, ex = M.x - 70, ey = M.y - 50;
    for (let i = 0; i < 46; i++) {
      const t0 = EV.xferStart + 0.08 + i * 0.095;
      if (t0 > EV.xferDone - 0.35) break;
      const q = (T - t0) / 0.8;
      if (q < 0 || q > 1) continue;
      const r = rng(i * 13 + 1);
      const e = E.inOutSine(q);
      const lift = 190 + r() * 120;
      const px = lerp(sx, ex, e), py = lerp(sy, ey, e) - Math.sin(Math.PI * e) * lift + (r() - 0.5) * 60 * Math.sin(Math.PI * e);
      const size = lerp(56, 34, e) * (0.85 + r() * 0.3);
      const a = Math.min(1, q * 6, (1 - q) * 5);
      // trail
      for (let k = 3; k >= 1; k--) {
        const e2 = E.inOutSine(Math.max(0, q - k * 0.025));
        const tx = lerp(sx, ex, e2), ty = lerp(sy, ey, e2) - Math.sin(Math.PI * e2) * lift;
        x.globalAlpha = a * 0.12 * (4 - k);
        x.fillStyle = C.cyan;
        x.beginPath(); x.arc(tx, ty, size * 0.32, 0, TAU); x.fill();
      }
      x.save();
      x.globalAlpha = a;
      x.translate(px, py); x.rotate((r() - 0.5) * 0.6 + (q - 0.5) * 0.4);
      x.shadowColor = 'rgba(69,229,255,0.65)'; x.shadowBlur = 18;
      roundRect(x, -size / 2 - 3, -size / 2 - 3, size + 6, size + 6, 10); x.fillStyle = '#fff'; x.fill();
      x.shadowBlur = 0;
      x.save(); roundRect(x, -size / 2, -size / 2, size, size, 8); x.clip();
      x.drawImage(s.imgs[i % s.imgs.length], -size / 2, -size / 2, size, size); x.restore();
      x.restore();
    }
    x.globalAlpha = 1;
  }
  if (A && M) {
    s.burstA(x, T, A.x, A.y + 10);
    s.burstB(x, T, M.x, M.y - 30);
    shock(x, M.x, M.y - 30, T, EV.xferDone + 0.05, { dur: 1.0, r0: 60, r1: 520, w: 4, col: C.phos, a: 0.7 });
  }
  wordsIn(s.cw, T, 34.3, { stagger: 0.04, dur: 0.6 });
  wordsOut(s.cw, T, 35.5, { stagger: 0.015 });
});

// ================================================== 6. crypto (36–40)
const LOCK_SVG = `<svg width="260" height="320" viewBox="0 0 260 320" fill="none">
  <defs><linearGradient id="lkg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#3BFFB0"/><stop offset=".45" stop-color="#45E5FF"/><stop offset=".75" stop-color="#9D6BFF"/><stop offset="1" stop-color="#FF4FD8"/></linearGradient>
  <linearGradient id="lkb" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#1c1452"/><stop offset="1" stop-color="#0a0626"/></linearGradient></defs>
  <g class="shackle"><path d="M68 150V102a62 62 0 0 1 124 0v48" stroke="url(#lkg)" stroke-width="22" stroke-linecap="round"/></g>
  <rect x="26" y="140" width="208" height="166" rx="40" fill="url(#lkb)" stroke="url(#lkg)" stroke-width="6"/>
  <circle cx="130" cy="210" r="17" fill="#B8FFE9"/><path d="M122 214h16l5 44h-26z" fill="#B8FFE9"/></svg>`;

scene('crypto', [35.5, 40.3], root => {
  const s = { fx: makeCanvas(root, 1920, 1080, 1) };
  s.kick = h('div', 'kicker abs', root, '03 <b>/</b> Private');
  css(s.kick, { left: '0', width: '1920px', justifyContent: 'center', top: '96px' });
  s.head = h('div', 'h1 center abs mask', root, 'End-to-end <span class="g">encrypted.</span>');
  css(s.head, { left: '0', width: '1920px', top: '136px' });
  s.hw = splitWords(s.head);
  s.lock = h('div', 'abs', root, LOCK_SVG);
  css(s.lock, { left: '830px', top: '330px', width: '260px', height: '320px' });
  s.shackle = s.lock.querySelector('.shackle');
  s.mono = h('div', 'abs center mono', root, 'X25519 key exchange&nbsp;&nbsp;·&nbsp;&nbsp;ChaCha20-Poly1305');
  css(s.mono, { left: '0', width: '1920px', top: '700px', font: "600 24px/1 'JetBrains Mono'", letterSpacing: '.06em', color: '#B8FFE9' });
  s.line = h('div', 'sub center abs mask', root, 'Nothing in between. <b>Not even us.</b>');
  css(s.line, { left: '0', width: '1920px', top: '770px', fontSize: '40px' });
  s.lw = splitWords(s.line);
  s.sas = h('div', 'abs', root, `<span class="tag">◈&nbsp;&nbsp;verify&nbsp;&nbsp;482 917</span><span style="font:500 24px Inter;color:#AEB4D8;margin:0 18px">same code on both screens</span><span class="tag" style="border-color:rgba(59,255,176,.6);color:#3BFFB0">✓</span>`);
  css(s.sas, { left: '0', width: '1920px', top: '868px', display: 'flex', justifyContent: 'center', alignItems: 'center' });
  s.hex = Array.from({ length: 64 }, (_, i) => { const r = rng(i + 77); return Array.from({ length: 4 }, () => '0123456789abcdef'[Math.floor(r() * 16)]).join(''); });
  return s;
}, (T, s) => {
  const x = s.fx.ctx;
  x.clearRect(0, 0, 1920, 1080);
  const out = E.inCubic(ramp(T, 39.5, 39.95));
  const vin = E.outCubic(ramp(T, 35.9, 36.4));
  // packet lanes: plaintext tiles on the left become ciphertext on the right
  const lanesA = clamp(ramp(T, 36.35, 36.9)) * (1 - out);
  if (lanesA > 0) {
    for (let lane = 0; lane < 7; lane++) {
      const y = 372 + lane * 40 + (lane % 2) * 6;
      const speed = 430 + (lane % 3) * 90;
      for (let k = 0; k < 14; k++) {
        const r = rng(lane * 31 + k);
        const px = ((T - 36) * speed + k * 160 + r() * 90 + lane * 57) % 2200 - 140;
        const fade = Math.min(1, px / 220, (1920 - px) / 220);
        if (fade <= 0) continue;
        const a = lanesA * fade * (lane === 0 || lane === 6 ? 0.45 : 0.85);
        const plain = px < 960;
        x.globalAlpha = a;
        if (plain) {
          roundRect(x, px - 18, y - 12, 36, 24, 6);
          const g = x.createLinearGradient(px - 18, y - 12, px + 18, y + 12);
          const c1 = AURORA[(lane + k) % 4], c2 = AURORA[(lane + k + 1) % 4];
          g.addColorStop(0, c1); g.addColorStop(1, c2);
          x.fillStyle = g; x.fill();
          x.fillStyle = 'rgba(3,0,20,0.45)';
          x.beginPath(); x.moveTo(px - 14, y + 9); x.lineTo(px - 3, y - 1); x.lineTo(px + 4, y + 5); x.lineTo(px + 8, y + 1); x.lineTo(px + 15, y + 9); x.closePath(); x.fill();
        } else {
          roundRect(x, px - 30, y - 13, 60, 26, 7);
          x.fillStyle = 'rgba(157,107,255,0.10)'; x.fill();
          x.strokeStyle = 'rgba(157,107,255,0.75)'; x.lineWidth = 1.5; x.stroke();
          x.font = "500 14px 'JetBrains Mono'"; x.fillStyle = '#C9B5FF'; x.textAlign = 'center'; x.textBaseline = 'middle';
          x.fillText(s.hex[(lane * 9 + k) % 64], px, y + 1);
        }
      }
    }
    x.globalAlpha = 1;
  }
  // lock: drops in open, shackle snaps shut on the beat
  const drop = E.outBack(ramp(T, 35.85, 36.35));
  const snap = T < EV.lock ? 0 : E.outBack(ramp(T, EV.lock, EV.lock + 0.14));
  s.shackle.style.transform = `translateY(${lerp(-46, 0, snap).toFixed(2)}px)`;
  const thump = T >= EV.lock ? Math.exp(-(T - EV.lock) * 9) : 0;
  tf(s.lock, { y: (1 - drop) * -120 - out * 30, s: (0.92 + 0.08 * drop) * (1 + 0.06 * thump), o: clamp(ramp(T, 35.85, 36.1)) * (1 - out), blur: out * 10 });
  const glow = 0.25 + 0.75 * (T >= EV.lock ? Math.exp(-(T - EV.lock) * 2.2) : 0) + 0.15 * G.pulse;
  const gg = x.createRadialGradient(960, 500, 0, 960, 500, 300);
  gg.addColorStop(0, rgba(C.teal, 0.32 * glow * (1 - out) * vin)); gg.addColorStop(0.5, rgba(C.violet, 0.16 * glow * (1 - out) * vin)); gg.addColorStop(1, rgba(C.violet, 0));
  x.fillStyle = gg; x.fillRect(560, 100, 800, 800);
  shock(x, 960, 500, T, EV.lock, { dur: 0.9, r0: 120, r1: 760, w: 5, col: C.teal, a: 0.8 });
  shock(x, 960, 500, T, EV.lock + 0.08, { dur: 1.2, r0: 100, r1: 900, w: 3, col: C.violet, a: 0.5 });
  tf(s.kick, { o: E.outCubic(ramp(T, 35.9, 36.3)) * (1 - out) });
  wordsIn(s.hw, T, 36.0, { stagger: 0.09, dur: 0.7 });
  wordsOut(s.hw, T, 39.5, { stagger: 0.02 });
  const m = E.outCubic(ramp(T, 36.9, 37.4));
  tf(s.mono, { o: m * (1 - out), y: (1 - m) * 16, blur: (1 - m) * 6 + out * 6 });
  wordsIn(s.lw, T, 37.45, { stagger: 0.07, dur: 0.6 });
  wordsOut(s.lw, T, 39.5, { stagger: 0.02 });
  const sp = spring(T - EV.sas, 2.2, 0.55), sa = clamp((T - EV.sas) / 0.2);
  tf(s.sas, { y: (1 - sp) * 24, o: sa * (1 - out), blur: out * 6 });
});

// ================================================== 7. delta (40–48)
const DELTA = (() => {
  const r = rng(2024), n = 34, x0 = 520, total = 1180, gap = 6;
  const raw = Array.from({ length: n }, () => 0.55 + r() * 0.9);
  const sum = raw.reduce((a, b) => a + b, 0);
  let x = x0;
  const chunks = raw.map(v => { const w = (v / sum) * (total - gap * (n - 1)); const c = { x, w }; x += w + gap; return c; });
  return { chunks, changed: [6, 17, 27], yS: 420, yR: 690, hgt: 74 };
})();
const STREAM = (() => {
  const S = { t0: 43.98, dt: 0.045, fly: 0.26, resume: 45.55, rdt: 0.05 };
  S.first = i => S.t0 + i * S.dt;
  S.lastFirst = Math.floor((EV.drop - S.t0) / S.dt - 1e-6);
  let safe = 0; while (S.first(safe) + S.fly <= EV.drop) safe++;
  S.safe = safe;
  S.second = i => S.resume + (i - safe) * S.rdt;
  S.arrive = i => (i < safe ? S.first(i) : S.second(i)) + S.fly;
  S.pct = T => Math.round((100 * Array.from({ length: 34 }, (_, i) => T >= S.arrive(i)).filter(Boolean).length) / 34);
  return S;
})();

scene('delta', [39.5, 48.3], root => {
  const s = { fx: makeCanvas(root, 1920, 1080, 1) };
  s.kick = h('div', 'kicker abs', root, '04 <b>/</b> Smart resend');
  css(s.kick, { left: '0', width: '1920px', justifyContent: 'center', top: '92px' });
  const mk = html => { const e = h('div', 'h2 center abs mask', root, html); css(e, { left: '0', width: '1920px', top: '136px' }); return { e, w: splitWords(e) }; };
  s.heads = [
    [mk('Edited a 2 GB video?'), 40.1, 41.6],
    [mk('Only the <span class="g">changes</span> travel.'), 41.98, 44.08],
    [mk('Wi-Fi dropped?'), EV.drop, 45.18],
    [mk('It picks up <span class="g">where it left off.</span>'), EV.reconnect, 47.55],
  ];
  const lab = (y, icon, name) => { const e = h('div', 'rowlabel', root, `${icon}<div>${name}<small class="fname"></small></div>`); css(e, { right: (1920 - 488) + 'px', top: (y + 10) + 'px', justifyContent: 'flex-end', textAlign: 'right' }); return e; };
  s.labS = lab(DELTA.yS, ICON.arrowUp('#3BFFB0', 26), 'Pixel 9');
  s.labR = lab(DELTA.yR, ICON.arrowDown('#45E5FF', 26), 'MacBook Pro');
  s.fnS = s.labS.querySelector('.fname'); s.fnR = s.labR.querySelector('.fname');
  s.status = h('div', 'statusline', root);
  css(s.status, { left: '0', width: '1920px', textAlign: 'center', top: '826px' });
  s.bad = h('div', 'alert bad', root, '✕&nbsp;&nbsp;Wi-Fi lost');
  s.good = h('div', 'alert good', root, '✓&nbsp;&nbsp;Reconnected');
  s.afterLayout = () => {
    css(s.bad, { left: (960 - s.bad.offsetWidth / 2) + 'px', top: '575px' });
    css(s.good, { left: (960 - s.good.offsetWidth / 2) + 'px', top: '575px' });
  };
  return s;
}, (T, s, root) => {
  const x = s.fx.ctx;
  x.clearRect(0, 0, 1920, 1080);
  const { chunks, changed, yS, yR, hgt } = DELTA;
  const out = E.inCubic(ramp(T, 47.55, 48.05));
  const stream = T >= 43.92;
  const vis = 1 - out;
  tf(s.kick, { o: E.outCubic(ramp(T, 39.9, 40.3)) * vis });
  s.heads.forEach(([hd, t0, t1]) => { if (T < t0 - 0.05 || T > t1 + 0.5) { hd.w.forEach(w => op(w, 0)); return; } wordsIn(hd.w, T, t0, { stagger: 0.07, dur: 0.6 }); wordsOut(hd.w, T, t1, { stagger: 0.015, dur: 0.28, dy: -0.3 }); });
  const fname = stream ? 'trip-4k.mov · 3.8 GB' : 'final-cut.mov · 2.1 GB';
  if (s.fnS.textContent !== fname) { s.fnS.textContent = fname; s.fnR.textContent = stream ? 'receiving…' : 'has the old version'; }
  const labA = E.outCubic(ramp(T, 40.0, 40.4)) * vis;
  tf(s.labS, { o: labA, x: (1 - labA) * -30 }); tf(s.labR, { o: labA, x: (1 - labA) * -30 });
  // glitch state
  const g0 = EV.drop, glitch = T >= g0 && T < g0 + 0.42 ? 1 - ramp(T, g0, g0 + 0.42) : 0;
  const dead = T >= g0 && T < EV.reconnect ? 1 : 0;
  if (glitch > 0) {
    const j = (noise1(T * 60, 4) - 0.5) * 24 * glitch;
    root.style.filter = `drop-shadow(${(8 * glitch).toFixed(1)}px 0 0 rgba(255,79,216,.75)) drop-shadow(${(-8 * glitch).toFixed(1)}px 0 0 rgba(69,229,255,.75))`;
    root.firstChild.style.transform = `translateX(${j.toFixed(1)}px)`;
  } else {
    root.style.filter = dead ? 'saturate(0.45) brightness(0.82)' : 'none';
    root.firstChild.style.transform = '';
  }
  const col = u => { const k = clamp(u) * 3, i = Math.min(2, Math.floor(k)), f = k - i; return [AURORA[i], AURORA[i + 1], f]; };
  const lerpCol = (a, b, f) => { const pa = parseInt(a.slice(1), 16), pb = parseInt(b.slice(1), 16); const m = sh => Math.round(lerp((pa >> sh) & 255, (pb >> sh) & 255, f)); return `rgb(${m(16)},${m(8)},${m(0)})`; };
  const fill = (cx, y, w, hh, style, a, glow = 0) => {
    if (a <= 0) return;
    x.globalAlpha = a * vis;
    roundRect(x, cx, y, w, hh, 9);
    if (glow > 0) { x.shadowColor = typeof style === 'string' ? style : C.teal; x.shadowBlur = 24 * glow; }
    x.fillStyle = style; x.fill();
    x.shadowBlur = 0;
  };
  const dashed = (cx, y, w, hh, a) => {
    if (a <= 0) return;
    x.globalAlpha = a * vis;
    roundRect(x, cx + 1, y + 1, w - 2, hh - 2, 9);
    x.setLineDash([6, 5]); x.strokeStyle = 'rgba(139,147,187,0.55)'; x.lineWidth = 1.5; x.stroke(); x.setLineDash([]);
  };
  // ---- sender row
  chunks.forEach((c, i) => {
    const a = E.outCubic(ramp(T, 40.0 + i * 0.012, 40.3 + i * 0.012));
    const sy = lerp(0.2, 1, a);
    const [c1, c2, f] = col((c.x - 520) / 1180);
    let style = lerpCol(c1, c2, f), glow = 0;
    const reset = stream ? E.outCubic(ramp(T, 43.92, 44.05)) : 1;
    if (!stream && changed.includes(i) && T >= EV.edited) {
      const pul = 0.5 + 0.5 * Math.sin((T - EV.edited) * 14);
      style = C.pink; glow = 0.6 + 0.4 * pul;
    }
    fill(c.x, yS + (hgt * (1 - sy)) / 2, c.w, hgt * sy, style, a * (stream ? 0.55 + 0.45 * reset : 1) * 0.92, glow);
  });
  // ---- receiver row + flights
  const land = (i, t) => T >= t;
  chunks.forEach((c, i) => {
    const a = E.outCubic(ramp(T, 40.25 + i * 0.012, 40.6 + i * 0.012));
    if (!stream) {
      const isChanged = changed.includes(i);
      if (!isChanged) { fill(c.x, yR, c.w, hgt, 'rgba(59,255,176,0.20)', a); x.globalAlpha = a * vis * 0.55; x.strokeStyle = 'rgba(59,255,176,0.6)'; x.lineWidth = 1; roundRect(x, c.x + 0.5, yR + 0.5, c.w - 1, hgt - 1, 9); x.stroke(); }
      else {
        const k = changed.indexOf(i), tl = EV.chunkFly[k] + 0.45;
        if (!land(i, tl)) dashed(c.x, yR, c.w, hgt, a);
        else { const fl = Math.exp(-(T - tl) * 4); fill(c.x, yR, c.w, hgt, C.teal, 1, 0.5 + fl); }
      }
      if (T > EV.deltaDone && T < 43.95) {
        const gl = env(T, EV.deltaDone, 43.95, 0.15, 0.4);
        fill(c.x, yR, c.w, hgt, C.phos, 0.25 * gl);
      }
    } else {
      // stream: first attempt, the drop kills in-flight chunks, resume from the first missing one
      const S = STREAM, arrive = S.arrive(i);
      if (T >= arrive) { const fl = Math.exp(-(T - arrive) * 5); fill(c.x, yR, c.w, hgt, C.teal, 0.92, 0.3 + fl * 0.8); }
      else dashed(c.x, yR, c.w, hgt, 1);
      const flight = (t0, col = C.cyan) => {
        const q = (T - t0) / S.fly;
        if (q <= 0 || q >= 1) return;
        fill(c.x, lerp(yS + hgt, yR, E.inOutSine(q)) - 14, c.w, 28, col, 0.95, 0.7);
      };
      if (i <= S.lastFirst) {
        if (i < S.safe || T < EV.drop) flight(S.first(i));
        else if (T < EV.drop + 0.6) {
          const q0 = clamp((EV.drop - S.first(i)) / S.fly), py0 = lerp(yS + hgt, yR, E.inOutSine(q0));
          const f = ramp(T, EV.drop, EV.drop + 0.6);
          fill(c.x, py0 - 14 + 130 * f * f, c.w, 28, C.danger, 0.9 * (1 - f), 0.5);
        }
      }
      if (i >= S.safe) flight(S.second(i));
    }
  });
  // delta flights (3 edited chunks)
  if (!stream) changed.forEach((ci, k) => {
    const c = chunks[ci], t0 = EV.chunkFly[k], q = (T - t0) / 0.45;
    if (q <= 0 || q >= 1) return;
    const e = E.inOutCubic(q);
    const py = lerp(yS, yR, e), sc = 1 + 0.18 * Math.sin(Math.PI * q);
    x.globalAlpha = vis;
    for (let tr = 1; tr <= 3; tr++) { const e2 = E.inOutCubic(Math.max(0, q - tr * 0.05)); fill(c.x, lerp(yS, yR, e2), c.w, hgt, C.pink, 0.16 * (4 - tr)); }
    fill(c.x - (c.w * (sc - 1)) / 2, py, c.w * sc, hgt, C.pink, 1, 1);
  });
  x.globalAlpha = 1;
  // link beam between the rows during streaming
  if (stream) {
    const alive = T < EV.drop || T >= EV.reconnect;
    const flick = T >= EV.drop && T < EV.drop + 0.25 ? (noise1(T * 80, 1) > 0.5 ? 1 : 0) : alive ? 1 : 0;
    const ga = 0.10 * flick * vis * (T < 47.35 ? 1 : 1 - ramp(T, 47.35, 47.7));
    const g = x.createLinearGradient(0, yS + hgt, 0, yR);
    g.addColorStop(0, rgba(C.teal, ga)); g.addColorStop(0.5, rgba(C.cyan, ga * 0.4)); g.addColorStop(1, rgba(C.teal, ga));
    x.fillStyle = g; x.fillRect(520, yS + hgt + 6, 1180, yR - yS - hgt - 12);
  }
  shock(x, 960, 600, T, EV.reconnect, { dur: 1.1, r0: 40, r1: 700, w: 4, col: C.teal, a: 0.8 });
  // alerts
  const bA = env(T, EV.drop, EV.reconnect, 0.12, 0.15);
  tf(s.bad, { s: 0.8 + 0.2 * E.outBack(ramp(T, EV.drop, EV.drop + 0.3)), o: bA * vis, x: glitch * (noise1(T * 50, 7) - 0.5) * 30 });
  const gA = env(T, EV.reconnect, 47.0, 0.15, 0.4);
  tf(s.good, { s: 0.8 + 0.2 * E.outBack(ramp(T, EV.reconnect, EV.reconnect + 0.3)), o: gA * vis });
  // status line
  let st = '';
  if (T >= 40.85 && T < 41.5) st = 'Δ&nbsp;&nbsp;<b>31 of 34</b> chunks are already on MacBook Pro';
  else if (T >= 41.5 && T < EV.deltaDone) {
    const n = EV.chunkFly.filter(t => T >= t + 0.45).length;
    st = `↑ <i>${(n * 12.7).toFixed(1)} MB</i> sent&nbsp;&nbsp;·&nbsp;&nbsp;<b>2.06 GB</b> already there`;
  } else if (T >= EV.deltaDone && T < 43.92) st = '✓&nbsp;&nbsp;<b>38.1 MB</b> sent instead of <b>2.1 GB</b>';
  else if (T >= 44.0 && T < EV.drop) st = `↑ <i>${STREAM.pct(T)}%</i>`;
  else if (T >= EV.drop && T < EV.reconnect) st = `⏸&nbsp;&nbsp;paused at <b>${STREAM.pct(EV.drop)}%</b> — received chunks are kept`;
  else if (T >= EV.reconnect && T < EV.resumeDone) st = `↻&nbsp;&nbsp;resuming from <b>${STREAM.pct(EV.drop)}%</b> → <i>${STREAM.pct(T)}%</i>`;
  else if (T >= EV.resumeDone) st = '✓&nbsp;&nbsp;<b>trip-4k.mov</b> done — nothing sent twice';
  if (s.lastSt !== st) { s.status.innerHTML = st; s.lastSt = st; }
  const sa = (T >= 40.85 ? 1 : 0) * vis;
  op(s.status, sa);
});
