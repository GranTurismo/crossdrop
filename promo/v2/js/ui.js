// DOM builders: devices, desktops and CrossDrop screens recreated from the
// Compose/SwiftUI sources (Root.kt, RadarScreen.kt, TransferScreen.kt,
// IncomingOfferSheet.kt, QrShareScreen.kt, DriveHostScreen.kt, share.html).
'use strict';

// --------------------------------------------------------------- icons
const svg = (s, body, extra = '') => `<svg width="${s}" height="${s}" viewBox="0 0 24 24" fill="none" ${extra}>${body}</svg>`;
const ICON = {
  radar: (c, s = 22) => svg(s, `<g stroke="${c}" stroke-width="1.7" stroke-linecap="round"><circle cx="12" cy="12" r="3.75"/><circle cx="12" cy="12" r="7.4"/><circle cx="12" cy="12" r="11"/><path d="M12 12L19.8 4.2"/></g><circle cx="12" cy="12" r="1.2" fill="${c}"/>`),
  transfers: (c, s = 22) => svg(s, `<g stroke="${c}" stroke-width="1.8" stroke-linecap="round"><path d="M7.7 18.7V5.3M4.8 8.2l2.9-2.9 2.9 2.9M16.3 5.3v13.4M13.4 15.8l2.9 2.9 2.9-2.9"/></g>`),
  inbox: (c, s = 22) => svg(s, `<g stroke="${c}" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M3.4 7.2v12h17.2v-12M3.4 7.2L8.6 12h6.8l5.2-4.8M12 3.4v6.7M9.6 7.7l2.4 2.4 2.4-2.4"/></g>`),
  gear: (c, s = 22) => {
    let p = '';
    for (let i = 0; i < 8; i++) { const a = i * Math.PI / 4 + Math.PI / 8; p += `M${(12 + Math.cos(a) * 7.44).toFixed(2)} ${(12 + Math.sin(a) * 7.44).toFixed(2)}L${(12 + Math.cos(a) * 10.56).toFixed(2)} ${(12 + Math.sin(a) * 10.56).toFixed(2)}`; }
    return svg(s, `<g stroke="${c}" stroke-width="1.7" stroke-linecap="round"><path d="${p}"/><circle cx="12" cy="12" r="6.24"/><circle cx="12" cy="12" r="2.16"/></g>`);
  },
  arrowUp: (c, s = 16) => svg(s, `<path d="M12 19.2V4.8M5.8 11L12 4.8l6.2 6.2" stroke="${c}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>`),
  arrowDown: (c, s = 16) => svg(s, `<path d="M12 4.8v14.4M5.8 13L12 19.2l6.2-6.2" stroke="${c}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>`),
  photo: (c, s = 24) => svg(s, `<g stroke="${c}" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="2.9" y="3.8" width="18.2" height="16.3" rx="2.9"/><path d="M4.3 17.8l6.3-5.8 3.8 3.9 1.9-1.9 3.9 3.8"/></g><circle cx="8.6" cy="9.1" r="1.9" fill="${c}"/>`),
  doc: (c, s = 24) => svg(s, `<g stroke="${c}" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M5.8 7.2v13.9h12.9V7.2l-4.3-4.3H5.8z"/><path d="M14.4 2.9v4.3h4.3M8.6 13.2h7.2M8.6 16.3h7.2" stroke-width="1.35"/></g>`),
  qr: (c, s = 20) => svg(s, `<g stroke="${c}" stroke-width="1.6"><rect x="2.4" y="2.4" width="9.6" height="9.6" rx="1.7"/><rect x="12" y="2.4" width="9.6" height="9.6" rx="1.7"/><rect x="2.4" y="12" width="9.6" height="9.6" rx="1.7"/></g><g fill="${c}"><rect x="5.3" y="5.3" width="3.8" height="3.8"/><rect x="14.9" y="5.3" width="3.8" height="3.8"/><rect x="5.3" y="14.9" width="3.8" height="3.8"/><rect x="13.2" y="13.2" width="3.8" height="3.8"/><rect x="18.7" y="14.9" width="3.8" height="3.8"/><rect x="14.9" y="18.7" width="3.8" height="3.8"/></g>`),
  vault: (c, s = 24) => svg(s, `<g stroke="${c}" stroke-width="1.7"><ellipse cx="12" cy="6.7" rx="8.2" ry="3.8"/><path d="M3.8 6.7v10.6c0 2.1 3.7 3.8 8.2 3.8s8.2-1.7 8.2-3.8V6.7"/></g>`),
  check: (c, s = 16) => svg(s, `<path d="M5.3 13.2l5.3 4.6 8.6-11" stroke="${c}" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>`),
  close: (c, s = 14) => svg(s, `<path d="M6 6l12 12M18 6L6 18" stroke="${c}" stroke-width="2" stroke-linecap="round"/>`),
  lock: (c, s = 24) => svg(s, `<g stroke="${c}" stroke-width="1.8" stroke-linecap="round"><rect x="4.5" y="10.5" width="15" height="10.5" rx="2.6"/><path d="M8 10.5V7.6a4 4 0 0 1 8 0v2.9"/></g><circle cx="12" cy="15.6" r="1.5" fill="${c}"/>`),
  wifi: (c, s = 16) => svg(s, `<g fill="${c}"><path d="M12 19.5l3.2-3.6a4.6 4.6 0 0 0-6.4 0z"/><path d="M5.4 12.6l1.8 2a6.9 6.9 0 0 1 9.6 0l1.8-2a9.6 9.6 0 0 0-13.2 0z" opacity=".95"/><path d="M2 9l1.8 2a11.9 11.9 0 0 1 16.4 0L22 9A14.6 14.6 0 0 0 2 9z" opacity=".95"/></g>`),
  signal: (c, s = 16) => svg(s, `<g fill="${c}"><rect x="2" y="15" width="3.6" height="5" rx="1"/><rect x="7.4" y="11.5" width="3.6" height="8.5" rx="1"/><rect x="12.8" y="8" width="3.6" height="12" rx="1"/><rect x="18.2" y="4.5" width="3.6" height="15.5" rx="1"/></g>`),
  battery: (c, w = 26) => `<svg width="${w}" height="${w * 0.5}" viewBox="0 0 26 13" fill="none"><rect x=".7" y=".7" width="21.6" height="11.6" rx="3.2" stroke="${c}" stroke-opacity=".55" stroke-width="1.2"/><rect x="2.4" y="2.4" width="16.4" height="8.2" rx="1.8" fill="${c}"/><path d="M23.8 4.4v4.2c.9-.3 1.5-1.1 1.5-2.1s-.6-1.8-1.5-2.1z" fill="${c}" fill-opacity=".55"/></svg>`,
  search: (c, s = 16) => svg(s, `<g stroke="${c}" stroke-width="2" stroke-linecap="round"><circle cx="10.5" cy="10.5" r="6.5"/><path d="M15.5 15.5L20 20"/></g>`),
  play: (c, s = 16) => svg(s, `<path d="M7 4.5v15l12.5-7.5z" fill="${c}"/>`),
  pause: (c, s = 16) => svg(s, `<g fill="${c}"><rect x="6" y="4.5" width="4" height="15" rx="1.2"/><rect x="14" y="4.5" width="4" height="15" rx="1.2"/></g>`),
  monitor: (c, s = 18) => svg(s, `<g stroke="${c}" stroke-width="1.7" stroke-linecap="round"><rect x="2.5" y="4" width="19" height="12.5" rx="2"/><path d="M9 20.5h6M12 16.5v4"/></g>`),
  globe: (c, s = 16) => svg(s, `<g stroke="${c}" stroke-width="1.6"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c2.6 2.6 3.8 5.6 3.8 9s-1.2 6.4-3.8 9c-2.6-2.6-3.8-5.6-3.8-9S9.4 5.6 12 3z"/></g>`),
  clock: (c, s = 16) => svg(s, `<g stroke="${c}" stroke-width="1.7" stroke-linecap="round"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.5 2"/></g>`),
  home: (c, s = 16) => svg(s, `<path d="M4 11l8-6.5 8 6.5V20h-5.5v-5.5h-5V20H4z" stroke="${c}" stroke-width="1.7" stroke-linejoin="round"/>`),
  film: (c, s = 16) => svg(s, `<g stroke="${c}" stroke-width="1.6"><rect x="3" y="4.5" width="18" height="15" rx="2.5"/><path d="M7.5 4.5v15M16.5 4.5v15M3 9.5h4.5M3 14.5h4.5M16.5 9.5H21M16.5 14.5H21"/></g>`),
};

