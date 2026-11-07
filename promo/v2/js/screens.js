// What every device's screen shows, as a pure function of T.
'use strict';

const LINEUP_PEERS = {
  android: ["Maya's iPhone", 'MacBook Pro', 'Studio PC'],
  iphone: ['Pixel 9', 'MacBook Pro', 'Studio PC'],
  mac: ['Pixel 9', "Maya's iPhone", 'Studio PC'],
  win: ['Pixel 9', "Maya's iPhone", 'MacBook Pro'],
};
const LINEUP_GEOM = [{ ang: -48, dist: 0.66 }, { ang: 200, dist: 0.62 }, { ang: 120, dist: 0.58 }];
function lineupPeers(dev, t0) {
  return LINEUP_PEERS[dev].map((name, i) => ({ name, ...LINEUP_GEOM[i], at: t0 + i * 0.35, seed: i + 7 }));
}
const PEERS = {
  android2: lineupPeers('android', EV.beams + 0.1),
  iphone2: lineupPeers('iphone', EV.beams + 0.25),
  mac2: lineupPeers('mac', EV.beams + 0.05),
  win2: lineupPeers('win', EV.beams + 0.4),
  android10: lineupPeers('android', 69.0), iphone10: lineupPeers('iphone', 69.2),
  mac10: lineupPeers('mac', 68.9), win10: lineupPeers('win', 69.3),
  macSend: [{ name: 'Pixel 9', ang: -40, dist: 0.6, at: 0, seed: 1 }, { name: "Maya's iPhone", ang: 205, dist: 0.66, at: 0, seed: 2 }, { name: 'Studio PC', ang: 140, dist: 0.55, at: 0, seed: 3 }],
};
const RADAR_PEERS_S4 = RADAR_PEERS.map(p => ({ ...p, at: 0, tap: p.name === 'MacBook Pro' ? EV.tapPeer : undefined }));

const XFER_TOTAL = 486.3;
function xferLevel(T, lag = 0) {
  const q = ramp(T, EV.xferStart + lag, EV.xferDone - 0.04 + lag);
  return clamp(0.18 * E.inOutSine(q) + 0.82 * q);
}
const xferSpeed = T => 108 + 5.5 * Math.sin(T * 3.1) + 3 * (noise1(T * 4, 5) - 0.5);
const pxl = i => `PXL_20261101_1845${String(12 + i * 7).padStart(2, '0')}.jpg`;

function offsetIn(el, anc) {
  let x = 0, y = 0, e = el;
  while (e && e !== anc) { x += e.offsetLeft; y += e.offsetTop; e = e.offsetParent; }
  return { x, y, w: el.offsetWidth, h: el.offsetHeight };
}

/** Touch indicator: press scale + ripple ring, centered at (x, y). */
function touchAt(el, T, t0, x, y) {
  const a = env(T, t0 - 0.16, t0 + 0.32, 0.12, 0.2);
  if (a <= 0) { op(el, 0); return; }
  const press = T < t0 ? E.outCubic(ramp(T, t0 - 0.16, t0)) : 1;
  css(el, { left: x + 'px', top: y + 'px' });
  tf(el, { s: lerp(1.25, 0.86, press) + (T > t0 ? 0.25 * E.outCubic(ramp(T, t0, t0 + 0.3)) : 0), o: a });
}

