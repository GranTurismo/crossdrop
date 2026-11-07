// Shared clock for the CrossDrop promo. Both the visuals (index.html) and the
// soundtrack (audio.html) read this file, so every cut, pop and ping lands on
// the same beat. 120 BPM -> 1 beat = 0.5 s, 1 bar = 2 s.
'use strict';

const BPM = 120;
const BEAT = 60 / BPM;
const BAR = BEAT * 4;
const FPS = 60;
const DURATION = 76;
const W = 1920, H = 1080;

/** Time of bar n (1-based) plus a beat offset. */
const bar = (n, beat = 0) => (n - 1) * BAR + beat * BEAT;

// Scene windows [start, end) in seconds.
const SCN = {
  hook:      [0, 8],
  logo:      [8, 12],
  platforms: [12, 18],
  radar:     [18, 26],
  send:      [26, 36],
  crypto:    [36, 40],
  delta:     [40, 48],
  anyone:    [48, 56],
  drive:     [56, 64],
  pillars:   [64, 68],
  outro:     [68, 76],
};

/**
 * Global time of a spoken word in the voice-over (js/vo.js), so visuals can pop
 * exactly when a word is said. Falls back to `fb` when there is no VO.
 */
const HAS_VO = typeof VO !== 'undefined' && VO.clips.length > 0;

function voAt(id, word, nth = 0, fb = null, lead = 0.04) {
  if (typeof VO === 'undefined') return fb;
  const c = VO.clips.find(k => k.id === id);
  if (!c) return fb;
  const key = s => s.toLowerCase().replace(/[^a-z0-9]/g, '');
  const hits = c.words.filter(w => key(w.w) === key(word));
  return hits[nth] ? +(hits[nth].s - lead).toFixed(3) : fb;
}

// Named moments. Scenes animate to these; the SFX track fires on them.
const EV = {
  // hook
  bubbles: [0.5, 1.0, 1.5, 2.0],
  bounce: [[0, 1, 2.55, 3.0], [2, 3, 3.55, 4.0], [1, 2, 4.55, 5.0]], // [from, to, launch, hit]
  hook2: 4.0,
  implode: 7.45,
  // logo
  impact: 8.0,
  wordmark: 8.9,
  tagline: 9.8,
  // platforms
  rise: [12.0, 12.25, 12.5, 12.75],
  labels: [voAt('plat', 'Mac', 0, 13.5), voAt('plat', 'Android', 0, 14.0), voAt('plat', 'iPhone', 0, 14.5), voAt('plat', 'Windows', 0, 15.0)],
  beams: 14.0,
  // radar
  peers: [19.0, 20.0, 21.0, 22.0],
  chips: [voAt('radar2', 'No', 0, 22.5), voAt('radar2', 'No', 1, 23.0), voAt('radar2', 'No', 2, 23.5)],
  // send
  tapPeer: 27.0,
  tapPhotos: 27.75,
  offer: 28.0,
  notify: 28.25,
  accept: 29.0,
  xferStart: 29.5,
  xferDone: 34.0,
  // crypto
  lock: 36.5,
  sas: 38.5,
  // delta
  edited: 40.8,
  chunkFly: [41.5, 41.75, 42.0],
  deltaDone: 43.3,
  stream: 44.0,
  drop: 44.5,
  reconnect: 45.5,
  resumeDone: 47.3,
  // anyone
  scanLock: 49.6,
  tapOpen: 50.4,
  pageLoad: 50.6,
  tapAll: 51.5,
  delivered: 53.5,
  // drive
  driveOn: 56.3,
  laptops: 57.6,
  finder: 58.55,
  explorer: 58.95,
  dblClick: 60.5,
  player: 60.8,
  // pillars
  words: [63.0, 63.75, 64.5, 65.25],   // dotted-quarter hits, landing on the 66.0 downbeat
  justDrop: 66.0,
  // outro
  finale: 68.0,
  outroNames: [voAt('outro2', 'Android', 0, 70.0), voAt('outro2', 'iOS', 0, 70.25), voAt('outro2', 'Mac', 0, 70.5), voAt('outro2', 'Windows', 0, 70.75)],
  lastPing: 72.0,
};

