// Scenes 8–11: share to anyone, wireless drive, pillars, outro + finishing layers.
'use strict';

// ================================================ 8. anyone (48–56)
scene('anyone', [47.6, 56.3], root => {
  const s = { fx: makeCanvas(root, 1920, 1080, 1) };
  const L = 150;
  s.kick = h('div', 'kicker abs', root, '05 <b>/</b> Share to anyone');
  css(s.kick, { left: L + 'px', top: '296px' });
  s.head = h('div', 'h1 abs mask', root, 'No app?<br><span class="g">No problem.</span>');
  css(s.head, { left: L + 'px', top: '346px' });
  s.hw = splitWords(s.head);
  s.sub = h('div', 'sub abs mask', root, HAS_VO ? 'Anyone nearby can scan the code<br>and download right in their browser.' : 'Send to any phone or laptop. They scan<br>a QR code and download in the browser.');
  css(s.sub, { left: L + 'px', top: '572px', width: '660px' });
  s.sw = splitWords(s.sub);
  s.chips = ['any camera', 'any browser', 'no sign-up'].map((t, i) => {
    const c = h('div', 'xchip abs', root, `<span class="x ok">✓</span>${t}`);
    css(c, { left: (L + [0, 262, 530][i]) + 'px', top: '716px', fontSize: '24px' });
    return c;
  });
  return s;
}, (T, s) => {
  const out = E.inCubic(ramp(T, 55.45, 55.9));
  tf(s.kick, { o: E.outCubic(ramp(T, 48.3, 48.7)) * (1 - out), x: -out * 50 });
  wordsIn(s.hw, T, 48.45, { stagger: 0.09, dur: 0.8 });
  wordsOut(s.hw, T, 55.45, { stagger: 0.02, dy: 0 });
  wordsIn(s.sw, T, 49.2, { stagger: 0.03, dur: 0.7 });
  wordsOut(s.sw, T, 55.4, { stagger: 0.01, dy: 0 });
  s.chips.forEach((c, i) => {
    const t0 = 50.0 + i * 0.25, p = spring(T - t0, 2.3, 0.5), a = clamp((T - t0) / 0.2);
    tf(c, { y: (1 - p) * 26, s: 0.85 + 0.15 * p, o: a * (1 - out), x: -out * 50, blur: out * 6 });
  });
  // light bridge between the phones: scan lock, then the downloads flowing over Wi-Fi
  const x = s.fx.ctx;
  x.clearRect(0, 0, 1920, 1080);
  const A = ACT.android.cur, F = ACT.friend.cur;
  if (!A || !F) return;
  const ax = A.x + 120, ay = A.y - 60, fx = F.x - 120, fy = F.y - 40;
  const scan = env(T, EV.scanLock - 0.1, EV.scanLock + 0.6, 0.08, 0.45);
  if (scan > 0) {
    const g = x.createLinearGradient(ax, 0, fx, 0);
    g.addColorStop(0, rgba(C.teal, 0.0)); g.addColorStop(0.5, rgba('#FFD60A', 0.55 * scan)); g.addColorStop(1, rgba('#FFD60A', 0.0));
    x.strokeStyle = g; x.lineWidth = 3; x.beginPath(); x.moveTo(ax, ay); x.lineTo(fx, fy); x.stroke();
  }
  const flow = env(T, 51.65, 53.6, 0.2, 0.3);
  if (flow > 0) {
    for (let i = 0; i < 26; i++) {
      const u = ((T - 51.6) * 1.4 + i / 26) % 1;
      const r = rng(i + 400);
      const px = lerp(ax, fx, u), py = lerp(ay, fy, u) - Math.sin(Math.PI * u) * (40 + r() * 60) + (r() - 0.5) * 30;
      const a = flow * Math.sin(Math.PI * u);
      const gg = x.createRadialGradient(px, py, 0, px, py, 10);
      gg.addColorStop(0, rgba('#ffffff', a)); gg.addColorStop(0.35, rgba(AURORA[i % 4], 0.6 * a)); gg.addColorStop(1, rgba(AURORA[i % 4], 0));
      x.fillStyle = gg; x.beginPath(); x.arc(px, py, 10, 0, TAU); x.fill();
    }
  }
  shock(x, F.x, F.y - 120, T, EV.delivered, { dur: 0.9, r0: 40, r1: 420, w: 4, col: C.teal, a: 0.7 });
});

