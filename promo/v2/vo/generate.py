#!/usr/bin/env python3
"""Generate the promo voice-over with Kokoro-82M and QA every line with Whisper.

    python generate.py --voices af_heart,am_michael --out takes        # audition
    python generate.py --voices af_heart --speeds speeds.json --out final

Writes <out>/<voice>/<id>.wav (24 kHz mono) and <out>/<voice>/report.json with
word timestamps, speech on/offset, Whisper transcript and word error rate.
"""
import argparse, json, pathlib, re, sys
import numpy as np, soundfile as sf

# Kokoro's bundled espeak-ng has a build-machine data path on macOS; use Homebrew's.
import misaki.espeak  # noqa: F401  (sets the bundled paths first)
from phonemizer.backend.espeak.wrapper import EspeakWrapper
EspeakWrapper.set_library('/opt/homebrew/lib/libespeak-ng.dylib')
EspeakWrapper.set_data_path('/opt/homebrew/share/espeak-ng-data')
from kokoro import KPipeline

SR = 24000
HERE = pathlib.Path(__file__).parent
# Pronunciation hints (misaki inline phonemes). Brand compound stressed like "AirDrop".
PHON = {r'\bCrossDrop\b': '[CrossDrop](/kɹˈɔsdɹˌɑp/)'}


def prep(text):
    for k, v in PHON.items():
        text = re.sub(k, v, text)
    return text


def synth(pipe, text, voice, speed):
    audio, words, off = [], [], 0.0
    for r in pipe(prep(text), voice=voice, speed=speed):
        a = r.audio.numpy().astype(np.float32)
        for tk in (r.tokens or []):
            if tk.start_ts is not None and re.search(r'\w', tk.text):
                words.append({'w': tk.text, 's': round(off + tk.start_ts, 3), 'e': round(off + tk.end_ts, 3)})
        audio.append(a)
        off += len(a) / SR
    return np.concatenate(audio), words


def question_rise(a, word, rise_st, male=False):
    """Reshape the pitch of `word` (its voiced part) into a smooth rise of `rise_st`
    semitones, via Praat PSOLA. Kokoro tends to end questions with a fall."""
    import parselmouth
    from parselmouth.praat import call
    floor, ceil = (65, 260) if male else (120, 420)
    snd = parselmouth.Sound(a.astype(np.float64), sampling_frequency=SR)
    p = snd.to_pitch_ac(time_step=0.005, pitch_floor=floor, pitch_ceiling=ceil, octave_jump_cost=0.9)
    f, t = p.selected_array['frequency'], p.xs()
    v = t[(f > 0) & (t >= word['s']) & (t <= word['e'])]
    if len(v) < 4:
        return a
    ws, we = float(v[0]), float(v[-1])
    manip = call(snd, "To Manipulation", 0.005, floor * 0.6, ceil * 1.4)
    pt = call(manip, "Extract pitch tier")
    pts = [(call(pt, "Get time from index", i), call(pt, "Get value at index", i)) for i in range(1, call(pt, "Get number of points") + 1)]
    ref = float(np.median([fq for tt, fq in pts if ws <= tt <= ws + 0.06] or [fq for tt, fq in pts if tt <= ws][-3:]))
    call(pt, "Remove points between", ws, we)
    for tt in np.arange(ws, we + 1e-9, 0.01):
        u = (tt - ws) / max(1e-6, we - ws)
        call(pt, "Add point", float(tt), ref * 2 ** (rise_st * u * u * (3 - 2 * u) / 12))
    call([pt, manip], "Replace pitch tier")
    return np.asarray(call(manip, "Get resynthesis (overlap-add)").values[0], dtype=np.float32)


def speech_bounds(a, thr_db=-42.0, win=0.01):
    n = int(win * SR)
    frames = [np.sqrt(np.mean(a[i:i + n] ** 2) + 1e-12) for i in range(0, len(a) - n, n)]
    db = 20 * np.log10(np.array(frames) + 1e-12)
    peak = db.max()
    on = np.where(db > max(thr_db, peak - 40))[0]
    if not len(on):
        return 0.0, len(a) / SR
    return on[0] * win, (on[-1] + 1) * win