function buildScreens() {
  const A = ACT.android.app, I = ACT.iphone.app, M = ACT.mac.app, Wn = ACT.win.app, F = ACT.friend;

  // ---- Android extras
  buildChooser(A, 'MacBook Pro');
  buildXfer(A, { dir: 'up', peer: 'MacBook Pro', sas: '482 917', file: pxl(0), total: XFER_TOTAL + ' MB' });
  buildQrShare(A, SHARE_URL, SHARE_FILES);
  buildDrive(A, DRIVE_URL, '4815');
  A.app.appendChild(A.touch); // keep touch on top

  // ---- Mac extras: offer sheet, receive view, notification, cursor
  buildDesktopOffer(M, { from: 'Pixel 9', files: [0, 1, 2, 3].map(i => ({ name: pxl(i), size: (19.2 + i * 0.9).toFixed(1) + ' MB' })), total: XFER_TOTAL + ' MB' });
  const mx = buildXfer(M, { dir: 'down', peer: 'Pixel 9', sas: '482 917', file: pxl(0), total: XFER_TOTAL + ' MB', orbDp: 250 });
  css(mx.querySelector('.xfer'), { padding: '34px 24px 30px' });
  M.notif = h('div', 'notif', ACT.mac.desk, `<div class="ic">${APP_ICON_SVG}</div><div><div class="nt">CrossDrop</div><div class="nb">Pixel 9 wants to share 24 photos</div></div><div class="tm">now</div>`);
  css(M.notif, { left: '864px', top: '44px' });
  op(M.notif, 0);
  M.cursor = h('div', 'cursor', ACT.mac.desk, `<svg width="26" height="38" viewBox="0 0 26 38"><path d="M2 2v30l7.5-7 5 11 5-2.2-5-10.8H25z" fill="#fff" stroke="#000" stroke-width="2" stroke-linejoin="round"/></svg>`);
  op(M.cursor, 0);

  // ---- render functions
  ACT.android.render = T => renderAndroid(T, A);
  ACT.iphone.render = T => { renderRadar(I, T, T < 40 ? PEERS.iphone2 : PEERS.iphone10, { self: "Maya's iPhone", sweepOff: 2 }); };
  ACT.win.render = T => { renderRadar(Wn, T, T < 40 ? PEERS.win2 : PEERS.win10, { self: 'Studio PC', sweepOff: 4, dpMul: 1.1 }); };
  ACT.mac.render = T => renderMac(T, M);
  ACT.friend.render = T => renderFriend(T, F);
}

