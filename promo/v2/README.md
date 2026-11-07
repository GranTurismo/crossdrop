# CrossDrop promo v2

76-second 1080p60 promo, rendered frame-by-frame from HTML (no screen recording) with an
original soundtrack synthesized in Web Audio. Outputs: `../crossdrop-promo-v2.mp4` (music only),
`../crossdrop-promo-v2-voiceover.mp4` (female narration, `af_heart`) and
`../crossdrop-promo-v2-voiceover-male.mp4` (`am_fenrir`), each with an `.srt` caption file.
Without `js/vo.js` the page renders the music-only cut.

## Layout

- `js/timeline.js` — single source of truth for timing: 120 BPM grid, scene windows (`SCN`),
  named moments (`EV`) and the sound-effect cue sheet (`CUES`). Visuals and audio both read it,
  so moving an event moves its sound too.
- `js/core.js` — easing, springs, keyframes (`kf`), DOM helpers, scene/layer registry, `seek(t)`.
  Every frame is a pure function of `t`, so workers can render chunks in any order.
- `js/draw.js` — canvas renderers: radar (port of `RadarCanvas.kt`), liquid orb (`LiquidOrb.kt`),
  aurora sky, film grain, particles, shockwaves, QR.
- `js/ui.js` — device mockups (Android, iPhone, MacBook, Windows laptop) and CrossDrop screens
  recreated from the Compose/SwiftUI sources and `core/src/main/resources/share.html`.
- `js/screens.js` — what each device's screen shows at time `t`.
- `js/scenes1-3.js` — the eleven scenes (hook, logo, platforms, radar, send, crypto, delta,
  anyone, drive, pillars, outro) plus finishing layers (vignette, grain, flash, fade).
- `js/music.js` — arrangement (I–V–vi–IV in E, 120 BPM), instruments, SFX, tape-stop, voice bus
  (EQ + compression), ducking under speech, limiter.
- `vo/` — voice-over: `script.json` (lines + target times), `generate.py` (Kokoro-82M TTS, Whisper QA,
  PSOLA question rises), `place.py` (aligns each line's first/anchor word to its target time and writes
  `js/vo.js` + `captions.srt`), and the placed clips per voice (`af_heart/`, `am_fenrir/`).
  `js/vo.js` also drives word-synced visuals (platform labels, "No pairing…" chips, outro names).

## Voice-over

Needs a Python 3.12 venv with `kokoro>=0.9.4 soundfile numpy mlx-whisper praat-parselmouth` and
Homebrew `espeak-ng` (Kokoro's bundled espeak data path is broken on macOS; `generate.py` points it at
Homebrew's).

```sh
python vo/generate.py --voices af_heart --speeds vo/speeds.json --out /tmp/vo   # synth + Whisper QA
python vo/place.py /tmp/vo/af_heart                                            # -> js/vo.js, vo/af_heart/
```

## Render

```sh
npm install                                   # playwright-core + qrcode-generator
node render.mjs preview 12.5 27.4             # PNG stills -> out/preview/
node render.mjs audio                         # -> out/mix.wav (prints voice-over-vs-bed level per line)
node render.mjs video --workers 6 --crf 17    # -> out/video.mp4
ffmpeg -i out/video.mp4 -i out/mix.wav -map 0:v -map 1:a -c:v copy -c:a aac -b:a 256k \
  -movflags +faststart -shortest ../crossdrop-promo-v2.mp4
```

Uses Playwright's cached "Chrome for Testing" (override with `CHROME=/path/to/chrome`).
`index.html?play` previews in real time in a normal browser (serve the folder over HTTP);
`index.html?t=27.4` shows a single frame.
