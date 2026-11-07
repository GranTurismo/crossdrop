// Original soundtrack + sound design, synthesized with Web Audio in an
// OfflineAudioContext. Arrangement follows timeline.js bar-for-bar.
'use strict';

const SR = 48000;
const mtof = m => 440 * 2 ** ((m - 69) / 12);

// I–V–vi–IV in E major. pad = voicing without root, arp = chord tones.
const CH = [
  { root: 40, pad: [56, 59, 63, 66], arp: [64, 68, 71, 76] },   // Emaj9
  { root: 35, pad: [54, 59, 63, 66], arp: [63, 66, 71, 75] },   // B
  { root: 37, pad: [56, 59, 61, 64], arp: [64, 68, 73, 76] },   // C#m7
  { root: 33, pad: [57, 61, 64, 68], arp: [64, 69, 73, 76] },   // Amaj7
];
// Outro re-harmonized so the finale drop (bar 35) and the ending (bar 37) both resolve V -> I.
const chordIdx = b => (b >= 35 ? [0, 1, 0, 0][b - 35] : (b - 1) % 4);
const chordAt = b => CH[chordIdx(b)];
// Lead hook, one phrase per chord: [16th step, midi, length in 16ths]
const HOOK = [
  [[0, 76, 3], [3, 75, 3], [6, 71, 2], [8, 73, 4], [12, 71, 4]],
  [[0, 75, 3], [3, 73, 3], [6, 71, 2], [8, 68, 4], [12, 66, 4]],
  [[0, 73, 3], [3, 71, 3], [6, 68, 2], [8, 71, 4], [12, 73, 4]],
  [[0, 76, 6], [6, 73, 2], [8, 71, 8]],
];
const S16 = BEAT / 4;

function noiseBuffer(ac, secs = 2, seed = 1) {
  const b = ac.createBuffer(2, Math.floor(secs * SR), SR), r = rng(seed);
  for (let c = 0; c < 2; c++) { const d = b.getChannelData(c); for (let i = 0; i < d.length; i++) d[i] = r() * 2 - 1; }
  return b;
}
function reverbIR(ac, secs = 2.8, decay = 0.85, seed = 9) {
  const n = Math.floor(secs * SR), b = ac.createBuffer(2, n, SR), r = rng(seed);
  for (let c = 0; c < 2; c++) {
    const d = b.getChannelData(c);
    let lp = 0;
    for (let i = 0; i < n; i++) {
      const t = i / SR;
      const k = 0.55 - 0.45 * Math.min(1, t / secs);      // darker tail
      lp += (r() * 2 - 1 - lp) * k;
      d[i] = t < 0.018 ? 0 : lp * Math.exp(-t / decay) * 0.9;
    }
  }
  return b;
}

/** Shared FX buses for one context. */
function rig(ac, { revGain = 0.28, dlyGain = 0.22 } = {}) {
  const master = ac.createGain();
  const lo = ac.createBiquadFilter(); lo.type = 'lowshelf'; lo.frequency.value = 110; lo.gain.value = -3.5;
  const hi = ac.createBiquadFilter(); hi.type = 'highshelf'; hi.frequency.value = 3200; hi.gain.value = 3.5;
  const comp = ac.createDynamicsCompressor();
  comp.threshold.value = -16; comp.knee.value = 10; comp.ratio.value = 2.5; comp.attack.value = 0.008; comp.release.value = 0.18;
  master.connect(lo); lo.connect(hi); hi.connect(comp); comp.connect(ac.destination);
  const rev = ac.createConvolver(); rev.buffer = reverbIR(ac);
  const revOut = ac.createGain(); revOut.gain.value = revGain;
  rev.connect(revOut); revOut.connect(master);
  // ping-pong dotted-eighth delay
  const dIn = ac.createGain(), dL = ac.createDelay(2), dR = ac.createDelay(2);
  dL.delayTime.value = BEAT * 0.75; dR.delayTime.value = BEAT * 0.75;
  const fb = ac.createGain(); fb.gain.value = 0.38;
  const tone = ac.createBiquadFilter(); tone.type = 'lowpass'; tone.frequency.value = 3800;
  const pl = ac.createStereoPanner(), pr = ac.createStereoPanner(); pl.pan.value = -0.7; pr.pan.value = 0.7;
  const dOut = ac.createGain(); dOut.gain.value = dlyGain;
  dIn.connect(dL); dL.connect(pl); dL.connect(dR); dR.connect(pr); dR.connect(tone); tone.connect(fb); fb.connect(dL);
  pl.connect(dOut); pr.connect(dOut); dOut.connect(master); dOut.connect(rev);
  return { master, rev, dly: dIn, noise: noiseBuffer(ac) };
}

function send(node, dest, g) { const s = node.context.createGain(); s.gain.value = g; node.connect(s); s.connect(dest); return s; }

function noiseSrc(R, t, dur, seed = 0) {
  const s = R.ac.createBufferSource(); s.buffer = R.noise;
  s.start(t, ((seed * 0.37) % 1.4) + 0.01, dur + 0.05);
  return s;
}

// ================================================================ MUSIC
/** Speech spans [on, off] from js/vo.js (empty when there is no voice-over). */
const voSpans = () => (typeof VO === 'undefined' ? [] : VO.clips.map(c => [c.on, c.off]));
const underVO = (t0, t1, pad = 0.12) => voSpans().some(([a, b]) => t0 < b + pad && t1 > a - pad);