// --------------------------------------------------------------- Android
function renderAndroid(T, A) {
  const L = A.layers;
  const inSend = T >= 25 && T < 37;
  const showQr = T >= 40 && T < 56.2, showDrv = T >= 55.9 && T < 66;
  // overlays slide like AnimatedVisibility(slideInVertically)
  const xIn = inSend ? E.outCubic(ramp(T, EV.offer, EV.offer + 0.45)) : 0;
  tf(L.xfer, { y: (1 - xIn) * A.H, o: xIn > 0 ? 1 : 0 });
  op(L.qrs, showQr ? 1 : 0);
  op(L.drv, showDrv ? E.inOutSine(ramp(T, 55.9, 56.3)) : 0);
  const covered = xIn >= 1 || showQr || showDrv;
  // radar tab
  if (!covered) {
    let peers;
    if (T < 17.6) peers = PEERS.android2;
    else if (T < 25) peers = RADAR_PEERS;
    else if (T < 40) peers = RADAR_PEERS_S4;
    else peers = PEERS.android10;
    renderRadar(A, T, peers, { self: 'Pixel 9' });
  }
  // S4: tap MacBook Pro -> chooser -> photos
  if (inSend) {
    const mb = RADAR_PEERS_S4[1];
    if (mb._x !== undefined) touchAt(A.touch, T, EV.tapPeer, mb._x / A.cv.k, A.rTop + mb._y / A.cv.k);
    const shIn = E.outCubic(ramp(T, 27.22, 27.6)), shOut = E.inCubic(ramp(T, 27.98, 28.3));
    if (!A.sheetH) { A.sheetH = L.chooser.offsetHeight; const o = offsetIn(L.chooserOpt, L.chooser); A.optY = A.H - A.sheetH + o.y + o.h / 2; A.optX = o.x + o.w / 2; }
    const shown = shIn * (1 - shOut);
    tf(L.chooser, { y: (1 - shIn + shOut) * (A.sheetH + 10), o: shIn > 0 && shOut < 1 ? 1 : 0 });
    op(A.scrim, 0.5 * shown);
    L.chooserOpt.style.background = T > EV.tapPhotos - 0.05 && T < 28.2 ? '#22195a' : '';
    if (T > 27.5 && T < 28.2) touchAt(A.touch, T, EV.tapPhotos, A.optX, A.optY);
    else if (T >= 28.2) op(A.touch, 0);
    if (xIn > 0) {
      const lv = xferLevel(T), done = T >= EV.xferDone;
      const going = T >= EV.xferStart;
      const idx = Math.min(23, Math.floor(lv * 24));
      renderXfer(A, T, {
        level: done ? 1 : lv, turb: going && !done ? 0.82 : done ? 0.2 * (1 - ramp(T, EV.xferDone, EV.xferDone + 0.6)) : 0.05,
        flare: E.outCubic(ramp(T, EV.xferDone, EV.xferDone + 0.9)), pct: !done,
        status: done ? 'Transfer complete' : going ? Math.floor(lv * 100) + '%' : 'Waiting for MacBook Pro to accept…',
        scls: done ? 'done' : going ? '' : 'wait',
        file: going ? pxl(idx) : pxl(0),
        bytes: `${going ? (lv * XFER_TOTAL).toFixed(1) : '0.0'} MB / ${XFER_TOTAL} MB`,
        speed: going && !done ? xferSpeed(T).toFixed(1) + ' MB/s' : '', done,
      });
    }
  } else if (A.touch.style.visibility !== 'hidden') op(A.touch, 0);
  // S7: QR share status
  if (showQr) {
    const s = A.qrs;
    let txt, col;
    if (T < EV.pageLoad) { txt = 'Waiting for a scan…'; col = C.dim; }
    else if (T < 51.7) { txt = 'Receiver connected — waiting for downloads'; col = C.teal; }
    else if (T < EV.delivered) {
      const n = (T >= 52.4) + (T >= 52.6) + (T >= 53.4);
      const mb = 6.1 * ramp(T, 51.7, 52.4) + 1.4 * ramp(T, 51.7, 52.6) + 148.2 * ramp(T, 51.7, 53.4);
      txt = `${n} of 3 downloads done · ${mb.toFixed(1)} MB`; col = C.cyan;
    } else { txt = 'Delivered — all files downloaded'; col = C.phos; }
    if (s.last !== txt) { s.st.textContent = txt; s.st.style.color = col; s.last = txt; }
    const pulse = T >= EV.scanLock && T < EV.scanLock + 0.6 ? 1 - ramp(T, EV.scanLock, EV.scanLock + 0.6) : 0;
    s.card.style.boxShadow = `0 0 0 ${3 + 6 * pulse}px rgba(59,255,176,${0.5 + 0.4 * pulse}), 0 0 ${40 + 40 * pulse}px rgba(59,255,176,${0.25 + 0.3 * pulse})`;
  }
  // S8: drive status
  if (showDrv) {
    const d = A.drv;
    const pul = (T * 1.1) % 1;
    d.dot.style.boxShadow = `0 0 0 ${(4 + 10 * pul).toFixed(1)}px rgba(59,255,176,${(0.3 * (1 - pul)).toFixed(3)})`;
    const n = (T >= EV.finder) + (T >= EV.explorer);
    const txt = n ? `${n} device${n > 1 ? 's' : ''} connected` : 'no devices connected';
    if (d.cn.textContent !== txt) d.cn.textContent = txt;
    const up = Math.max(0, (T - EV.finder) * 1.2) + Math.max(0, (T - EV.player) * 14);
    const io = `↑ ${up.toFixed(1)} MB  ↓ 0 MB`;
    if (d.io.textContent !== io) d.io.textContent = io;
  }
}