// ================================================ 9. drive (56–64)
const DRV = { finder: { x: 930, y: 498, s: 0.78 }, explorer: { x: 1468, y: 700, s: 0.70 }, player: { x: 1150, y: 596, s: 0.80 } };
scene('drive', [55.4, 63.2], root => {
  const s = { fx: makeCanvas(root, 1920, 1080, 1) };
  const L = 150;
  s.kick = h('div', 'kicker abs', root, '06 <b>/</b> Wireless drive');
  css(s.kick, { left: L + 'px', top: '300px' });
  s.head = h('div', 'h2 abs mask', root, 'Turn your phone into<br>a <span class="g">wireless drive.</span>');
  css(s.head, { left: L + 'px', top: '348px' });
  s.hw = splitWords(s.head);
  s.sub = h('div', 'sub abs mask', root, HAS_VO ? '' : 'Other devices on your Wi-Fi can browse, stream<br>and download, like plugging in a USB stick.');
  css(s.sub, { left: L + 'px', top: '552px', width: '820px' });
  s.sw = splitWords(s.sub);
  const top = html => { const e = h('div', 'h3 center abs mask', root, html); css(e, { left: '0', width: '1920px', top: '66px' }); return { e, w: splitWords(e) }; };
  s.t1 = top('Browse it from <span class="g">any computer.</span>');
  s.t2 = top('Stream videos <span class="g">without copying.</span>');
  s.pills = h('div', 'abs', root, ['Finder', 'File Explorer', 'any browser'].map(t => `<span class="tag" style="font-size:18px">${t}</span>`).join('<span style="width:14px;display:inline-block"></span>'));
  css(s.pills, { left: '0', width: '1920px', top: '150px', display: 'flex', justifyContent: 'center' });
  const win = (b, left, top, z) => { const w = b(root); css(w.el, { left: left + 'px', top: top + 'px', zIndex: z }); return w; };
  s.finder = win(buildFinder, DRV.finder.x - 540, DRV.finder.y - 330, 2);
  s.explorer = win(buildExplorer, DRV.explorer.x - 570, DRV.explorer.y - 345, 3);
  s.player = win(buildPlayer, DRV.player.x - 450, DRV.player.y - 280, 4);
  s.cursor = h('div', 'cursor', root, `<svg width="26" height="38" viewBox="0 0 26 38"><path d="M2 2v30l7.5-7 5 11 5-2.2-5-10.8H25z" fill="#fff" stroke="#000" stroke-width="2" stroke-linejoin="round"/></svg>`);
  css(s.cursor, { zIndex: 6 });
  s.afterLayout = () => { const o = offsetIn(s.finder.video, s.finder.el); s.vidXY = { x: o.x + o.w / 2, y: o.y + o.h * 0.35 }; };
  return s;
}, (T, s) => {
  const out1 = E.inCubic(ramp(T, 57.35, 57.75));
  tf(s.kick, { o: E.outCubic(ramp(T, 56.15, 56.55)) * (1 - out1), x: -out1 * 50 });
  wordsIn(s.hw, T, 56.3, { stagger: 0.08, dur: 0.75 });
  wordsOut(s.hw, T, 57.35, { stagger: 0.015, dy: 0 });
  wordsIn(s.sw, T, 56.75, { stagger: 0.025, dur: 0.6 });
  wordsOut(s.sw, T, 57.3, { stagger: 0.008, dy: 0 });
  const end = E.inCubic(ramp(T, 62.5, 63.0));
  if (T < 60.62) { wordsIn(s.t1.w, T, 58.2, { stagger: 0.08, dur: 0.7 }); wordsOut(s.t1.w, T, 60.45, { stagger: 0.02 }); s.t2.w.forEach(w => op(w, 0)); }
  else { s.t1.w.forEach(w => op(w, 0)); wordsIn(s.t2.w, T, 60.65, { stagger: 0.08, dur: 0.7 }); wordsOut(s.t2.w, T, 62.45, { stagger: 0.02 }); }
  const pa = E.outCubic(ramp(T, 58.6, 59.0)) * (1 - end);
  tf(s.pills, { o: pa, y: (1 - pa) * 14 });
  // floating OS windows
  const win = (w, t0, sc, ry, extra = 0) => {
    const p = E.outQuart(ramp(T, t0, t0 + 0.7)), a = clamp((T - t0) / 0.25);
    const drift = Math.sin(T * 0.6 + extra) * 6;
    tf(w.el, { y: (1 - p) * 70 + drift + end * 40, p: 2000, ry: lerp(ry * 2.4, ry, p) + Math.sin(T * 0.4 + extra) * 1.2, s: sc * lerp(0.86, 1, p), o: a * (1 - end), blur: (1 - p) * 10 + end * 12 });
  };
  win(s.finder, EV.finder, DRV.finder.s, -5, 0);
  win(s.explorer, EV.explorer, DRV.explorer.s, 5, 1.7);
  // cursor double-clicks the movie in Finder
  const fz = DRV.finder.s, fo = { x: DRV.finder.x - 540 * fz, y: DRV.finder.y - 330 * fz };
  const vx = fo.x + s.vidXY.x * fz, vy = fo.y + s.vidXY.y * fz;
  const cm = E.inOutCubic(ramp(T, 59.85, 60.38));
  css(s.cursor, { left: lerp(1340, vx, cm) + 'px', top: lerp(880, vy, cm) + 'px' });
  const clickP = env(T, EV.dblClick - 0.04, EV.dblClick + 0.24, 0.04, 0.1);
  tf(s.cursor, { o: env(T, 59.75, 61.0, 0.15, 0.3), s: 1 - 0.1 * clickP });
  s.finder.video.classList.toggle('sel', T >= EV.dblClick - 0.02 && T < 61.2);
  // player pops out of the thumbnail and plays (streamed over the LAN)
  const pp = E.outBack(ramp(T, EV.player, EV.player + 0.5)), pa2 = clamp((T - EV.player) / 0.18);
  const pz = DRV.player.s * lerp(0.25, 1, pp);
  const pcx = DRV.player.x, pcy = DRV.player.y;
  const ox = lerp(vx, pcx, E.outCubic(ramp(T, EV.player, EV.player + 0.45))), oy = lerp(vy, pcy, E.outCubic(ramp(T, EV.player, EV.player + 0.45)));
  tf(s.player.el, { x: ox - pcx, y: oy - pcy + end * 40, s: pz, o: pa2 * (1 - end), blur: end * 12 });
  if (T >= EV.player) {
    const lt = T - EV.player + 12;
    const c = s.player.cv;
    drawVideoFrame(c.ctx, c.width, c.height, lt);
    s.player.bar.style.width = ((lt / 221) * 100).toFixed(2) + '%';
    s.player.buf.style.width = Math.min(100, (lt / 221) * 100 + 9 + (T - EV.player) * 3).toFixed(2) + '%';
    const tc = `${Math.floor(lt / 60)}:${String(Math.floor(lt % 60)).padStart(2, '0')}`;
    if (s.player.tc.textContent !== tc) s.player.tc.textContent = tc;
  }
  // Wi-Fi links from the phone to the computers
  const x = s.fx.ctx;
  x.clearRect(0, 0, 1920, 1080);
  const A = ACT.android.cur;
  const linkA = env(T, EV.finder, 62.8, 0.4, 0.4);
  if (A && linkA > 0) {
    const src = { x: A.x + 140, y: A.y - 80 };
    const dsts = [{ x: DRV.finder.x - 400, y: DRV.finder.y - 40, t: EV.finder }, { x: DRV.explorer.x - 380, y: DRV.explorer.y - 20, t: EV.explorer }];
    dsts.forEach((d, j) => {
      const la = linkA * clamp((T - d.t) / 0.3);
      if (la <= 0) return;
      for (let i = 0; i < 9; i++) {
        const u = ((T - d.t) * 0.8 + i / 9) % 1;
        const px = lerp(src.x, d.x, u), py = lerp(src.y, d.y, u) - Math.sin(Math.PI * u) * 50;
        const a = la * Math.sin(Math.PI * u) * 0.9;
        const gg = x.createRadialGradient(px, py, 0, px, py, 9);
        gg.addColorStop(0, rgba('#ffffff', a)); gg.addColorStop(0.4, rgba(j ? C.cyan : C.teal, 0.6 * a)); gg.addColorStop(1, rgba(C.teal, 0));
        x.fillStyle = gg; x.beginPath(); x.arc(px, py, 9, 0, TAU); x.fill();
      }
    });
  }
});