function renderMusic(len) {
  const ac = new OfflineAudioContext(2, len, SR);
  const R = rig(ac, { revGain: 0.30, dlyGain: 0.24 });
  R.ac = ac;
  // sidechain "pump" bus for tonal parts
  const pump = ac.createGain(); pump.connect(R.master);
  const drums = ac.createGain(); drums.gain.value = 1; drums.connect(R.master);

  const kicks = [];
  const groove = b => (b >= 7 && b <= 22) || (b >= 25 && b <= 32) || (b >= 35 && b <= 36);

  // ---------- instruments
  const padNote = (t, dur, m, { gain = 0.05, cut = 2400, cutEnd = null, atk = 0.5, rel = 1.4 } = {}) => {
    const f = ac.createBiquadFilter(); f.type = 'lowpass'; f.Q.value = 0.6;
    f.frequency.setValueAtTime(cut, t);
    if (cutEnd) f.frequency.exponentialRampToValueAtTime(cutEnd, t + dur);
    const g = ac.createGain();
    g.gain.setValueAtTime(0, t); g.gain.linearRampToValueAtTime(gain, t + atk);
    g.gain.setValueAtTime(gain, t + dur); g.gain.linearRampToValueAtTime(0, t + dur + rel);
    f.connect(g); g.connect(pump); send(g, R.rev, 0.55);
    [-13, 0, 12].forEach((dt, i) => {
      const o = ac.createOscillator(); o.type = 'sawtooth';
      o.frequency.value = mtof(m); o.detune.value = dt + (i - 1) * 0.5;
      const p = ac.createStereoPanner(); p.pan.value = [-0.55, 0, 0.55][i];
      o.connect(p); p.connect(f); o.start(t); o.stop(t + dur + rel + 0.05);
    });
  };
  const bassNote = (t, dur, m, gain = 0.22) => {
    const g = ac.createGain();
    g.gain.setValueAtTime(0, t); g.gain.linearRampToValueAtTime(gain, t + 0.006);
    g.gain.setTargetAtTime(gain * 0.7, t + 0.02, 0.08);
    g.gain.setValueAtTime(gain * 0.7, t + dur); g.gain.linearRampToValueAtTime(0, t + dur + 0.06);
    g.connect(pump);
    const sub = ac.createOscillator(); sub.type = 'sine'; sub.frequency.value = mtof(m);
    sub.connect(g); sub.start(t); sub.stop(t + dur + 0.1);
    const saw = ac.createOscillator(); saw.type = 'sawtooth'; saw.frequency.value = mtof(m + 12);
    const lp = ac.createBiquadFilter(); lp.type = 'lowpass'; lp.Q.value = 1.2;
    lp.frequency.setValueAtTime(1400, t); lp.frequency.exponentialRampToValueAtTime(380, t + 0.16);
    const sg = ac.createGain(); sg.gain.value = 0.42;
    saw.connect(lp); lp.connect(sg); sg.connect(g); saw.start(t); saw.stop(t + dur + 0.1);
  };
  const pluck = (t, m, { gain = 0.085, dec = 0.32, bright = 3400, dly = 0.55, rev = 0.3, pan = 0 } = {}) => {
    const g = ac.createGain();
    g.gain.setValueAtTime(0, t); g.gain.linearRampToValueAtTime(gain, t + 0.003);
    g.gain.exponentialRampToValueAtTime(0.0008, t + dec);
    const f = ac.createBiquadFilter(); f.type = 'lowpass'; f.Q.value = 3.5;
    f.frequency.setValueAtTime(bright, t); f.frequency.exponentialRampToValueAtTime(420, t + dec * 0.8);
    const p = ac.createStereoPanner(); p.pan.value = pan;
    f.connect(g); g.connect(p); p.connect(pump); send(p, R.dly, dly); send(p, R.rev, rev);
    const o1 = ac.createOscillator(); o1.type = 'square'; o1.frequency.value = mtof(m);
    const o2 = ac.createOscillator(); o2.type = 'sawtooth'; o2.frequency.value = mtof(m); o2.detune.value = 7;
    const mix = ac.createGain(); mix.gain.value = 0.5;
    o1.connect(mix); o2.connect(mix); mix.connect(f);
    o1.start(t); o2.start(t); o1.stop(t + dec + 0.05); o2.stop(t + dec + 0.05);
  };
  const lead = (t, dur, m, gain = 0.11) => {
    const g = ac.createGain();
    g.gain.setValueAtTime(0, t); g.gain.linearRampToValueAtTime(gain, t + 0.018);
    g.gain.setTargetAtTime(gain * 0.72, t + 0.05, 0.12);
    g.gain.setValueAtTime(gain * 0.72, t + dur); g.gain.linearRampToValueAtTime(0, t + dur + 0.22);
    const f = ac.createBiquadFilter(); f.type = 'lowpass'; f.Q.value = 1.4;
    f.frequency.setValueAtTime(5200, t); f.frequency.exponentialRampToValueAtTime(2300, t + 0.25);
    f.connect(g); g.connect(pump); send(g, R.dly, 0.42); send(g, R.rev, 0.4);
    const vib = ac.createOscillator(); vib.frequency.value = 5.4;
    const vg = ac.createGain(); vg.gain.setValueAtTime(0, t); vg.gain.linearRampToValueAtTime(9, t + 0.35);
    vib.connect(vg); vib.start(t); vib.stop(t + dur + 0.3);
    [[-6, 'sawtooth', 0.5], [6, 'sawtooth', 0.5], [0, 'triangle', 0.45]].forEach(([dt, type, lv], i) => {
      const o = ac.createOscillator(); o.type = type;
      o.frequency.value = mtof(m + (i === 2 ? 12 : 0)); o.detune.value = dt;
      vg.connect(o.detune);
      const og = ac.createGain(); og.gain.value = lv;
      o.connect(og); og.connect(f); o.start(t); o.stop(t + dur + 0.3);
    });
  };
  const kick = (t, gain = 0.8) => {
    kicks.push(t);
    const o = ac.createOscillator(); o.type = 'sine';
    o.frequency.setValueAtTime(155, t); o.frequency.exponentialRampToValueAtTime(47, t + 0.11);
    const g = ac.createGain(); g.gain.setValueAtTime(gain, t); g.gain.exponentialRampToValueAtTime(0.001, t + 0.36);
    o.connect(g); g.connect(drums); o.start(t); o.stop(t + 0.4);
    const n = noiseSrc(R, t, 0.01, t * 13); const hp = ac.createBiquadFilter(); hp.type = 'highpass'; hp.frequency.value = 2500;
    const ng = ac.createGain(); ng.gain.setValueAtTime(0.22 * gain, t); ng.gain.exponentialRampToValueAtTime(0.001, t + 0.012);
    n.connect(hp); hp.connect(ng); ng.connect(drums);
  };
  const clap = (t, gain = 0.42) => {
    const n = noiseSrc(R, t, 0.3, t * 7);
    const bp = ac.createBiquadFilter(); bp.type = 'bandpass'; bp.frequency.value = 1350; bp.Q.value = 0.9;
    const g = ac.createGain();
    g.gain.setValueAtTime(0, t);
    [0, 0.011, 0.022].forEach(d => { g.gain.setValueAtTime(gain, t + d); g.gain.exponentialRampToValueAtTime(gain * 0.25, t + d + 0.009); });
    g.gain.setValueAtTime(gain * 0.8, t + 0.031); g.gain.exponentialRampToValueAtTime(0.001, t + 0.24);
    n.connect(bp); bp.connect(g); g.connect(drums); send(g, R.rev, 0.35);
  };
  const hat = (t, open = false, gain = 0.07) => {
    const n = noiseSrc(R, t, open ? 0.25 : 0.06, t * 3 + (open ? 1 : 0));
    const hp = ac.createBiquadFilter(); hp.type = 'highpass'; hp.frequency.value = open ? 6500 : 8200;
    const g = ac.createGain(); g.gain.setValueAtTime(gain, t); g.gain.exponentialRampToValueAtTime(0.001, t + (open ? 0.2 : 0.045));
    const p = ac.createStereoPanner(); p.pan.value = open ? 0.18 : -0.15;
    n.connect(hp); hp.connect(g); g.connect(p); p.connect(drums);
  };
  const crash = (t, gain = 0.2) => {
    const n = noiseSrc(R, t, 2.4, t);
    const hp = ac.createBiquadFilter(); hp.type = 'highpass'; hp.frequency.value = 4200;
    const g = ac.createGain(); g.gain.setValueAtTime(gain, t); g.gain.exponentialRampToValueAtTime(0.001, t + 2.3);
    n.connect(hp); hp.connect(g); g.connect(drums); send(g, R.rev, 0.4);
  };
  const snare = (t, gain = 0.16) => {
    const n = noiseSrc(R, t, 0.15, t * 5);
    const bp = ac.createBiquadFilter(); bp.type = 'bandpass'; bp.frequency.value = 2200; bp.Q.value = 0.7;
    const g = ac.createGain(); g.gain.setValueAtTime(gain, t); g.gain.exponentialRampToValueAtTime(0.001, t + 0.12);
    n.connect(bp); bp.connect(g); g.connect(drums);
    const o = ac.createOscillator(); o.type = 'triangle'; o.frequency.setValueAtTime(230, t); o.frequency.exponentialRampToValueAtTime(160, t + 0.06);
    const og = ac.createGain(); og.gain.setValueAtTime(gain * 0.7, t); og.gain.exponentialRampToValueAtTime(0.001, t + 0.08);
    o.connect(og); og.connect(drums); o.start(t); o.stop(t + 0.1);
  };
  const stab = (t, ch, { gain = 0.07, oct = 0 } = {}) => {
    ch.pad.concat([ch.root + 24]).forEach(m => {
      const f = ac.createBiquadFilter(); f.type = 'lowpass'; f.Q.value = 2;
      f.frequency.setValueAtTime(6000, t); f.frequency.exponentialRampToValueAtTime(700, t + 0.35);
      const g = ac.createGain(); g.gain.setValueAtTime(gain, t); g.gain.exponentialRampToValueAtTime(0.001, t + 0.6);
      f.connect(g); g.connect(R.master); send(g, R.rev, 0.9);
      [-9, 9].forEach(dt => { const o = ac.createOscillator(); o.type = 'sawtooth'; o.frequency.value = mtof(m + oct); o.detune.value = dt; o.connect(f); o.start(t); o.stop(t + 0.65); });
    });
  };

  // ---------- arrangement
  for (let b = 1; b <= 38; b++) {
    const t = bar(b), ch = chordAt(b);
    const beats = b === 32 ? 2 : 4;   // bar 32 breaks after beat 2 for the "No cloud…" hits
    // pads
    let pad = null;
    if (b <= 4) pad = { gain: 0.030 + 0.006 * b, cut: 500 + b * 220, cutEnd: 700 + b * 260, atk: 0.9 };
    else if (b <= 6) pad = { gain: 0.05, cut: 3200, atk: 0.05 };
    else if (b === 19 || b === 20) pad = { gain: 0.042, cut: 1500 };
    else if (b === 23) pad = { gain: 0.045, cut: 2200 };
    else if (b === 24) pad = { gain: 0.045, cut: 700, cutEnd: 5200, atk: 0.3 };
    else if (b <= 32) pad = { gain: 0.045, cut: 2600 };
    else if (b === 33) pad = null;
    else if (b === 34) pad = { gain: 0.05, cut: 1200, cutEnd: 4800, atk: 0.2 };
    else if (b <= 36) pad = { gain: 0.05, cut: 3400 };
    else if (b === 37) pad = { gain: 0.055, cut: 2600, cutEnd: 900, atk: 0.05, rel: 2.5 };
    if (pad && b !== 38) ch.pad.forEach(m => padNote(t, b === 37 ? BAR * 1.6 : beats * BEAT - 0.02, m, { rel: b === 32 ? 0.35 : 1.2, ...pad }));
    // bass
    if (b >= 5 && b <= 6) bassNote(t, BAR - 0.05, ch.root, 0.3);
    if (groove(b) || b === 23 || b === 24) {
      for (let k = 0; k < beats; k++) { bassNote(t + k * BEAT + BEAT / 2, BEAT / 2 - 0.04, ch.root, 0.3); }
      if (b >= 25) for (let k = 0; k < beats; k++) bassNote(t + k * BEAT, 0.09, ch.root + 12, 0.08);
    }
    if (b === 34) bassNote(t, BAR - 0.05, ch.root, 0.26);
    if (b === 37) bassNote(t, BAR * 1.4, ch.root, 0.3);
    // arps
    const pat = [0, 1, 2, 3, 2, 1, 2, 3, 0, 1, 2, 3, 2, 1, 2, 3];
    if (b <= 4) for (let k = 0; k < 4; k++) pluck(t + k * BEAT, ch.arp[k % 4] + 12, { gain: 0.06 + 0.012 * b, dec: 0.5, bright: 2600, dly: 0.8, rev: 0.6, pan: [-0.3, 0.3, -0.15, 0.15][k] });
    else if (b <= 6) for (let k = 0; k < 8; k++) pluck(t + k * BEAT / 2, ch.arp[pat[k]] + 12, { gain: 0.1, dec: 0.35, dly: 0.6 });
    else if (groove(b) || b === 24) for (let k = 0; k < beats * 4; k++) pluck(t + k * S16, ch.arp[pat[k]] + (b >= 29 && b <= 32 ? 12 : 0), { gain: k % 4 === 0 ? 0.15 : 0.11, dec: 0.22 + (k % 2) * 0.06, bright: b === 19 || b === 20 ? 2000 : 3600, pan: k % 2 ? 0.22 : -0.22 });
    else if (b === 23) for (let k = 0; k < 2; k++) pluck(t + k * S16, ch.arp[pat[k]], { gain: 0.08 });
    // lead hook
    const leadOn = (b >= 13 && b <= 18) || (b >= 25 && b <= 32) || (b >= 35 && b <= 36);
    if (leadOn) for (const [st, m, ln] of HOOK[chordIdx(b)]) {
      if (st >= beats * 4) continue;
      const t0 = t + st * S16, d = ln * S16 - 0.03;
      lead(t0, d, m, (b >= 35 ? 0.2 : 0.17) * (underVO(t0, t0 + d) ? 0.3 : 1));   // make room for the voice
    }
    // drums
    if (groove(b)) {
      for (let k = 0; k < beats; k++) {
        kick(t + k * BEAT);
        hat(t + k * BEAT + BEAT / 2, true, 0.13);
        hat(t + k * BEAT + BEAT / 4, false, 0.075); hat(t + k * BEAT + (3 * BEAT) / 4, false, 0.065);
      }
      if (b >= 8 && b !== 19) { clap(t + BEAT); if (beats > 2) clap(t + 3 * BEAT); }
    }
    if (b <= 4 && b >= 3) for (let k = 0; k < 8; k++) hat(t + k * BEAT / 2, false, 0.025 + 0.01 * (b - 3));
    if (b === 6) { kick(t, 0.8); kick(t + 2 * BEAT, 0.8); kick(t + 3.5 * BEAT, 0.6); }
    if (b === 23) kick(t, 0.95);
    if (b === 24) { // resume build: kicks on every beat + snare roll
      for (let k = 0; k < 4; k++) kick(t + k * BEAT, 0.85);
      for (let k = 0; k < 16; k++) snare(t + k * S16, 0.05 + 0.012 * k);
      for (let k = 0; k < 8; k++) snare(t + 3 * BEAT + k * S16 / 2, 0.2);
    }
    if ([5, 7, 13, 18, 21, 25, 29, 35].includes(b)) crash(t, b === 5 ? 0.2 : 0.15);
    if (b === 33) EV.words.forEach((tw, i) => stab(tw, chordAt(33), { gain: 0.06 + 0.008 * i, oct: i === 3 ? 12 : 0 }));
    if (b === 34) stab(t, chordAt(34), { gain: 0.08, oct: 12 });
  }
  // sidechain pump on every kick
  pump.gain.setValueAtTime(1, 0);
  kicks.sort((a, b) => a - b).forEach(k => { pump.gain.setValueAtTime(0.38, k); pump.gain.setTargetAtTime(1, k + 0.015, 0.075); });
  // fade the very end
  R.master.gain.setValueAtTime(1, 0); R.master.gain.setValueAtTime(1, 73.0); R.master.gain.linearRampToValueAtTime(0, 76.0);
  return ac.startRendering();
}