// Sound-effect cue sheet (rendered on its own bus, unaffected by the tape stop).
const CUES = [
  { t: 0.0, fx: 'ping', gain: 0.55, note: 83 },
  ...EV.bubbles.map((t, i) => ({ t, fx: 'bloop', note: [64, 68, 71, 76][i] })),
  ...EV.bounce.map(b => ({ t: b[2], fx: 'swish', gain: 0.35, dur: 0.45 })),
  ...EV.bounce.map(b => ({ t: b[3], fx: 'thunk' })),
  { t: 6.0, fx: 'riser', dur: 2.0, gain: 0.9 },
  { t: EV.implode - 0.05, fx: 'suck', dur: 0.6 },
  { t: EV.impact, fx: 'impact', gain: 1.0 },
  { t: EV.wordmark, fx: 'shimmer', dur: 1.4, gain: 0.5 },
  { t: 11.55, fx: 'whoosh', dur: 0.7, gain: 0.55 },
  ...EV.rise.map(t => ({ t: t - 0.05, fx: 'swish', gain: 0.28, dur: 0.5 })),
  ...EV.labels.map((t, i) => ({ t, fx: 'blip', note: [76, 80, 83, 88][i] })),
  { t: EV.beams, fx: 'shimmer', dur: 2.0, gain: 0.35 },
  { t: 17.25, fx: 'whoosh', dur: 0.8, gain: 0.7 },
  ...EV.peers.map((t, i) => ({ t, fx: 'ping', gain: 0.5, note: [83, 88, 85, 90][i], short: true })),
  ...EV.chips.map(t => ({ t, fx: 'tick' })),
  { t: 25.55, fx: 'whoosh', dur: 0.8, gain: 0.6 },
  { t: EV.tapPeer, fx: 'tap' },
  { t: EV.tapPhotos, fx: 'tap' },
  { t: EV.offer, fx: 'swish', gain: 0.3, dur: 0.4 },
  { t: EV.notify, fx: 'pop', note: 81 },
  { t: EV.accept, fx: 'click' },
  { t: EV.xferStart, fx: 'whoosh', dur: 1.0, gain: 0.45 },
  { t: EV.xferStart + 0.1, fx: 'data', dur: EV.xferDone - EV.xferStart - 0.1, gain: 0.22 },
  { t: EV.xferDone, fx: 'chime', gain: 0.6 },
  { t: 35.6, fx: 'whoosh', dur: 0.8, gain: 0.6 },
  { t: EV.lock, fx: 'lock' },
  { t: EV.sas, fx: 'pop', note: 85 },
  { t: 39.6, fx: 'whoosh', dur: 0.7, gain: 0.55 },
  { t: 40.0, fx: 'tick' }, { t: 40.25, fx: 'tick' },
  { t: EV.edited, fx: 'blip', note: 88 },
  ...EV.chunkFly.map((t, i) => ({ t, fx: 'zip', note: [76, 80, 83][i] })),
  { t: EV.deltaDone, fx: 'chime', gain: 0.45, small: true },
  { t: EV.drop, fx: 'glitch', dur: 0.9 },
  { t: EV.reconnect, fx: 'ping', gain: 0.6, note: 83 },
  { t: EV.resumeDone, fx: 'chime', gain: 0.45, small: true },
  { t: 47.55, fx: 'whoosh', dur: 0.7, gain: 0.5 },
  { t: 48.0, fx: 'swish', gain: 0.3, dur: 0.6 },
  { t: EV.scanLock, fx: 'beep' },
  { t: EV.tapOpen, fx: 'tap' },
  { t: EV.pageLoad, fx: 'swish', gain: 0.25, dur: 0.4 },
  { t: EV.tapAll, fx: 'tap' },
  { t: EV.delivered, fx: 'chime', gain: 0.6 },
  { t: 55.6, fx: 'whoosh', dur: 0.7, gain: 0.55 },
  { t: EV.driveOn, fx: 'powerup' },
  { t: EV.laptops - 0.1, fx: 'swish', gain: 0.35, dur: 0.6 },
  { t: EV.finder, fx: 'pop', note: 76 },
  { t: EV.explorer, fx: 'pop', note: 80 },
  { t: EV.dblClick, fx: 'click' }, { t: EV.dblClick + 0.12, fx: 'click' },
  { t: EV.player, fx: 'pop', note: 83 },
  { t: 62.45, fx: 'suck', dur: 0.55 },
  ...EV.words.map(t => ({ t, fx: 'tick', gain: 0.9 })),
  { t: EV.justDrop, fx: 'impact', gain: 0.5 },
  { t: 66.5, fx: 'riser', dur: 1.5, gain: 0.6 },
  { t: EV.finale, fx: 'impact', gain: 0.6 },
  { t: EV.lastPing, fx: 'ping', gain: 0.9, note: 83 },
];

if (typeof module !== 'undefined') module.exports = { BPM, BEAT, BAR, FPS, DURATION, W, H, bar, SCN, EV, CUES };