// macOS-style blue folder and Windows-11-style yellow folder (original vector art).
function folderMac(s = 84) {
  return `<svg width="${s}" height="${s * 0.8}" viewBox="0 0 100 80"><defs><linearGradient id="fmb" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#8dd0fa"/><stop offset="1" stop-color="#4aa6ec"/></linearGradient></defs><path d="M6 10a6 6 0 0 1 6-6h22l7 7h47a6 6 0 0 1 6 6v53a6 6 0 0 1-6 6H12a6 6 0 0 1-6-6z" fill="#3c8fd8"/><rect x="4" y="18" width="92" height="60" rx="6" fill="url(#fmb)"/><rect x="4" y="18" width="92" height="2" rx="1" fill="#c4e8ff" opacity=".7"/></svg>`;
}
function folderWin(s = 84) {
  return `<svg width="${s}" height="${s * 0.8}" viewBox="0 0 100 80"><defs><linearGradient id="fwy" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ffd86b"/><stop offset="1" stop-color="#f4b400"/></linearGradient></defs><path d="M4 12a5 5 0 0 1 5-5h24l8 8h50a5 5 0 0 1 5 5v50a5 5 0 0 1-5 5H9a5 5 0 0 1-5-5z" fill="#d99a00"/><path d="M4 26a5 5 0 0 1 5-5h82a5 5 0 0 1 5 5v44a5 5 0 0 1-5 5H9a5 5 0 0 1-5-5z" fill="url(#fwy)"/></svg>`;
}
/** Generated "photo" thumbnail: a little aurora landscape, seeded. */
function photoThumb(w, hh, seed, radius = 10) {
  const r = rng(seed * 97 + 3);
  const hues = [[C.teal, C.violet], [C.cyan, C.pink], [C.violet, C.cyan], ['#ffb86b', C.pink], [C.teal, C.cyan], ['#6c8dff', C.pink]];
  const [a, b] = hues[Math.floor(r() * hues.length)];
  const sun = 0.2 + r() * 0.6, hill = 0.55 + r() * 0.2;
  return `<svg width="${w}" height="${hh}" viewBox="0 0 100 100" preserveAspectRatio="none" style="display:block;border-radius:${radius}px"><defs><linearGradient id="pt${seed}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${b}"/><stop offset="1" stop-color="${a}"/></linearGradient></defs><rect width="100" height="100" fill="url(#pt${seed})"/><circle cx="${sun * 100}" cy="34" r="${9 + r() * 6}" fill="#fff" opacity=".85"/><path d="M0 ${hill * 100} Q 30 ${hill * 100 - 18} 55 ${hill * 100 - 4} T 100 ${hill * 100 - 10} V100 H0z" fill="#0b0730" opacity=".55"/><path d="M0 ${hill * 100 + 14} Q 40 ${hill * 100} 70 ${hill * 100 + 10} T 100 ${hill * 100 + 6} V100 H0z" fill="#030014" opacity=".75"/></svg>`;
}