// ================================================================== SFX
function renderSfx(len) {
  const ac = new OfflineAudioContext(2, len, SR);
  const R = rig(ac, { revGain: 0.42, dlyGain: 0.2 });
  R.ac = ac;
  let out = R.master;
  const env = (g, t, a, peak, d) => { g.gain.setValueAtTime(0, t); g.gain.linearRampToValueAtTime(peak, t + a); g.gain.exponentialRampToValueAtTime(0.0008, t + a + d); };
  const osc = (type, f, t, dur, dest) => { const o = ac.createOscillator(); o.type = type; o.frequency.setValueAtTime(f, t); o.connect(dest); o.start(t); o.stop(t + dur); return o; };
  const fx = {
    ping(c) {
      const t = c.t, f = mtof(c.note || 83), g = ac.createGain(), d = c.short ? 0.55 : 1.5;
      env(g, t, 0.004, 0.26 * (c.gain ?? 0.6), d);
      const o = osc('sine', f, t, d + 0.1, g); o.frequency.exponentialRampToValueAtTime(f * 0.985, t + d);
      const g2 = ac.createGain(); env(g2, t, 0.003, 0.07 * (c.gain ?? 0.6), d * 0.4); osc('sine', f * 2, t, d, g2); g2.connect(out);
      g.connect(out); send(g, R.dly, c.short ? 0.25 : 0.55); send(g, R.rev, 0.7);
    },
    bloop(c) {
      const t = c.t, f = mtof(c.note), g = ac.createGain(); env(g, t, 0.01, 0.11, 0.45);
      const o = osc('sine', f * 0.75, t, 0.6, g); o.frequency.exponentialRampToValueAtTime(f, t + 0.08);
      g.connect(out); send(g, R.rev, 0.6); send(g, R.dly, 0.3);
    },
    blip(c) {
      const t = c.t, f = mtof(c.note), g = ac.createGain(); env(g, t, 0.003, 0.09, 0.22);
      osc('triangle', f, t, 0.3, g); const g2 = ac.createGain(); env(g2, t, 0.002, 0.03, 0.08); osc('sine', f * 3, t, 0.12, g2); g2.connect(out);
      g.connect(out); send(g, R.rev, 0.45); send(g, R.dly, 0.25);
    },
    pop(c) {
      const t = c.t, f = mtof(c.note || 81), g = ac.createGain(); env(g, t, 0.004, 0.13, 0.35);
      const o = osc('sine', f * 0.8, t, 0.45, g); o.frequency.exponentialRampToValueAtTime(f, t + 0.05);
      g.connect(out); send(g, R.rev, 0.4);
    },
    tick(c) {
      const t = c.t, n = noiseSrc(R, t, 0.02, t * 11), bp = ac.createBiquadFilter(); bp.type = 'bandpass'; bp.frequency.value = 3200; bp.Q.value = 2;
      const g = ac.createGain(); g.gain.setValueAtTime(0.22 * (c.gain ?? 0.6), t); g.gain.exponentialRampToValueAtTime(0.001, t + 0.018);
      n.connect(bp); bp.connect(g); g.connect(out);
      const g2 = ac.createGain(); env(g2, t, 0.001, 0.05 * (c.gain ?? 0.6), 0.03); osc('sine', 2400, t, 0.05, g2); g2.connect(out);
    },
    tap(c) {
      const t = c.t, g = ac.createGain(); env(g, t, 0.002, 0.07, 0.05); osc('sine', 1700, t, 0.08, g); g.connect(out);
      const n = noiseSrc(R, t, 0.02, t), lp = ac.createBiquadFilter(); lp.type = 'lowpass'; lp.frequency.value = 2400;
      const ng = ac.createGain(); ng.gain.setValueAtTime(0.06, t); ng.gain.exponentialRampToValueAtTime(0.001, t + 0.02);
      n.connect(lp); lp.connect(ng); ng.connect(out);
    },
    click(c) {
      [0, 0.03].forEach((d, i) => {
        const t = c.t + d, n = noiseSrc(R, t, 0.02, t * 3 + i), bp = ac.createBiquadFilter(); bp.type = 'bandpass'; bp.frequency.value = i ? 4200 : 2600; bp.Q.value = 3;
        const g = ac.createGain(); g.gain.setValueAtTime(i ? 0.10 : 0.16, t); g.gain.exponentialRampToValueAtTime(0.001, t + 0.012);
        n.connect(bp); bp.connect(g); g.connect(out);
      });
    },
    swish(c) {
      const t = c.t, dur = c.dur || 0.5, n = noiseSrc(R, t, dur, t * 17), bp = ac.createBiquadFilter(); bp.type = 'bandpass'; bp.Q.value = 1.4;
      bp.frequency.setValueAtTime(500, t); bp.frequency.exponentialRampToValueAtTime(4200, t + dur);
      const g = ac.createGain(); g.gain.setValueAtTime(0, t); g.gain.linearRampToValueAtTime(0.32 * (c.gain ?? 0.3), t + dur * 0.6); g.gain.linearRampToValueAtTime(0, t + dur);
      const p = ac.createStereoPanner(); p.pan.setValueAtTime(-0.6, t); p.pan.linearRampToValueAtTime(0.6, t + dur);
      n.connect(bp); bp.connect(g); g.connect(p); p.connect(out); send(p, R.rev, 0.3);
    },
    whoosh(c) {
      const t = c.t, dur = c.dur || 0.8, n = noiseSrc(R, t, dur, t * 23);
      const bp = ac.createBiquadFilter(); bp.type = 'bandpass'; bp.Q.value = 0.8;
      bp.frequency.setValueAtTime(300, t); bp.frequency.exponentialRampToValueAtTime(2600, t + dur * 0.7); bp.frequency.exponentialRampToValueAtTime(900, t + dur);
      const g = ac.createGain(); g.gain.setValueAtTime(0, t); g.gain.linearRampToValueAtTime(0.42 * (c.gain ?? 0.6), t + dur * 0.65); g.gain.linearRampToValueAtTime(0, t + dur);
      const p = ac.createStereoPanner(); p.pan.setValueAtTime(0.5, t); p.pan.linearRampToValueAtTime(-0.5, t + dur);
      n.connect(bp); bp.connect(g); g.connect(p); p.connect(out); send(p, R.rev, 0.35);
      const sg = ac.createGain(); sg.gain.setValueAtTime(0, t); sg.gain.linearRampToValueAtTime(0.12 * (c.gain ?? 0.6), t + dur * 0.65); sg.gain.linearRampToValueAtTime(0, t + dur);
      const o = osc('sine', 60, t, dur, sg); o.frequency.linearRampToValueAtTime(90, t + dur * 0.65); sg.connect(out);
    },
    suck(c) {
      const t = c.t, dur = c.dur || 0.6, n = noiseSrc(R, t, dur, t * 29), bp = ac.createBiquadFilter(); bp.type = 'bandpass'; bp.Q.value = 1;
      bp.frequency.setValueAtTime(5000, t); bp.frequency.exponentialRampToValueAtTime(300, t + dur);
      const g = ac.createGain(); g.gain.setValueAtTime(0.001, t); g.gain.exponentialRampToValueAtTime(0.3, t + dur * 0.95); g.gain.linearRampToValueAtTime(0, t + dur);
      n.connect(bp); bp.connect(g); g.connect(out);
    },
    thunk(c) {
      const t = c.t, g = ac.createGain(); env(g, t, 0.003, 0.34, 0.22);
      const o = osc('sine', 150, t, 0.3, g); o.frequency.exponentialRampToValueAtTime(72, t + 0.08);
      const n = noiseSrc(R, t, 0.05, t * 31), lp = ac.createBiquadFilter(); lp.type = 'lowpass'; lp.frequency.value = 1100;
      const ng = ac.createGain(); ng.gain.setValueAtTime(0.14, t); ng.gain.exponentialRampToValueAtTime(0.001, t + 0.05);
      n.connect(lp); lp.connect(ng); ng.connect(out);
      const g2 = ac.createGain(); env(g2, t, 0.002, 0.035, 0.12); osc('square', mtof(70), t, 0.15, g2); osc('square', mtof(71), t, 0.15, g2);
      const f2 = ac.createBiquadFilter(); f2.type = 'lowpass'; f2.frequency.value = 1800; g2.connect(f2); f2.connect(out);
      g.connect(out); send(g, R.rev, 0.25);
    },
    riser(c) {
      const t = c.t, dur = c.dur, k = c.gain ?? 0.8;
      const n = noiseSrc(R, t, dur, t * 41), hp = ac.createBiquadFilter(); hp.type = 'bandpass'; hp.Q.value = 0.9;
      hp.frequency.setValueAtTime(400, t); hp.frequency.exponentialRampToValueAtTime(9000, t + dur);
      const g = ac.createGain(); g.gain.setValueAtTime(0.001, t); g.gain.exponentialRampToValueAtTime(0.22 * k, t + dur); g.gain.linearRampToValueAtTime(0, t + dur + 0.02);
      n.connect(hp); hp.connect(g); g.connect(out); send(g, R.rev, 0.3);
      const sg = ac.createGain(); sg.gain.setValueAtTime(0.001, t); sg.gain.exponentialRampToValueAtTime(0.05 * k, t + dur); sg.gain.linearRampToValueAtTime(0, t + dur + 0.02);
      const lp = ac.createBiquadFilter(); lp.type = 'lowpass'; lp.frequency.setValueAtTime(600, t); lp.frequency.exponentialRampToValueAtTime(6000, t + dur);
      [0, 7].forEach(dt => { const o = osc('sawtooth', mtof(52), t, dur + 0.05, lp); o.detune.value = dt; o.frequency.exponentialRampToValueAtTime(mtof(76), t + dur); });
      lp.connect(sg); sg.connect(out);
    },
    impact(c) {
      const t = c.t, k = c.gain ?? 1;
      const g = ac.createGain(); env(g, t, 0.004, 0.5 * k, 1.7);
      const o = osc('sine', 72, t, 2.2, g); o.frequency.exponentialRampToValueAtTime(31, t + 1.1);
      g.connect(out);
      const n = noiseSrc(R, t, 0.6, t * 3), lp = ac.createBiquadFilter(); lp.type = 'lowpass'; lp.frequency.setValueAtTime(3000, t); lp.frequency.exponentialRampToValueAtTime(200, t + 0.5);
      const ng = ac.createGain(); env(ng, t, 0.002, 0.42 * k, 0.55); n.connect(lp); lp.connect(ng); ng.connect(out); send(ng, R.rev, 0.6);
      const n2 = noiseSrc(R, t, 2.4, t * 5), hp = ac.createBiquadFilter(); hp.type = 'highpass'; hp.frequency.value = 3800;
      const cg = ac.createGain(); env(cg, t, 0.003, 0.16 * k, 2.2); n2.connect(hp); hp.connect(cg); cg.connect(out); send(cg, R.rev, 0.5);
    },
    shimmer(c) {
      const t0 = c.t, dur = c.dur || 1.2, r = rng(Math.floor(t0 * 100)), notes = [76, 78, 80, 83, 85, 88, 90, 92, 95];
      const n = Math.floor(dur * 14);
      for (let i = 0; i < n; i++) {
        const t = t0 + (i / n) * dur + r() * 0.03, m = notes[Math.floor(r() * notes.length)];
        const g = ac.createGain(); env(g, t, 0.003, 0.05 * (c.gain ?? 0.5) * (1 - (i / n) * 0.6), 0.4);
        const p = ac.createStereoPanner(); p.pan.value = r() * 1.6 - 0.8;
        osc(i % 2 ? 'sine' : 'triangle', mtof(m), t, 0.5, g); g.connect(p); p.connect(out); send(p, R.rev, 0.8);
      }
    },
    data(c) {
      const t0 = c.t, dur = c.dur, r = rng(77), notes = [88, 90, 92, 95, 97, 100];
      for (let t = t0; t < t0 + dur; t += 0.06 + r() * 0.05) {
        const g = ac.createGain(); env(g, t, 0.002, 0.02 * (c.gain ?? 0.2) / 0.2, 0.06);
        const p = ac.createStereoPanner(); p.pan.value = r() * 1.4 - 0.7;
        osc('sine', mtof(notes[Math.floor(r() * notes.length)]), t, 0.1, g); g.connect(p); p.connect(out); send(p, R.dly, 0.2);
      }
      const n = noiseSrc(R, t0, dur, 3), bp = ac.createBiquadFilter(); bp.type = 'bandpass'; bp.frequency.value = 6000; bp.Q.value = 0.6;
      const g = ac.createGain(); g.gain.setValueAtTime(0, t0); g.gain.linearRampToValueAtTime(0.018, t0 + 0.4); g.gain.setValueAtTime(0.018, t0 + dur - 0.3); g.gain.linearRampToValueAtTime(0, t0 + dur);
      n.connect(bp); bp.connect(g); g.connect(out);
    },
    chime(c) {
      const t = c.t, k = c.gain ?? 0.7, notes = c.small ? [88] : [83, 88, 95];
      notes.forEach((m, i) => {
        const tt = t + i * 0.07, f = mtof(m), g = ac.createGain(); env(g, tt, 0.003, 0.12 * k, c.small ? 0.9 : 2.0);
        const car = osc('sine', f, tt, 2.2, g);
        const mod = ac.createOscillator(); mod.frequency.value = f * 3.5;
        const mg = ac.createGain(); mg.gain.setValueAtTime(f * 2.2, tt); mg.gain.exponentialRampToValueAtTime(1, tt + 1.2);
        mod.connect(mg); mg.connect(car.frequency); mod.start(tt); mod.stop(tt + 2.2);
        const p = ac.createStereoPanner(); p.pan.value = [-0.3, 0.3, 0][i] || 0;
        g.connect(p); p.connect(out); send(p, R.rev, 0.6); send(p, R.dly, 0.3);
      });
    },
    lock(c) {
      const t = c.t;
      [[0, 1900, 0.2], [0.035, 3400, 0.14]].forEach(([d, f, a], i) => {
        const n = noiseSrc(R, t + d, 0.02, t * 7 + i), bp = ac.createBiquadFilter(); bp.type = 'bandpass'; bp.frequency.value = f; bp.Q.value = 4;
        const g = ac.createGain(); g.gain.setValueAtTime(a, t + d); g.gain.exponentialRampToValueAtTime(0.001, t + d + 0.015);
        n.connect(bp); bp.connect(g); g.connect(out);
      });
      const g = ac.createGain(); env(g, t, 0.002, 0.3, 0.14); const o = osc('sine', 130, t, 0.2, g); o.frequency.exponentialRampToValueAtTime(60, t + 0.1); g.connect(out);
      const r = ac.createGain(); env(r, t + 0.035, 0.002, 0.03, 0.25); osc('sine', 2350, t + 0.035, 0.3, r); r.connect(out); send(r, R.rev, 0.6);
    },
    zip(c) {
      const t = c.t, f = mtof(c.note), g = ac.createGain(); env(g, t, 0.005, 0.09, 0.3);
      const o = osc('triangle', f / 2, t, 0.35, g); o.frequency.exponentialRampToValueAtTime(f, t + 0.12);
      g.connect(out); send(g, R.dly, 0.35); send(g, R.rev, 0.4);
      fx.swish({ t, dur: 0.3, gain: 0.25 });
    },
    glitch(c) {
      const t0 = c.t, r = rng(4);
      for (let i = 0; i < 9; i++) {
        const t = t0 + i * 0.045 + r() * 0.02, g = ac.createGain(); env(g, t, 0.001, 0.07, 0.03 + r() * 0.03);
        osc('square', 200 + r() * 1800, t, 0.08, g);
        const f = ac.createBiquadFilter(); f.type = 'bandpass'; f.frequency.value = 1500; f.Q.value = 0.7; g.connect(f); f.connect(out);
      }
      const g = ac.createGain(); env(g, t0, 0.005, 0.18, 0.5); const o = osc('sawtooth', 520, t0, 0.6, g); o.frequency.exponentialRampToValueAtTime(45, t0 + 0.5);
      const lp = ac.createBiquadFilter(); lp.type = 'lowpass'; lp.frequency.value = 1400; g.connect(lp); lp.connect(out);
      const n = noiseSrc(R, t0, 0.35, 9), ng = ac.createGain(); env(ng, t0, 0.002, 0.08, 0.3); n.connect(ng); ng.connect(out);
    },
    beep(c) {
      const t = c.t, g = ac.createGain(); g.gain.setValueAtTime(0, t); g.gain.linearRampToValueAtTime(0.11, t + 0.005); g.gain.setValueAtTime(0.11, t + 0.08); g.gain.linearRampToValueAtTime(0, t + 0.1);
      osc('sine', 1568, t, 0.12, g); g.connect(out); send(g, R.rev, 0.25);
    },
    powerup(c) {
      const t = c.t, g = ac.createGain(); env(g, t, 0.02, 0.1, 0.45);
      const o = osc('triangle', 220, t, 0.6, g); o.frequency.exponentialRampToValueAtTime(1320, t + 0.35);
      g.connect(out); send(g, R.rev, 0.5); send(g, R.dly, 0.3);
      fx.shimmer({ t: t + 0.25, dur: 0.6, gain: 0.3 });
    },
  };
  // per-type trims (dB) from the audibility report: SFX peak vs music RMS at each cue
  const TRIM = { shimmer: 6, whoosh: 5, swish: 6, blip: 5, tick: 6, tap: 7, click: 8, data: 2, zip: 5, ping: 2, powerup: 5, pop: 3, thunk: -2, beep: 4 };
  for (const c of CUES) {
    if (!fx[c.fx]) throw new Error('unknown fx ' + c.fx);
    const db = c.fx === 'chime' ? (c.small ? 7 : 3) : (TRIM[c.fx] || 0);
    out = ac.createGain(); out.gain.value = 10 ** (db / 20); out.connect(R.master);
    fx[c.fx](c);
  }
  return ac.startRendering();
}