// ============================================== 10. pillars (64–68)
scene('pillars', [62.7, 68.3], root => {
  const s = { fx: makeCanvas(root, 1920, 1080, 1) };
  s.lines = ['No cloud.', 'No accounts.', 'No ads.', 'No tracking.'].map(t => {
    const e = h('div', 'h1 center abs', root, t);
    css(e, { left: '0', width: '1920px', top: '482px', fontSize: '104px' });
    return e;
  });
  s.final = h('div', 'abs center', root, '<span class="g" style="padding:0 .05em">Just drop it.</span>');
  css(s.final, { left: '0', width: '1920px', top: '452px', font: "800 160px/1.05 Sora", letterSpacing: '-0.045em' });
  s.burst = makeBurst({ seed: 66, n: 120, t0: EV.justDrop, x: 960, y: 540, speed: [300, 1400], life: [0.6, 1.5], size: [1, 4], drag: 2.4 });
  return s;
}, (T, s) => {
  const x = s.fx.ctx;
  x.clearRect(0, 0, 1920, 1080);
  const col = E.inQuart(ramp(T, EV.justDrop - 0.12, EV.justDrop + 0.12));
  s.lines.forEach((e, i) => {
    const t0 = EV.words[i];
    if (T < t0 - 0.02) { op(e, 0); return; }
    const p = E.outQuart(ramp(T, t0, t0 + 0.32));
    let slot = 0;
    for (let j = i + 1; j < 4; j++) slot += E.outQuart(ramp(T, EV.words[j], EV.words[j] + 0.32));
    const newest = 1 - clamp(slot);
    const y = (1 - p) * 90 - slot * 118 - col * 300;
    tf(e, { y, s: (0.88 + 0.12 * newest) * lerp(1.25, 1, p), o: p * lerp(0.28, 1, newest) * (1 - col), blur: (1 - p) * 14 + col * 14 });
  });
  const f = E.outExpo(ramp(T, EV.justDrop, EV.justDrop + 0.4));
  const fo = E.inCubic(ramp(T, 67.55, 68.05));
  tf(s.final, { s: lerp(1.7, 1, f) * (1 - 0.12 * fo), o: clamp((T - EV.justDrop) / 0.08) * (1 - fo), blur: (1 - f) * 24 + fo * 10 });
  s.final.style.filter = (s.final.style.filter === 'none' ? '' : s.final.style.filter) + ` drop-shadow(0 0 ${30 + 30 * Math.exp(-(T - EV.justDrop) * 2)}px rgba(69,229,255,0.45))`;
  s.burst(x, T);
  shock(x, 960, 540, T, EV.justDrop, { dur: 1.0, r0: 80, r1: 1300, w: 8, col: C.teal });
  shock(x, 960, 540, T, EV.justDrop + 0.08, { dur: 1.3, r0: 60, r1: 1500, w: 5, col: C.pink, a: 0.6 });
  // beat flashes behind each word
  EV.words.forEach((t0, i) => shock(x, 960, 540, T, t0, { dur: 0.7, r0: 200, r1: 900, w: 3, col: AURORA[i], a: 0.45 }));
});