// ------------------------------------------------------------------- Mac
function renderMac(T, M) {
  const L = M.layers;
  const inSend = T >= 25 && T < 37;
  const offerIn = inSend ? E.outCubic(ramp(T, EV.offer, EV.offer + 0.4)) * (1 - E.inCubic(ramp(T, 29.1, 29.38))) : 0;
  const xIn = inSend ? E.outCubic(ramp(T, 29.15, 29.6)) : 0;
  if (xIn < 1) renderRadar(M, T, inSend ? PEERS.macSend : T < 40 ? PEERS.mac2 : PEERS.mac10, { self: 'MacBook Pro', sweepOff: 1 });
  if (!inSend) { op(L.offer, 0); op(L.xfer, 0); op(M.scrim, 0); op(M.notif, 0); op(M.cursor, 0); return; }
  if (!M.sheetH) M.sheetH = L.offer.offsetHeight;
  tf(L.offer, { y: (1 - offerIn) * (M.sheetH + 20), o: offerIn > 0.001 ? 1 : 0 });
  op(M.scrim, 0.5 * offerIn);
  tf(L.xfer, { y: (1 - xIn) * M.H, o: xIn > 0 ? 1 : 0 });
  // accept button press
  const press = env(T, EV.accept - 0.06, EV.accept + 0.14, 0.06, 0.12);
  tf(L.accept, { s: 1 - 0.07 * press, b: 1 + 0.25 * press });
  // notification banner (macOS style) slides in from the right
  const nIn = E.outBack(ramp(T, EV.notify, EV.notify + 0.45)), nOut = E.inCubic(ramp(T, 30.6, 31.0));
  tf(M.notif, { x: (1 - nIn) * 440 + nOut * 440, o: clamp((T - EV.notify) / 0.15) * (1 - nOut) });
  // cursor glides to Accept
  if (!M.accXY) { const o = offsetIn(L.accept, ACT.mac.desk); M.accXY = { x: o.x + o.w * 0.5, y: o.y + o.h * 0.55 }; }
  const cIn = env(T, 28.3, 30.0, 0.2, 0.4);
  const mv = E.inOutCubic(ramp(T, 28.35, 28.92));
  const cx = lerp(1150, M.accXY.x, mv), cy = lerp(800, M.accXY.y, mv) - 6 * Math.sin(Math.PI * mv);
  css(M.cursor, { left: cx + 'px', top: cy + 'px' });
  tf(M.cursor, { s: 1 - 0.12 * press, o: cIn });
  if (xIn > 0) {
    const lv = xferLevel(T, 0.08), done = T >= EV.xferDone + 0.05;
    const idx = Math.min(23, Math.floor(lv * 24));
    renderXfer(M, T, {
      level: done ? 1 : lv, turb: done ? 0.2 * (1 - ramp(T, EV.xferDone, EV.xferDone + 0.6)) : 0.82,
      flare: E.outCubic(ramp(T, EV.xferDone + 0.05, EV.xferDone + 0.95)), pct: !done,
      status: done ? 'Transfer complete' : Math.floor(lv * 100) + '%', scls: done ? 'done' : '',
      file: pxl(idx), bytes: `${(lv * XFER_TOTAL).toFixed(1)} MB / ${XFER_TOTAL} MB`,
      speed: done ? '' : xferSpeed(T - 0.08).toFixed(1) + ' MB/s', done,
    });
  }
}