// ============================================================ post + mix
const MIX = { music: 0.85, sfx: 0.95, vo: 1.19, targetRms: -16.5, duckMusicDb: -12, duckSfxDb: -7, voLineRms: -17 };

/** Stereo-linked look-ahead peak limiter (in place). Returns max gain reduction in dB. */
function limit(L, R, ceil, look = Math.floor(0.004 * SR), rel = 0.12) {
  const n = L.length, req = new Float32Array(n);
  for (let i = 0; i < n; i++) { const p = Math.max(Math.abs(L[i]), Math.abs(R[i])); req[i] = p > ceil ? ceil / p : 1; }
  // min over the look-ahead window (so gain is down before the peak arrives)
  const win = new Float32Array(n); const dq = []; 
  for (let i = n - 1; i >= 0; i--) {
    while (dq.length && req[dq[dq.length - 1]] >= req[i]) dq.pop();
    dq.push(i);
    while (dq[0] > i + look) dq.shift();
    win[i] = req[dq[0]];
  }
  const rc = Math.exp(-1 / (rel * SR));
  let g = 1, worst = 1;
  const sm = new Float32Array(n);
  for (let i = 0; i < n; i++) { g = win[i] < g ? win[i] : win[i] + (g - win[i]) * rc; sm[i] = g; }
  // smooth the attack edge across the look-ahead window
  let acc = 0; const q = [];
  for (let i = 0; i < n; i++) {
    q.push(sm[i]); acc += sm[i]; if (q.length > look) acc -= q.shift();
    const gg = Math.min(sm[i], acc / q.length);
    L[i] *= gg; R[i] *= gg; if (gg < worst) worst = gg;
  }
  return -20 * Math.log10(worst);
}
/** Tape stop: decelerate the music from t0 over `dur`, then silence until tResume. */
function tapeStop(buf, t0, dur, tResume) {
  const i0 = Math.floor(t0 * SR), n = Math.floor(dur * SR), i1 = Math.floor(tResume * SR);
  for (let c = 0; c < buf.numberOfChannels; c++) {
    const d = buf.getChannelData(c), src = d.slice(i0, i0 + n + 10);
    let pos = 0;
    for (let i = 0; i < n; i++) {
      const u = i / n, rate = (1 - u) ** 1.6;
      const k = Math.floor(pos), f = pos - k;
      const v = (src[k] ?? 0) * (1 - f) + (src[k + 1] ?? 0) * f;
      const fade = Math.min(1, (n - i) / (0.06 * SR));
      d[i0 + i] = v * fade;
      pos += rate;
    }
    for (let i = i0 + n; i < i1; i++) d[i] = 0;
    // de-click the restart
    for (let i = 0; i < 0.01 * SR; i++) d[i1 + i] *= i / (0.01 * SR);
  }
}

