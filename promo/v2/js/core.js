// Frame-exact animation core. Everything is a pure function of time `T`, so
// any frame can be rendered in any order (parallel workers seek at random).
'use strict';

const TAU = Math.PI * 2;
const clamp = (x, a = 0, b = 1) => (x < a ? a : x > b ? b : x);
const lerp = (a, b, t) => a + (b - a) * t;
const ramp = (t, t0, t1) => clamp((t - t0) / (t1 - t0));

const E = {
  lin: t => t,
  inSine: t => 1 - Math.cos((t * Math.PI) / 2),
  outSine: t => Math.sin((t * Math.PI) / 2),
  inOutSine: t => -(Math.cos(Math.PI * t) - 1) / 2,
  inQuad: t => t * t,
  outQuad: t => 1 - (1 - t) * (1 - t),
  inOutQuad: t => (t < 0.5 ? 2 * t * t : 1 - (-2 * t + 2) ** 2 / 2),
  inCubic: t => t ** 3,
  outCubic: t => 1 - (1 - t) ** 3,
  inOutCubic: t => (t < 0.5 ? 4 * t ** 3 : 1 - (-2 * t + 2) ** 3 / 2),
  inQuart: t => t ** 4,
  outQuart: t => 1 - (1 - t) ** 4,
  inOutQuart: t => (t < 0.5 ? 8 * t ** 4 : 1 - (-2 * t + 2) ** 4 / 2),
  outQuint: t => 1 - (1 - t) ** 5,
  inOutQuint: t => (t < 0.5 ? 16 * t ** 5 : 1 - (-2 * t + 2) ** 5 / 2),
  inExpo: t => (t <= 0 ? 0 : 2 ** (10 * t - 10)),
  outExpo: t => (t >= 1 ? 1 : 1 - 2 ** (-10 * t)),
  inOutExpo: t => (t <= 0 ? 0 : t >= 1 ? 1 : t < 0.5 ? 2 ** (20 * t - 10) / 2 : (2 - 2 ** (-20 * t + 10)) / 2),
  outBack: t => { const s = 1.70158; return 1 + (s + 1) * (t - 1) ** 3 + s * (t - 1) ** 2; },
  outBackSoft: t => { const s = 1.1; return 1 + (s + 1) * (t - 1) ** 3 + s * (t - 1) ** 2; },
  inBack: t => { const s = 1.70158; return (s + 1) * t ** 3 - s * t * t; },
  inOutBack: t => {
    const c = 1.70158 * 1.525;
    return t < 0.5 ? ((2 * t) ** 2 * ((c + 1) * 2 * t - c)) / 2 : ((2 * t - 2) ** 2 * ((c + 1) * (t * 2 - 2) + c) + 2) / 2;
  },
  outCirc: t => Math.sqrt(1 - (t - 1) ** 2),
};

/** Eased 0..1 progress of `t` through [t0, t1]. */
const ease = (t, t0, t1, fn = E.inOutCubic) => fn(ramp(t, t0, t1));

/** Damped spring 0 -> 1 (overshoots), driven by elapsed seconds. */
function spring(dt, f = 2.2, z = 0.45) {
  if (dt <= 0) return 0;
  const w = TAU * f, wd = w * Math.sqrt(1 - z * z);
  return 1 - Math.exp(-z * w * dt) * (Math.cos(wd * dt) + ((z * w) / wd) * Math.sin(wd * dt));
}

/** Keyframes: [[t, v, easeIntoNext?], ...] -> value at t (numbers or arrays). */
function kf(t, frames, def = E.inOutCubic) {
  if (t <= frames[0][0]) return frames[0][1];
  for (let i = 0; i < frames.length - 1; i++) {
    const a = frames[i], b = frames[i + 1];
    if (t < b[0]) {
      const p = (a[2] || def)((t - a[0]) / (b[0] - a[0]));
      if (Array.isArray(a[1])) return a[1].map((v, k) => v + (b[1][k] - v) * p);
      return a[1] + (b[1] - a[1]) * p;
    }
  }
  return frames[frames.length - 1][1];
}

/** In/out envelope: 0 before t0, rises over `inD`, holds, falls over `outD` ending at t1. */
function env(t, t0, t1, inD = 0.4, outD = 0.4, fin = E.outCubic, fout = E.inCubic) {
  if (t < t0 || t > t1) return 0;
  const a = inD > 0 ? fin(ramp(t, t0, t0 + inD)) : 1;
  const b = outD > 0 ? 1 - fout(ramp(t, t1 - outD, t1)) : 1;
  return Math.min(a, b);
}