// ============================================== 11. outro (68–76)
scene('outro', [67.6, 76.01], root => {
  const s = { fx: makeCanvas(root, 1920, 1080, 1) };
  s.icon = h('div', 'appicon abs', root, APP_ICON_SVG);
  css(s.icon, { width: '156px', height: '156px', top: '176px' });
  s.word = h('div', 'wordmark abs mask', root);
  css(s.word, { fontSize: '120px', top: '196px' });
  'CrossDrop'.split('').forEach(ch => { const m = h('span', 'wm', s.word); h('span', 'w g', m, ch); });
  s.chars = [...s.word.querySelectorAll('.w')];
  s.tag = h('div', 'sub center abs mask', root, 'Share anything with any device nearby.');
  css(s.tag, { left: '0', width: '1920px', top: '392px', fontSize: '36px', color: '#C3C8EA' });
  s.tw = splitWords(s.tag);
  s.plat = h('div', 'abs center', root, 'Available on&nbsp;&nbsp;<b>Android</b>&nbsp;&nbsp;·&nbsp;&nbsp;<b>iOS</b>&nbsp;&nbsp;·&nbsp;&nbsp;<b>macOS</b>&nbsp;&nbsp;·&nbsp;&nbsp;<b>Windows</b>');
  css(s.plat, { left: '0', width: '1920px', top: '470px', font: "500 27px/1 Inter", color: '#8B93BB', letterSpacing: '.01em' });
  s.names = [...s.plat.querySelectorAll('b')];
  s.names.forEach(b => css(b, { color: '#EEF2FF', fontWeight: '600' }));
  s.afterLayout = () => {
    const ww = s.word.offsetWidth, total = 156 + 40 + ww, left = (1920 - total) / 2;
    css(s.icon, { left: left + 'px' });
    css(s.word, { left: (left + 196) + 'px' });
    s.iconC = { x: left + 78, y: 254 };
  };
  return s;
}, (T, s) => {
  const x = s.fx.ctx;
  x.clearRect(0, 0, 1920, 1080);
  const ip = spring(T - 68.1, 1.8, 0.5), ia = clamp((T - 68.1) / 0.2);
  tf(s.icon, { s: 0.5 + 0.5 * ip, o: ia, r: (1 - ip) * -12 });
  s.chars.forEach((c, i) => {
    const p = E.outQuart(ramp(T, 68.3 + i * 0.04, 68.95 + i * 0.04));
    tf(c, { y: (1 - p) * 120, o: p, blur: (1 - p) * 6 });
  });
  s.word.style.filter = `drop-shadow(0 0 ${(18 + 16 * G.pulse).toFixed(1)}px rgba(69,229,255,0.35))`;
  wordsIn(s.tw, T, 69.0, { stagger: 0.06, dur: 0.7 });
  const pl = E.outCubic(ramp(T, Math.min(69.8, EV.outroNames[0] - 0.4), Math.min(70.3, EV.outroNames[0] + 0.1)));
  tf(s.plat, { o: pl, y: (1 - pl) * 18 });
  s.names.forEach((b, i) => {
    const t0 = EV.outroNames[i];
    const k = T >= t0 ? Math.exp(-(T - t0) * 2.5) : 0;
    b.style.textShadow = k > 0.01 ? `0 0 ${(24 * k).toFixed(1)}px rgba(59,255,176,${(0.9 * k).toFixed(3)})` : 'none';
    b.style.color = k > 0.01 ? `rgb(${Math.round(lerp(238, 59, k))},${Math.round(lerp(242, 255, k))},${Math.round(lerp(255, 176, k))})` : '#EEF2FF';
  });
  if (s.iconC) {
    for (const [t0, col, a] of [[68.1, C.teal, 0.6], [EV.lastPing, C.teal, 0.9], [EV.lastPing + 0.1, C.violet, 0.6], [EV.lastPing + 0.22, C.pink, 0.4]])
      shock(x, s.iconC.x, s.iconC.y, T, t0, { dur: 2.2, r0: 80, r1: 2000, w: 5, col, a });
    const g = env(T, EV.lastPing, 75, 0.1, 2.5);
    if (g > 0) {
      const rg = x.createRadialGradient(s.iconC.x, s.iconC.y, 0, s.iconC.x, s.iconC.y, 300);
      rg.addColorStop(0, rgba(C.teal, 0.25 * g)); rg.addColorStop(1, rgba(C.teal, 0));
      x.fillStyle = rg; x.fillRect(s.iconC.x - 300, s.iconC.y - 300, 600, 600);
    }
  }
});

// ============================================== finishing layers
layer('vignette', root => { h('div', 'vignette', root); return {}; }, () => {}, 80, { noShake: true });
layer('grain', root => { initGrain(); return { c: makeCanvas(root, 1920, 1080, 1) }; }, (T, s) => drawGrain(s.c.ctx, 1920, 1080, STAGE.frame, 0.032), 90, { noShake: true });
const FLASHES = [[EV.impact, 0.9, 0.55], [EV.justDrop, 0.35, 0.4], [EV.finale, 0.25, 0.5]];
layer('flash', root => ({ el: h('div', 'flash', root) }), (T, s) => {
  let a = 0;
  for (const [t0, k, d] of FLASHES) if (T >= t0 && T < t0 + d) a = Math.max(a, k * (1 - (T - t0) / d) ** 2);
  op(s.el, a);
}, 95, { noShake: true });
layer('fade', root => ({ el: h('div', 'abs', root) }), (T, s) => {
  css(s.el, { inset: '0', background: '#000' });
  op(s.el, kf(T, [[0, 1], [0.45, 0, E.outSine], [74.4, 0], [76, 1, E.inSine]]));
}, 99, { noShake: true });