/** Voice bus: per-line level match, high-pass, de-mud, presence, gentle compression. */
async function renderVO(len) {
  if (typeof VO === 'undefined' || !VO.clips.length) return null;
  const ac = new OfflineAudioContext(2, len, SR);
  const bufs = await Promise.all(VO.clips.map(async c => ac.decodeAudioData(await (await fetch(c.file)).arrayBuffer())));
  const bq = (type, f, q, g = 0) => { const n = ac.createBiquadFilter(); n.type = type; n.frequency.value = f; n.Q.value = q; n.gain.value = g; return n; };
  // Per-voice tone: af_heart is already bright (5-10 kHz only ~6 dB under 1-4 kHz), so no air
  // boost and a soft cut where sibilance lives; am_fenrir is darker and gets a little air.
  const bright = VO.voice === 'af_heart';
  const chain = bright
    ? [bq('highpass', 90, 0.7), bq('peaking', 300, 1.0, -2.5), bq('peaking', 3300, 0.9, 1.5), bq('peaking', 7000, 1.2, -3)]
    : [bq('highpass', 80, 0.7), bq('peaking', 300, 1.0, -2), bq('peaking', 3300, 0.9, 2.5), bq('highshelf', 9500, 0.7, 1.5)];
  const comp = ac.createDynamicsCompressor();
  comp.threshold.value = -20; comp.knee.value = 6; comp.ratio.value = 3; comp.attack.value = 0.003; comp.release.value = 0.12;
  const bus = ac.createGain();
  [bus, ...chain, comp].reduce((a, b) => (a.connect(b), b)).connect(ac.destination);
  const levels = [];
  VO.clips.forEach((c, i) => {
    const d = bufs[i].getChannelData(0), fr = Math.floor(0.02 * SR), frames = [];
    for (let k = 0; k + fr <= d.length; k += fr) { let e = 0; for (let j = k; j < k + fr; j++) e += d[j] * d[j]; frames.push(e / fr); }
    const top = Math.max(...frames), act = frames.filter(e => e > top * 1e-3);           // frames within 30 dB of the loudest
    const speech = Math.sqrt(act.reduce((a, b) => a + b, 0) / Math.max(1, act.length));
    const g = 10 ** (MIX.voLineRms / 20) / Math.max(speech, 1e-6);
    levels.push(`${c.id}:${(20 * Math.log10(g)).toFixed(1)}`);
    const src = ac.createBufferSource(); src.buffer = bufs[i];
    const gn = ac.createGain(); gn.gain.value = g;
    src.connect(gn); gn.connect(bus);
    if (c.t >= 0) src.start(c.t); else src.start(0, -c.t);
  });
  const out = await ac.startRendering();
  out.levels = levels;
  return out;
}