function rng(seed) {
  let a = seed >>> 0;
  return () => {
    a |= 0; a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const hash01 = (i, s = 0) => { const r = rng(i * 7919 + s * 104729 + 13); r(); return r(); };

/** Smooth value noise for organic jitter (deterministic). */
function noise1(x, seed = 0) {
  const i = Math.floor(x), f = x - i;
  const a = hash01(i, seed), b = hash01(i + 1, seed);
  const u = f * f * (3 - 2 * f);
  return a + (b - a) * u;
}

// ----------------------------------------------------------------- DOM utils

function h(tag, cls, parent, html) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (html != null) e.innerHTML = html;
  if (parent) parent.appendChild(e);
  return e;
}

function css(el, s) { Object.assign(el.style, s); return el; }

/** Transform shorthand. Positions are offsets from the element's layout box. */
function tf(el, o) {
  let t = '';
  if (o.x || o.y) t += `translate(${(o.x || 0).toFixed(2)}px,${(o.y || 0).toFixed(2)}px) `;
  if (o.p) t += `perspective(${o.p}px) `;
  if (o.rx) t += `rotateX(${o.rx.toFixed(3)}deg) `;
  if (o.ry) t += `rotateY(${o.ry.toFixed(3)}deg) `;
  if (o.r) t += `rotate(${o.r.toFixed(3)}deg) `;
  const sx = o.sx ?? o.s ?? 1, sy = o.sy ?? o.s ?? 1;
  if (sx !== 1 || sy !== 1) t += `scale(${sx.toFixed(4)},${sy.toFixed(4)})`;
  el.style.transform = t;
  if (o.o !== undefined) {
    el.style.opacity = clamp(o.o).toFixed(4);
    el.style.visibility = o.o <= 0.002 ? 'hidden' : '';
  }
  if (o.blur !== undefined || o.b !== undefined || o.sat !== undefined) {
    let f = '';
    if (o.blur > 0.05) f += `blur(${o.blur.toFixed(2)}px) `;
    if (o.b !== undefined && Math.abs(o.b - 1) > 0.002) f += `brightness(${o.b.toFixed(3)}) `;
    if (o.sat !== undefined && Math.abs(o.sat - 1) > 0.002) f += `saturate(${o.sat.toFixed(3)}) `;
    el.style.filter = f || 'none';
  }
  return el;
}

function show(el, on) { el.style.display = on ? '' : 'none'; }
function op(el, o) { el.style.opacity = clamp(o).toFixed(4); el.style.visibility = o <= 0.002 ? 'hidden' : ''; }

/**
 * Split an element's text into word spans (each wrapped in a mask span), so
 * words can rise through an invisible floor. Preserves <span class="g"> etc.
 */
function splitWords(el) {
  const words = [];
  const walk = (node, parentCls) => {
    const kids = [...node.childNodes];
    for (const k of kids) {
      if (k.nodeType === 3) {
        const parts = k.textContent.split(/(\s+)/);
        const frag = document.createDocumentFragment();
        for (const p of parts) {
          if (!p) continue;
          if (/^\s+$/.test(p)) { frag.appendChild(document.createTextNode(' ')); continue; }
          const m = h('span', 'wm');
          const w = h('span', 'w' + (parentCls ? ' ' + parentCls : ''), m, p);
          frag.appendChild(m);
          words.push(w);
        }
        k.replaceWith(frag);
      } else if (k.nodeType === 1) {
        if (k.classList.contains('g') || k.classList.contains('dim')) {
          const cls = k.className;
          walk(k, cls);
          k.replaceWith(...k.childNodes);
        } else walk(k, parentCls);
      }
    }
  };
  walk(el, '');
  return words;
}

/** Make gradient text continuous across separately-transformed word spans. */
function gradientAcross(container) {
  const g = [...container.querySelectorAll('.w.g, .c.g')];
  if (!g.length) return;
  const lines = new Map();
  for (const w of g) {
    const r = w.getBoundingClientRect();
    const key = Math.round(r.top / 8);
    if (!lines.has(key)) lines.set(key, []);
    lines.get(key).push({ w, r });
  }
  for (const arr of lines.values()) {
    const x0 = Math.min(...arr.map(a => a.r.left)), x1 = Math.max(...arr.map(a => a.r.right));
    const width = x1 - x0;
    for (const { w, r } of arr) {
      w.style.backgroundSize = `${width}px 100%`;
      w.style.backgroundPosition = `${-(r.left - x0)}px 0`;
    }
  }
}

/** Animate words rising in: returns update(t) helper. */
function wordsIn(words, t, t0, { stagger = 0.06, dur = 0.7, dy = 1.0, blur = 10, fn = E.outQuart } = {}) {
  words.forEach((w, i) => {
    const p = fn(ramp(t, t0 + i * stagger, t0 + i * stagger + dur));
    tf(w, { y: (1 - p) * dy * w.offsetHeight, o: p, blur: (1 - p) * blur });
  });
}
function wordsOut(words, t, t0, { stagger = 0.03, dur = 0.45, dy = -0.6, blur = 8, fn = E.inCubic } = {}) {
  if (t < t0) return;
  words.forEach((w, i) => {
    const p = fn(ramp(t, t0 + i * stagger, t0 + i * stagger + dur));
    tf(w, { y: p * dy * w.offsetHeight, o: 1 - p, blur: p * blur });
  });
}

// --------------------------------------------------------------- scene graph

const STAGE = { scenes: [], layers: [], ready: null, frame: 0 };

/** Register a scene. `vis` = [from, to] global seconds when it is mounted. */
function scene(name, vis, build, update) {
  STAGE.scenes.push({ name, vis, build, update });
}
/** Register a persistent layer updated every frame (background, actors, fx). */
function layer(name, build, update, z = 0, opts = {}) {
  STAGE.layers.push({ name, build, update, z, ...opts });
}
const SHAKE = { x: 0, y: 0 };

async function boot() {
  await Promise.all([
    document.fonts.load('700 80px Sora'), document.fonts.load('800 80px Sora'),
    document.fonts.load('600 80px Sora'), document.fonts.load('400 40px Sora'),
    document.fonts.load('400 20px Inter'), document.fonts.load('500 20px Inter'),
    document.fonts.load('600 20px Inter'), document.fonts.load('700 20px Inter'),
    document.fonts.load('800 20px Inter'), document.fonts.load('300 20px Inter'),
    document.fonts.load('500 20px "JetBrains Mono"'), document.fonts.load('700 20px "JetBrains Mono"'),
  ]);
  await document.fonts.ready;
  const stage = document.getElementById('stage');
  const all = [
    ...STAGE.layers.map(l => ({ ...l, kind: 'layer' })),
    ...STAGE.scenes.map(s => ({ ...s, kind: 'scene', z: s.z ?? 30 })),
  ];
  for (const item of all) {
    item.root = h('div', item.kind === 'scene' ? 'scene' : 'layer', stage);
    item.root.dataset.name = item.name;
    item.root.style.zIndex = item.z;
    item.state = (await item.build(item.root)) || {};
    if (item.kind === 'scene') item.root.style.display = 'none';
  }
  STAGE.items = all;
  // second pass: layout-dependent fixups (gradient text across words)
  for (const item of all) {
    item.root.style.display = '';
    gradientAcross(item.root);
    if (item.state.afterLayout) item.state.afterLayout();
    if (item.kind === 'scene') item.root.style.display = 'none';
  }
  return true;
}

function seek(T) {
  STAGE.T = T;
  STAGE.frame = Math.round(T * FPS);
  for (const it of STAGE.items) {
    if (it.kind === 'scene') {
      const on = T >= it.vis[0] && T < it.vis[1];
      if (on !== it.mounted) { it.root.style.display = on ? '' : 'none'; it.mounted = on; }
      if (on) it.update(T, it.state, it.root);
    } else {
      it.update(T, it.state, it.root);
    }
    if (!it.noShake) {
      const s = (Math.abs(SHAKE.x) + Math.abs(SHAKE.y)) > 0.05 ? `translate(${SHAKE.x.toFixed(2)}px,${SHAKE.y.toFixed(2)}px)` : '';
      if (it._shake !== s) { it.root.style.transform = s; it._shake = s; }
    }
  }
  return STAGE.frame;
}

window.addEventListener('DOMContentLoaded', () => {
  STAGE.ready = boot().then(() => { seek(0); window.__ready = true; return true; });
  window.ready = () => STAGE.ready;
});