// ------------------------------------------------------- friend's iPhone
function renderFriend(T, F) {
  const cam = T < EV.pageLoad + 0.3;
  const sIn = E.outCubic(ramp(T, EV.pageLoad, EV.pageLoad + 0.35));
  op(F.cam, cam ? 1 : 0);
  tf(F.saf, { s: lerp(0.9, 1, sIn), o: sIn });
  if (cam) {
    // handheld camera feed
    const c = F.vfc, x = c.ctx, k = c.k;
    const hx = Math.sin(T * 1.7) * 5 + Math.sin(T * 3.3) * 2, hy = Math.cos(T * 1.3) * 4;
    const g = x.createRadialGradient(200 * k, 260 * k, 0, 200 * k, 260 * k, 420 * k);
    g.addColorStop(0, '#2a2140'); g.addColorStop(1, '#0a0812');
    x.fillStyle = g; x.fillRect(0, 0, c.width, c.height);
    const r = rng(5);
    for (let i = 0; i < 14; i++) {
      const bx = (r() * 393 + hx * 2) * k, by = (r() * 560 + hy * 2) * k, br = (20 + r() * 50) * k;
      const bg = x.createRadialGradient(bx, by, 0, bx, by, br);
      bg.addColorStop(0, `rgba(${180 + r() * 70},${140 + r() * 80},${200 + r() * 55},0.12)`); bg.addColorStop(1, 'rgba(0,0,0,0)');
      x.fillStyle = bg; x.fillRect(bx - br, by - br, br * 2, br * 2);
    }
    // the sender phone as seen through the lens
    x.save(); x.translate((196 + hx) * k, (276 + hy) * k); x.rotate(-0.05 + Math.sin(T * 0.8) * 0.02);
    roundRect(x, -112 * k, -230 * k, 224 * k, 460 * k, 34 * k); x.fillStyle = '#07050f'; x.fill();
    roundRect(x, -104 * k, -222 * k, 208 * k, 444 * k, 28 * k); x.fillStyle = '#0d0828'; x.fill();
    const gl = x.createRadialGradient(0, -20 * k, 0, 0, -20 * k, 200 * k);
    gl.addColorStop(0, 'rgba(59,255,176,0.18)'); gl.addColorStop(1, 'rgba(59,255,176,0)');
    x.fillStyle = gl; x.fillRect(-104 * k, -222 * k, 208 * k, 444 * k);
    x.restore();
    tf(F.qrImg, { x: hx, y: hy, r: -2.9 + Math.sin(T * 0.8) * 1.1, s: 0.98 });
    // scan brackets lock on
    const lock = E.outBack(ramp(T, EV.scanLock, EV.scanLock + 0.32));
    const la = clamp((T - EV.scanLock) / 0.12);
    const cxq = 196 + hx, cyq = 276 + hy, half = lerp(150, 92, lock), L = 30;
    F.bk.forEach((b, i) => {
      const sx = i % 2 ? 1 : -1, sy = i > 1 ? 1 : -1;
      css(b, { left: (cxq + sx * half - (sx > 0 ? L : 0)) + 'px', top: (cyq + sy * half - (sy > 0 ? L : 0)) + 'px', width: L + 'px', height: L + 'px', borderWidth: `${sy < 0 ? 4 : 0}px ${sx > 0 ? 4 : 0}px ${sy > 0 ? 4 : 0}px ${sx < 0 ? 4 : 0}px`, borderRadius: `${sy < 0 && sx < 0 ? 10 : 0}px ${sy < 0 && sx > 0 ? 10 : 0}px ${sy > 0 && sx > 0 ? 10 : 0}px ${sy > 0 && sx < 0 ? 10 : 0}px` });
      op(b, la);
    });
    const ch = E.outBack(ramp(T, 49.82, 50.12));
    const chPress = env(T, EV.tapOpen - 0.06, EV.tapOpen + 0.14, 0.06, 0.12);
    tf(F.chip, { x: -F.chip.offsetWidth / 2, s: ch * (1 - 0.06 * chPress), o: clamp(ch) });
    touchAt(F.touch, T, EV.tapOpen, 196, 96 + 470 + 18);
  } else {
    // browser: load bar, then downloads
    css(F.loadbar, { width: (365 * E.outCubic(ramp(T, EV.pageLoad, EV.pageLoad + 0.45))) + 'px' });
    op(F.loadbar, 1 - ramp(T, 51.05, 51.25));
    op(F.page, E.outCubic(ramp(T, 50.82, 51.12)));
    if (!F.allXY) { const o = offsetIn(F.allBtn, F.root); F.allXY = { x: o.x + o.w / 2, y: o.y + o.h / 2 }; }
    const press = env(T, EV.tapAll - 0.06, EV.tapAll + 0.14, 0.06, 0.12);
    tf(F.allBtn, { s: 1 - 0.04 * press });
    touchAt(F.touch, T, EV.tapAll, F.allXY.x, F.allXY.y);
    const ends = [53.4, 52.4, 52.6];
    F.progs.forEach((p, i) => {
      const q = ramp(T, 51.7, ends[i]);
      p.style.width = (q * 100).toFixed(2) + '%';
      const dl = p.parentElement.querySelector('.dl');
      const txt = q >= 1 ? 'Saved ✓' : q > 0 ? Math.floor(q * 100) + '%' : 'Save';
      if (dl.textContent !== txt) dl.textContent = txt;
    });
    const tIn = E.outBack(ramp(T, EV.delivered, EV.delivered + 0.45));
    tf(F.toast, { y: (1 - tIn) * -110, o: clamp((T - EV.delivered) / 0.2) });
  }
}