/** Ducking envelope: dips to depthDb while the voice speaks (nearby lines merged). */
function duckEnv(len, spans, depthDb, att = 0.12, rel = 0.42, merge = 0.7) {
  const m = [];
  for (const s of spans.slice().sort((a, b) => a[0] - b[0])) {
    if (m.length && s[0] - m[m.length - 1][1] < merge) m[m.length - 1][1] = Math.max(m[m.length - 1][1], s[1]);
    else m.push([s[0], s[1]]);
  }
  const env = new Float32Array(len).fill(1), g = 10 ** (depthDb / 20), sm = x => x * x * (3 - 2 * x);
  for (const [a, b] of m) {
    const i0 = Math.max(0, Math.floor((a - att) * SR)), i1 = Math.floor(a * SR), i2 = Math.floor(b * SR), i3 = Math.min(len, Math.floor((b + rel) * SR));
    for (let i = i0; i < i3; i++) {
      const v = i < i1 ? 1 + (g - 1) * sm((i - i0) / Math.max(1, i1 - i0)) : i < i2 ? g : g + (1 - g) * sm((i - i2) / Math.max(1, i3 - i2));
      if (v < env[i]) env[i] = v;
    }
  }
  return env;
}

async function renderAudio() {
  const len = Math.ceil((DURATION + 0.25) * SR);
  const [music, sfx, vo] = await Promise.all([renderMusic(len), renderSfx(len), renderVO(len)]);
  tapeStop(music, EV.drop, 0.55, bar(24));
  const L = new Float32Array(len), Rr = new Float32Array(len);
  const mL = music.getChannelData(0), mR = music.getChannelData(1), sL = sfx.getChannelData(0), sR = sfx.getChannelData(1);
  const vL = vo ? vo.getChannelData(0) : new Float32Array(len), vR = vo ? vo.getChannelData(1) : new Float32Array(len);
  const rms = (a, b, i0 = 0, i1 = a.length) => { let s = 0; for (let i = i0; i < i1; i++) s += a[i] * a[i] + b[i] * b[i]; return Math.sqrt(s / (2 * Math.max(1, i1 - i0))); };
  const db = v => (20 * Math.log10(v)).toFixed(1);
  const spans = voSpans();
  const dm = duckEnv(len, spans, MIX.duckMusicDb), ds = duckEnv(len, spans, MIX.duckSfxDb);
  const bedL = new Float32Array(len), bedR = new Float32Array(len);
  for (let i = 0; i < len; i++) {
    bedL[i] = mL[i] * MIX.music * dm[i] + sL[i] * MIX.sfx * ds[i];
    bedR[i] = mR[i] * MIX.music * dm[i] + sR[i] * MIX.sfx * ds[i];
    L[i] = bedL[i] + vL[i] * MIX.vo; Rr[i] = bedR[i] + vR[i] * MIX.vo;
  }
  const log = [];
  if (vo) {
    log.push('vo line gains (dB) ' + vo.levels.join(' '));
    // voice-over-bed ratio per line: speech RMS vs everything else during that line
    const ratios = VO.clips.map(c => {
      const i0 = Math.floor(c.on * SR), i1 = Math.floor(c.off * SR);
      return [c.id, 20 * Math.log10(rms(vL, vR, i0, i1) * MIX.vo / Math.max(1e-6, rms(bedL, bedR, i0, i1)))];
    });
    log.push('voice over bed (dB): ' + ratios.map(([id, r]) => `${id} ${r.toFixed(1)}`).join(', '));
    log.push(`min voice-over-bed ${Math.min(...ratios.map(r => r[1])).toFixed(1)} dB`);
  }
  // loudness target by RMS over the body, then a look-ahead limiter at -1.5 dBFS
  const a0 = Math.floor(8 * SR), a1 = Math.floor(72 * SR);
  const g = 10 ** (MIX.targetRms / 20) / rms(L, Rr, a0, a1);
  for (let i = 0; i < len; i++) { L[i] *= g; Rr[i] *= g; }
  const red = limit(L, Rr, 0.84);
  log.push(`pre-gain ${db(g)} dB, max limiter reduction ${red.toFixed(1)} dB`);
  return { sampleRate: SR, L, R: Rr, log: log.join('\n') };
}
window.renderAudio = renderAudio;
window.__audioReady = true;