def norm(t):
    t = t.lower().replace('wi-fi', 'wifi').replace('wi fi', 'wifi').replace('end-to-end', 'end to end')
    t = t.replace('i os', 'ios').replace('cross drop', 'crossdrop').replace('crossdrop', 'crossdrop')
    return re.sub(r"[^a-z0-9' ]+", ' ', t).split()


def wer(ref, hyp):
    r, h = norm(ref), norm(hyp)
    d = [[0] * (len(h) + 1) for _ in range(len(r) + 1)]
    for i in range(len(r) + 1): d[i][0] = i
    for j in range(len(h) + 1): d[0][j] = j
    for i in range(1, len(r) + 1):
        for j in range(1, len(h) + 1):
            d[i][j] = min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (r[i - 1] != h[j - 1]))
    return d[len(r)][len(h)] / max(1, len(r))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--voices', default='af_heart')
    ap.add_argument('--out', default='takes')
    ap.add_argument('--speed', type=float, default=1.0)
    ap.add_argument('--speeds', help='json {id: speed} overrides')
    ap.add_argument('--only', help='comma list of line ids')
    ap.add_argument('--no-asr', action='store_true')
    a = ap.parse_args()
    script = json.loads((HERE / 'script.json').read_text())['lines']
    if a.only:
        keep = set(a.only.split(','))
        script = [l for l in script if l['id'] in keep]
    speeds = json.loads(pathlib.Path(a.speeds).read_text()) if a.speeds else {}
    pipe = KPipeline(lang_code='a', repo_id='hexgrad/Kokoro-82M')
    asr = None
    if not a.no_asr:
        import mlx_whisper
        asr = mlx_whisper
    for voice in a.voices.split(','):
        out = pathlib.Path(a.out) / voice
        out.mkdir(parents=True, exist_ok=True)
        rep_path = out / 'report.json'
        report = json.loads(rep_path.read_text()) if rep_path.exists() else {}
        for line in script:
            sp = speeds.get(line['id'], a.speed)
            audio, words = synth(pipe, line['text'], voice, sp)
            if line.get('rise'):
                q = line['rise']
                hits = [w for w in words if re.sub(r'\W', '', w['w']).lower() == q['word'].lower()]
                if hits:
                    audio = question_rise(audio, hits[q.get('nth', -1)], q['st'], male=voice[1] == 'm')
            on, off = speech_bounds(audio)
            path = out / f"{line['id']}.wav"
            sf.write(path, audio, SR, subtype='FLOAT')
            r = {'speed': sp, 'dur': round(len(audio) / SR, 3), 'on': round(on, 3), 'off': round(off, 3),
                 'speech': round(off - on, 3), 'slot': round(line['end'] - line['at'], 3), 'words': words}
            if asr:
                res = asr.transcribe(str(path), path_or_hf_repo='mlx-community/whisper-large-v3-turbo', language='en',
                                     word_timestamps=True, initial_prompt='CrossDrop. Wi-Fi. iOS.', condition_on_previous_text=False)
                r['asr'] = res['text'].strip()
                r['wer'] = round(wer(line['text'], r['asr']), 3)
                r['asr_words'] = [{'w': w['word'].strip(), 's': round(w['start'], 3), 'e': round(w['end'], 3)} for s in res['segments'] for w in s.get('words', [])]
            report[line['id']] = r
            flag = '' if r['speech'] <= r['slot'] else f"  OVER by {r['speech'] - r['slot']:.2f}s"
            print(f"{voice:10s} {line['id']:7s} sp={sp:.2f} speech={r['speech']:.2f}/{r['slot']:.2f}{flag}  wer={r.get('wer', '-')}  | {r.get('asr', '')}", flush=True)
        rep_path.write_text(json.dumps(report, indent=1))


if __name__ == '__main__':
    main()