const STATUS_ICONS = (os) => os === 'ios'
  ? `${ICON.signal('#EEF2FF', 17)}${ICON.wifi('#EEF2FF', 17)}${ICON.battery('#EEF2FF', 27)}`
  : `${ICON.wifi('#EEF2FF', 15)}${ICON.signal('#EEF2FF', 15)}${ICON.battery('#EEF2FF', 24)}`;

// ------------------------------------------------------------ app icon
const APP_ICON_SVG = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><defs><radialGradient id="ibg" cx="50%" cy="38%" r="78%"><stop offset="0" stop-color="#17113D"/><stop offset=".55" stop-color="#0B0724"/><stop offset="1" stop-color="#05070F"/></radialGradient><radialGradient id="igm" cx="50%" cy="50%" r="50%"><stop offset="0" stop-color="#2EE6C8" stop-opacity=".16"/><stop offset="1" stop-color="#2EE6C8" stop-opacity="0"/></radialGradient><radialGradient id="igv" cx="50%" cy="50%" r="50%"><stop offset="0" stop-color="#9D6BFF" stop-opacity=".15"/><stop offset="1" stop-color="#9D6BFF" stop-opacity="0"/></radialGradient><linearGradient id="iorb" x1="30%" y1="12%" x2="78%" y2="96%"><stop offset="0" stop-color="#3BFFB0"/><stop offset=".48" stop-color="#45E5FF"/><stop offset="1" stop-color="#9D6BFF"/></linearGradient><radialGradient id="icore" cx="50%" cy="50%" r="50%"><stop offset="0" stop-color="#FFFFFF" stop-opacity=".55"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></radialGradient><linearGradient id="ibeam" x1="0%" y1="100%" x2="70%" y2="0%"><stop offset="0" stop-color="#2EE6C8" stop-opacity=".14"/><stop offset="1" stop-color="#2EE6C8" stop-opacity="0"/></linearGradient></defs><rect width="108" height="108" fill="url(#ibg)"/><ellipse cx="30" cy="26" rx="34" ry="26" fill="url(#igm)"/><ellipse cx="82" cy="88" rx="36" ry="28" fill="url(#igv)"/><path d="M54,54 L58.9,26.4 A28,28 0 0 1 80.9,46.3 Z" fill="url(#ibeam)"/><path d="M79.2,41.7 A28,28 0 1 1 44.4,80.3" fill="none" stroke="#2EE6C8" stroke-width="1.6" stroke-opacity=".42" stroke-linecap="round"/><path d="M28.8,66.3 A28,28 0 0 1 63.6,27.7" fill="none" stroke="#2EE6C8" stroke-width="1.6" stroke-opacity=".42" stroke-linecap="round"/><circle cx="54" cy="54" r="21" fill="none" stroke="#2EE6C8" stroke-width="2" stroke-opacity=".8"/><circle cx="54" cy="54" r="14" fill="url(#iorb)"/><path d="M40,54 a14,14 0 0 0 28,0 Z" fill="#241052" fill-opacity=".38"/><circle cx="50.5" cy="49.5" r="5.5" fill="url(#icore)"/><path d="M42.2,50.8 A12.2,12.2 0 0 1 51.9,42.0" fill="none" stroke="#DFFCF3" stroke-width="1.6" stroke-opacity=".85" stroke-linecap="round"/><circle cx="71.7" cy="34.3" r="4.6" fill="#FF4FD8" fill-opacity=".22"/><circle cx="71.7" cy="34.3" r="2.6" fill="#FF4FD8"/></svg>`;

// ---------------------------------------------------------- mobile app
/**
 * CrossDrop mobile UI (Android Root/RadarScreen, or the iOS port).
 * Returns handles that scenes poke each frame.
 */
function buildMobileApp(ui, os, self) {
  const W = os === 'ios' ? 393 : 412, H = os === 'ios' ? 846 : 903;
  const top = os === 'ios' ? 50 : 34;
  const app = h('div', 'app ' + os, ui);
  const sb = h('div', 'sb', app, `<span>9:41</span><span class="r">${STATUS_ICONS(os)}</span>`);
  if (os === 'ios') h('div', 'island', app); else h('div', 'punch', app);
  const main = h('div', 'main', app);
  css(main, { position: 'absolute', inset: '0' });
  const tb = h('div', 'topbar', main, `<div><div class="brand">CrossDrop</div><div><span class="namepill"><i></i>${self}</span></div></div><div class="anyone">${ICON.qr('#3BFFB0', 18)}<span>to anyone</span></div>`);
  css(tb, { top: top + 'px' });
  const navH = os === 'ios' ? 83 : 96;
  const chipsTop = H - navH - 20 - 41;
  const rTop = top + 86, rH = chipsTop - rTop - 4;
  const rbox = h('div', 'radarbox', main); css(rbox, { top: rTop + 'px', height: rH + 'px' });
  const k = 2;
  const cv = makeCanvas(rbox, W, rH, k);
  const status = h('div', 'rstatus idle', rbox, 'Listening for nearby devices…');
  const chips = h('div', 'chips', main); css(chips, { top: chipsTop + 'px' });
  const hint = h('div', 'hint', main, 'Tap a device to send files'); css(hint, { top: (chipsTop + 12) + 'px' });
  const tabs = [['Radar', ICON.radar], ['Transfers', ICON.transfers], ['Inbox', ICON.inbox], ['Settings', ICON.gear]];
  if (os === 'ios') {
    h('div', 'tabbar', main, tabs.map(([l, ic], i) => `<div class="it${i === 0 ? ' on' : ''}">${ic(i === 0 ? '#3BFFB0' : '#7E86AE', 25)}<span>${l}</span></div>`).join(''));
    h('div', 'homebar', app);
  } else {
    h('div', 'navbar', main, tabs.map(([l, ic], i) => `<div class="it${i === 0 ? ' on' : ''}"><div class="ind">${ic(i === 0 ? '#3BFFB0' : '#8B93BB', 22)}</div><span>${l}</span></div>`).join('') + '<i class="handle"></i>');
  }
  const scrim = h('div', 'scrim', app); op(scrim, 0);
  const touch = h('div', 'touch', app); op(touch, 0);
  const st = { os, W, H, app, main, cv, status, chips, hint, scrim, touch, rTop, rH, peerKey: '', layers: {} };
  return st;
}

function setChips(st, peers) {
  const key = peers.map(p => p.name).join('|');
  if (key === st.peerKey) return;
  st.peerKey = key;
  st.chips.innerHTML = peers.slice(0, st.os ? 2 : 3).map(p => `<div class="chip"><span class="d">●</span><span>${p.name}</span><span class="s">send</span></div>`).join('');
  show(st.hint, peers.length === 0);
  st.status.className = 'rstatus' + (peers.length ? '' : ' idle');
  st.status.textContent = peers.length ? `${peers.length} device${peers.length === 1 ? '' : 's'} nearby — tap to send` : 'Listening for nearby devices…';
}

/** Draw the radar into a mobile/desktop app handle. */
function renderRadar(st, T, peers, opts = {}) {
  const c = st.cv, x = c.ctx;
  drawRadar(x, c.width, c.height, T, { dp: c.k * (opts.dpMul || 1), peers, self: opts.self || 'Pixel 9', sweepOff: opts.sweepOff || 0 });
  const vis = peers.filter(p => T >= p.at);
  setChips(st, vis);
}

function buildChooser(st, peerName) {
  const sh = h('div', 'sheet', st.app, `<div class="grab"></div><div class="t">Send to ${peerName}</div><div class="st">pick what to share</div>
    <div class="opt" data-k="media">${ICON.photo('#3BFFB0', 26)}<div><div class="ot">Photos &amp; videos</div><div class="os">gallery, camera roll, Google Photos</div></div><div class="chev">›</div></div>
    <div class="opt">${ICON.doc('#45E5FF', 24)}<div><div class="ot">Files</div><div class="os">documents, archives, everything else</div></div><div class="chev">›</div></div>`);
  st.layers.chooser = sh;
  st.layers.chooserOpt = sh.querySelector('[data-k=media]');
  tf(sh, { y: 400, o: 0 });
  return sh;
}

/** Transfer overlay (TransferScreen.kt). */
function buildXfer(st, { dir = 'up', peer = 'MacBook Pro', sas = '482917', orbDp = 260, file = 'PXL_20261101_184512.jpg', total = '412 MB' } = {}) {
  const ov = h('div', 'ovl', st.app);
  h('div', 'backdrop', ov);
  const col = h('div', 'xfer', ov); css(col, { position: 'absolute', inset: '0' });
  col.innerHTML = `<div style="display:flex;flex-direction:column;align-items:center">
      <div class="dir">${dir === 'up' ? ICON.arrowUp('#8B93BB', 13) : ICON.arrowDown('#8B93BB', 13)}<span>${dir === 'up' ? 'S E N D I N G' : 'R E C E I V I N G'}</span></div>
      <div class="peer">${peer}</div>
      <div class="sas"><span>◈</span><span>verify  ${sas}</span></div></div>
    <div class="orbc"></div>
    <div style="display:flex;flex-direction:column;align-items:center">
      <div class="status wait">Waiting for ${peer} to accept…</div>
      <div class="statcard"><div><div class="fn">${file}</div><div class="fb">0 MB / ${total}</div></div><div class="sp"></div></div>
      <div class="btnrow"><span class="btn-text">Cancel transfer</span></div></div>`;
  const orbHost = col.querySelector('.orbc');
  css(orbHost, { width: orbDp + 'px', height: orbDp + 'px' });
  const cv = makeCanvas(orbHost, orbDp, orbDp, 2.2);
  st.layers.xfer = ov;
  st.xfer = { ov, cv, status: col.querySelector('.status'), fn: col.querySelector('.fn'), fb: col.querySelector('.fb'), sp: col.querySelector('.sp'), btn: col.querySelector('.btnrow'), orbDp, last: {} };
  op(ov, 0);
  return ov;
}

function renderXfer(st, T, s) {
  const x = st.xfer, c = x.cv;
  c.ctx.clearRect(0, 0, c.width, c.height);
  const r = (c.width / 2) * 0.94 / 1.18;
  drawOrb(c.ctx, c.width / 2, c.height / 2, r, T, { level: s.level, turb: s.turb, flare: s.flare, pct: s.pct, dp: c.k });
  const set = (k, el, v, prop = 'textContent') => { if (x.last[k] !== v) { el[prop] = v; x.last[k] = v; } };
  set('status', x.status, s.status);
  set('scls', x.status, 'status ' + (s.scls || ''), 'className');
  set('fn', x.fn, s.file);
  set('fb', x.fb, s.bytes);
  set('sp', x.sp, s.speed || '');
  set('btn', x.btn, s.done ? '<span class="btn-fill">Done</span>' : '<span class="btn-text">Cancel transfer</span>', 'innerHTML');
}

/** QR share overlay (QrShareScreen.kt). */
function buildQrShare(st, url, files) {
  const ov = h('div', 'ovl', st.app);
  h('div', 'backdrop', ov);
  const col = h('div', 'qrs', ov); css(col, { position: 'absolute', inset: '0' });
  col.innerHTML = `<div class="cap">S H A R E   T O   A N Y O N E</div>
    <div class="big">Scan with any camera — no app needed</div>
    <div class="note">They download straight from this phone over Wi-Fi.</div>
    <div class="qrcard">${qrSvg(url, 226, { quiet: 1 })}</div>
    <div class="list">${files.map(f => `<div class="frow"><div class="ftile">${f.ext}</div><div class="nm">${f.name}</div><div class="sz">${f.size}</div></div>`).join('')}</div>
    <div class="st">Waiting for a scan…</div>
    <div class="note" style="margin-top:14px">Both devices must stay on this Wi-Fi. The link stops working when you close this screen.</div>`;
  st.layers.qrs = ov;
  st.qrs = { ov, st: col.querySelector('.st'), card: col.querySelector('.qrcard'), last: '' };
  op(ov, 0);
  return ov;
}

/** Wireless Drive host (DriveHostScreen.kt live card). */
function buildDrive(st, url, pin) {
  const ov = h('div', 'ovl', st.app);
  h('div', 'backdrop', ov);
  const col = h('div', 'drv', ov); css(col, { position: 'absolute', inset: '0' });
  col.innerHTML = `<div class="back">‹ back</div><div class="cap" style="margin-top:6px">W I R E L E S S   D R I V E</div>
    <div class="on"><i></i>Drive is on</div><div class="vis">visible to devices on your Wi-Fi</div>
    <div class="folder"><span style="font-size:24px">📁</span><div><div class="ft">DCIM</div><div class="fs">view &amp; download only · passcode on</div></div><div class="conn"><b class="cn">no devices connected</b><br><span class="io">↑ 0 MB  ↓ 0 MB</span></div></div>
    <div class="cap" style="margin-top:18px;font-size:11px">F A S T E S T</div>
    <div class="qrcard" style="margin-top:10px;padding:10px;border-radius:18px">${qrSvg(url, 150, { quiet: 1 })}</div>
    <div class="vis" style="margin-top:8px">scan with any camera — files open instantly</div>
    <div class="cap" style="margin-top:14px;font-size:11px">O R   T Y P E   T H I S   A D D R E S S</div>
    <div class="addr"><span class="u">${url.replace('http://', '').replace(/\/$/, '')}</span><span class="cp">copy</span></div>
    <div class="pin"><span>passcode</span><b>${pin}</b></div>
    <div class="stop">Stop Sharing Drive</div>`;
  st.layers.drv = ov;
  st.drv = { ov, dot: col.querySelector('.on i'), cn: col.querySelector('.cn'), io: col.querySelector('.io') };
  op(ov, 0);
  return ov;
}

// -------------------------------------------------------------- devices
function buildAndroid(parent, self = 'Pixel 9') {
  const el = h('div', 'dev phone android', parent);
  h('div', 'shadow', el);
  h('i', 'btn', el).style.cssText = 'top:138px;height:44px';
  h('i', 'btn', el).style.cssText = 'top:206px;height:86px';
  h('div', 'body', el);
  const scr = h('div', 'screen', el);
  const ui = h('div', 'ui', scr);
  const st = buildMobileApp(ui, 'android', self);
  h('div', 'glare', scr);
  const sweep = h('div', 'sweep', scr);
  return { el, w: 300, h: 636, scr, ui, app: st, sweep };
}

function buildIPhone(parent, { color = '', self = "Maya's iPhone", app = true } = {}) {
  const el = h('div', 'dev phone iphone ' + color, parent);
  h('div', 'shadow', el);
  h('i', 'btn', el).style.cssText = 'left:-3px;top:112px;height:30px';
  h('i', 'btn', el).style.cssText = 'left:-3px;top:162px;height:54px';
  h('i', 'btn', el).style.cssText = 'left:-3px;top:228px;height:54px';
  h('i', 'btn', el).style.cssText = 'right:-3px;top:180px;height:86px';
  h('div', 'body', el);
  h('div', 'bezel', el);
  const scr = h('div', 'screen', el);
  const ui = h('div', 'ui', scr);
  const st = app ? buildMobileApp(ui, 'ios', self) : null;
  h('div', 'glare', scr);
  const sweep = h('div', 'sweep', scr);
  return { el, w: 300, h: 623, scr, ui, app: st, sweep };
}

/** Desktop CrossDrop window (NavigationRail + radar), mac or w11 chrome. */
function buildDesktopApp(parent, kind, self) {
  const win = h('div', 'window cdwin' + (kind === 'win' ? ' w11' : ''), parent);
  const tbH = kind === 'win' ? 34 : 30;
  if (kind === 'win') {
    h('div', 'titlebar', win, `<span style="width:18px;height:18px;border-radius:5px;overflow:hidden;display:block">${APP_ICON_SVG}</span><span>CrossDrop</span><span class="ctl"><span>—</span><span>▢</span><span>✕</span></span>`);
  } else {
    h('div', 'titlebar', win, `<span class="lights"><i></i><i></i><i></i></span><span class="ttl">CrossDrop</span>`);
  }
  const body = h('div', 'cdbody', win); css(body, { top: tbH + 'px' });
  const tabs = [['Radar', ICON.radar], ['Transfers', ICON.transfers], ['Inbox', ICON.inbox], ['Settings', ICON.gear]];
  h('div', 'rail', body, tabs.map(([l, ic], i) => `<div class="it${i === 0 ? ' on' : ''}"><div class="ind">${ic(i === 0 ? '#3BFFB0' : '#8B93BB', 22)}</div><span>${l}</span></div>`).join(''));
  const content = h('div', 'cdcontent', body);
  const CW = 1000, CH = 760 - tbH;
  const tb = h('div', 'topbar', content, `<div><div class="brand">CrossDrop</div><div><span class="namepill"><i></i>${self}</span></div></div><div class="anyone">${ICON.qr('#3BFFB0', 18)}<span>to anyone</span></div>`);
  css(tb, { top: '0px' });
  const rTop = 86, chipsTop = CH - 20 - 41, rH = chipsTop - rTop - 4;
  const rbox = h('div', 'radarbox', content); css(rbox, { top: rTop + 'px', height: rH + 'px' });
  const cv = makeCanvas(rbox, CW, rH, 1.25);
  const status = h('div', 'rstatus idle', rbox, 'Listening for nearby devices…');
  const chips = h('div', 'chips', content); css(chips, { top: chipsTop + 'px' });
  const hint = h('div', 'hint', content, 'Click a device to send files'); css(hint, { top: (chipsTop + 12) + 'px' });
  const scrim = h('div', 'scrim', content); op(scrim, 0);
  return { win, content, app: content, cv, status, chips, hint, scrim, W: CW, H: CH, layers: {}, peerKey: '', touch: null };
}

function buildMac(parent, self = 'MacBook Pro') {
  const el = h('div', 'dev mac', parent);
  h('div', 'shadow', el);
  const lid = h('div', 'lid', el);
  const scr = h('div', 'screen', lid);
  const desk = h('div', 'desk mac-desk', scr);
  css(desk, { width: '1280px', height: '829px', transform: 'scale(0.682813)' });
  h('div', 'menubar', desk, `<b>CrossDrop</b><span>File</span><span>Edit</span><span>View</span><span>Window</span><span>Help</span><span class="r">${ICON.battery('#fff', 26)}${ICON.wifi('#fff', 17)}${ICON.search('#fff', 15)}<span>Sun Nov 1&nbsp;&nbsp;9:41 AM</span></span>`);
  const dapp = buildDesktopApp(desk, 'mac', self);
  css(dapp.win, { left: '100px', top: '52px' });
  h('div', 'notch', lid);
  h('div', 'glare', scr);
  const sweep = h('div', 'sweep', scr);
  const base = h('div', 'base', el); h('div', 'lip', base);
  return { el, w: 1010, h: 622, desk, scr, app: dapp, sweep };
}

function buildWin(parent, self = 'Studio PC') {
  const el = h('div', 'dev wlap', parent);
  h('div', 'shadow', el);
  const lid = h('div', 'lid', el);
  const scr = h('div', 'screen', lid);
  const desk = h('div', 'desk win-desk', scr);
  css(desk, { width: '1280px', height: '834px', transform: 'scale(0.66875)' });
  const dapp = buildDesktopApp(desk, 'win', self);
  css(dapp.win, { left: '100px', top: '14px' });
  h('div', 'taskbar', desk, `<div class="search">${ICON.search('rgba(255,255,255,.75)', 15)}<span>Search</span></div>
    <div class="ti">${folderWin(28)}</div><div class="ti">${ICON.globe('#7cc4ff', 24)}</div>
    <div class="ti act"><span style="width:26px;height:26px;border-radius:7px;overflow:hidden;display:block">${APP_ICON_SVG}</span></div>
    <div class="ti">${ICON.gear('#c9d1e0', 24)}</div>
    <div class="tray">${ICON.wifi('#fff', 16)}${ICON.battery('#fff', 24)}<span>9:41 AM<br>11/1/2026</span></div>`);
  h('div', 'cam', lid);
  h('div', 'glare', scr);
  const sweep = h('div', 'sweep', scr);
  const base = h('div', 'base', el); h('div', 'lip', base);
  return { el, w: 990, h: 612, desk, scr, app: dapp, sweep };
}

/** Desktop offer = the shared IncomingOfferSheet, as a bottom sheet in the window. */
function buildDesktopOffer(dapp, { from = 'Pixel 9', files, total }) {
  const sh = h('div', 'sheet offer', dapp.content);
  css(sh, { left: '170px', right: '170px', padding: '0 28px 30px' });
  sh.innerHTML = `<div class="grab"></div><div class="avatar"><i>${from[0]}</i></div>
    <div class="ttl">${from} wants to share</div><div class="sub2">encrypted channel · ${files.length + 20} files · ${total}</div>
    <div class="filecard">${files.map((f, i) => `<div class="frow"><div class="ftile" style="padding:0;border:none">${photoThumb(36, 36, 300 + i, 9)}</div><div class="nm">${f.name}</div><div class="sz">${f.size}</div></div>`).join('')}<div class="more">+ 20 more</div></div>
    <div class="actions"><div class="dec">Decline</div><div class="acc">${ICON.check('#030014', 16)}<span>Accept</span></div></div>`;
  dapp.layers.offer = sh;
  dapp.layers.accept = sh.querySelector('.acc');
  tf(sh, { y: 500, o: 0 });
  return sh;
}

// ------------------------------------------------- floating OS windows (S8)
function buildFinder(parent) {
  const w = h('div', 'window finder', parent);
  const items = [['Camera', 'f'], ['Movies', 'f'], ['Music', 'f'], ['Documents', 'f'], ['Screenshots', 'f'], ['aurora-trip.mp4', 'v'], ['beach-day.mp4', 'v2'], ['sunset.jpg', 'p'], ['itinerary.pdf', 'd'], ['notes.txt', 'd2']];
  w.innerHTML = `<div class="side"><span class="lights"><i></i><i></i><i></i></span>
      <div class="sec">Favorites</div>
      <div class="row">${ICON.clock('#4aa6ec', 17)}Recents</div><div class="row">${ICON.home('#4aa6ec', 17)}Home</div><div class="row">${ICON.monitor('#4aa6ec', 17)}Desktop</div><div class="row">${ICON.doc('#4aa6ec', 17)}Documents</div><div class="row">${ICON.arrowDown('#4aa6ec', 17)}Downloads</div>
      <div class="sec">Locations</div>
      <div class="row">${ICON.monitor('rgba(255,255,255,.7)', 17)}MacBook Pro</div>
      <div class="row sel">${ICON.vault('#3BFFB0', 17)}Pixel Drive<span class="ej">⏏</span></div>
      <div class="row">${ICON.globe('rgba(255,255,255,.7)', 17)}Network</div></div>
    <div class="main"><div class="tb"><span class="nav">‹ ›</span><span>Pixel Drive</span><span class="r"><span>▦</span><span>⋯</span>${ICON.search('rgba(255,255,255,.6)', 16)}</span></div>
      <div class="grid">${items.map(([n, k], i) => `<div class="fitem" data-n="${n}"><div class="ico">${k === 'f' ? folderMac(86) : k[0] === 'v' || k === 'p' ? `<div style="padding:4px 0">${photoThumb(84, 60, 40 + i, 6)}</div>` : `<div style="width:56px;height:70px;margin:0 14px;border-radius:6px;background:linear-gradient(#f4f6fb,#d8dde8);display:grid;place-items:center;font:700 11px Inter;color:#6a7390">${n.split('.').pop().toUpperCase()}</div>`}</div><span class="lbl">${n}</span></div>`).join('')}</div>
      <div class="path">${ICON.vault('rgba(255,255,255,.55)', 13)}<span>Pixel Drive</span><span style="margin-left:auto">10 items · read only</span></div></div>`;
  return { el: w, video: w.querySelector('[data-n="aurora-trip.mp4"]') };
}

function buildExplorer(parent) {
  const w = h('div', 'window explorer', parent);
  const items = [['Camera', 'f'], ['Movies', 'f'], ['Music', 'f'], ['Documents', 'f'], ['Screenshots', 'f'], ['aurora-trip.mp4', 'v'], ['beach-day.mp4', 'v'], ['sunset.jpg', 'p'], ['itinerary.pdf', 'd'], ['notes.txt', 'd']];
  w.innerHTML = `<div class="tabs"><div class="tab">${ICON.vault('#3BFFB0', 16)}<span>Pixel Drive</span><span class="x">✕</span></div><span style="padding:0 12px 9px;opacity:.8;align-self:flex-end">+</span><span class="ctl"><span>—</span><span>▢</span><span>✕</span></span></div>
    <div class="addr"><span style="opacity:.7">←  →  ↑  ⟳</span><div class="box">${ICON.globe('rgba(255,255,255,.7)', 15)}<span>Network</span><span style="opacity:.5">›</span><span>Pixel Drive</span><span style="opacity:.5">›</span></div><div class="srch">Search Pixel Drive</div></div>
    <div class="cmd"><span>⊕ New</span><span style="opacity:.6">✂  ⧉  ⎘</span><span>⇅ Sort</span><span>☰ View</span><span style="margin-left:auto;opacity:.8">⋯</span></div>
    <div class="bx"><div class="nav">
        <div class="row">${ICON.home('#c9d1e0', 16)}Home</div><div class="row">${ICON.photo('#c9d1e0', 16)}Gallery</div>
        <div class="row" style="margin-top:10px">${ICON.monitor('#7cc4ff', 16)}This PC</div><div class="row">${ICON.globe('#7cc4ff', 16)}Network</div>
        <div class="row sel" style="padding-left:28px">${ICON.vault('#3BFFB0', 16)}Pixel Drive</div></div>
      <div class="files">${items.map(([n, k], i) => `<div class="fitem" data-n="${n}"><div class="ico" style="padding:4px 8px">${k === 'f' ? folderWin(84) : k === 'd' ? `<div style="width:56px;height:70px;margin:0 14px;border-radius:4px;background:linear-gradient(#f4f6fb,#d8dde8);display:grid;place-items:center;font:700 11px Inter;color:#6a7390">${n.split('.').pop().toUpperCase()}</div>` : photoThumb(84, 62, 60 + i, 3)}</div><span class="lbl">${n}</span></div>`).join('')}</div></div>
    <div class="status">10 items  |  Pixel Drive (\\\\192.168.1.24)</div>`;
  return { el: w };
}

function buildPlayer(parent) {
  const w = h('div', 'window player', parent);
  const cv = makeCanvas(w, 900, 560, 1);
  w.insertAdjacentHTML('beforeend', `<div class="ptl"><span class="lights"><i></i><i></i><i></i></span><span class="ttl">aurora-trip.mp4 — Pixel Drive</span></div>
    <div class="ctrls"><span class="pp">${ICON.pause('#fff', 18)}</span><span class="tc">0:00</span><div class="bar"><s></s><i></i></div><span>3:41</span></div>`);
  return { el: w, cv, bar: w.querySelector('.bar i'), buf: w.querySelector('.bar s'), tc: w.querySelector('.tc') };
}

/** Procedural "video": aurora over mountains, slowly panning. */
function drawVideoFrame(ctx, w, hh, t) {
  const g = ctx.createLinearGradient(0, 0, 0, hh);
  g.addColorStop(0, '#050322'); g.addColorStop(0.6, '#140a40'); g.addColorStop(1, '#1d1150');
  ctx.fillStyle = g; ctx.fillRect(0, 0, w, hh);
  const r = rng(11);
  for (let i = 0; i < 120; i++) { const x = (r() * w + t * 6) % w, y = r() * hh * 0.6; ctx.fillStyle = `rgba(255,255,255,${0.2 + 0.6 * r() * (0.6 + 0.4 * Math.sin(t * 2 + i))})`; ctx.fillRect(x, y, 1.6, 1.6); }
  ctx.globalCompositeOperation = 'lighter';
  for (let b = 0; b < 3; b++) {
    const col = [C.teal, C.cyan, C.violet][b];
    for (let i = 0; i < 60; i++) {
      const u = i / 59, x = u * w;
      const y = hh * (0.18 + b * 0.06) + Math.sin(u * 6 + t * 0.7 + b * 2) * hh * 0.06;
      const hg = hh * (0.22 + 0.08 * Math.sin(u * 9 + t + b));
      const lg = ctx.createLinearGradient(0, y, 0, y + hg);
      const a = 0.16 * (0.6 + 0.4 * Math.sin(u * 19 + t * 1.3 + b));
      lg.addColorStop(0, rgba(col, 0)); lg.addColorStop(0.2, rgba(col, a)); lg.addColorStop(1, rgba(col, 0));
      ctx.fillStyle = lg; ctx.fillRect(x - w / 59, y, (w / 59) * 2.2, hg);
    }
  }
  ctx.globalCompositeOperation = 'source-over';
  const layer = (base, amp, freq, col, sp) => {
    ctx.beginPath(); ctx.moveTo(0, hh);
    for (let x = 0; x <= w; x += 8) { const u = x / w + t * sp; ctx.lineTo(x, hh * base - Math.abs(Math.sin(u * freq)) * hh * amp - Math.sin(u * freq * 2.7) * hh * amp * 0.3); }
    ctx.lineTo(w, hh); ctx.closePath(); ctx.fillStyle = col; ctx.fill();
  };
  layer(0.78, 0.16, 5, '#120a35', 0.006);
  layer(0.88, 0.12, 8, '#0a0624', 0.012);
  layer(0.97, 0.08, 11, '#030014', 0.02);
}

// ----------------------------------------------------- friend's phone (S7)
function buildFriendPhone(parent) {
  const d = buildIPhone(parent, { color: 'blue', app: false });
  const ui = d.ui;
  const root = h('div', 'app ios', ui);
  h('div', 'sb', root, `<span>9:41</span><span class="r">${STATUS_ICONS('ios')}</span>`);
  h('div', 'island', root);
  h('div', 'homebar', root);
  // camera
  const cam = h('div', 'cam', root);
  const vf = h('div', 'vf', cam);
  const vfc = makeCanvas(vf, 393, 560, 1.6);
  const qrImg = h('div', 'abs', vf, qrSvg(SHARE_URL, 160, { quiet: 1 }));
  css(qrImg, { left: '116px', top: '196px', width: '160px', height: '160px', borderRadius: '10px', overflow: 'hidden' });
  const brackets = h('div', 'abs', vf);
  css(brackets, { left: '0', top: '0', width: '393px', height: '560px' });
  const bk = [];
  for (let i = 0; i < 4; i++) { const b = h('div', 'qrbracket', brackets); bk.push(b); }
  const chip = h('div', 'urlchip', vf, `${ICON.globe('#111', 16)}<span>Open 192.168.1.24</span>`);
  css(chip, { top: '470px' });
  h('div', 'bot', cam, `<div class="modes"><span>TIME-LAPSE</span><span>VIDEO</span><span class="on">PHOTO</span><span>PORTRAIT</span></div><div class="thumb"></div><div class="shutter"><i></i></div>`);
  h('div', 'abs', cam, '').style.cssText = 'left:0;right:0;top:54px;height:42px;display:flex;justify-content:space-between;align-items:center;padding:0 22px;color:#FFD60A;font:600 15px Inter';
  // browser
  const saf = h('div', 'safari', root);
  const page = h('div', 'webpage', saf);
  page.innerHTML = `<div class="mark">CrossDrop</div>
    <div class="from"><b>Pixel 9</b> is sharing files with you — direct over local Wi-Fi. Nothing touches the cloud.</div>
    ${SHARE_FILES.map(f => `<div class="wcard"><div class="tile">${f.ext}</div><div class="nm">${f.name}<div>${f.size}</div></div><span class="dl">Save</span><i class="prog" style="width:0"></i></div>`).join('')}
    <div class="all">Save all as .zip</div>
    <div class="foot"><span>◈</span> device-to-device · no account · no app needed<br>keep both devices on this screen until downloads finish</div>`;
  const toast = h('div', 'dltoast', saf, `<span class="ck">✓</span><div><div>3 files saved</div><div style="font:400 12px Inter;color:#8B93BB">Downloads · 155.7 MB</div></div>`);
  const bar = h('div', 'sbar', saf, `<div class="url"><span class="aa">AA</span>${ICON.lock('rgba(255,255,255,.55)', 13)}<span>192.168.1.24</span><span class="rl">↻</span></div><i class="loadbar" style="width:0"></i>`);
  const touch = h('div', 'touch', root); op(touch, 0);
  return { ...d, root, cam, vfc, qrImg, bk, chip, saf, page, toast, loadbar: bar.querySelector('.loadbar'), progs: [...page.querySelectorAll('.prog')], allBtn: page.querySelector('.all'), touch };
}

const SHARE_URL = 'http://192.168.1.24:8721/9f3c1a7e5b2d4c60a8e1f4b7c3d2e9a1/';
const SHARE_FILES = [
  { name: 'beach-day.mp4', size: '148.2 MB', ext: 'MP4' },
  { name: 'sunset.jpg', size: '6.1 MB', ext: 'JPG' },
  { name: 'itinerary.pdf', size: '1.4 MB', ext: 'PDF' },
];
const DRIVE_URL = 'http://192.168.1.24:8080/';
