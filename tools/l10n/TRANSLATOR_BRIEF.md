# Brief: translate CrossDrop into ONE language

CrossDrop is a free, private, no-account app that sends files straight between phones and computers on the same
Wi-Fi. People using it are ordinary users, often not technical: the words must be **plain, short and friendly**.

Input:  `tools/l10n/source/en.json` (every string; plural entries are objects) and `tools/l10n/source/context.json`
        (for each string, up to three `file:line` places it is used: open them when the meaning is unclear: a button, a
        title, a sentence, a status).
Output: `l10n/<lang>.json`: the same keys, values translated. UTF-8, valid JSON, `ensure_ascii=False`, keys sorted.

## Format rules (a checker enforces these: `python3 tools/l10n/l10n.py check`, it prints only your language's problems)

1. **Keys are the English text: copy them exactly.** Values are your translation.
2. **Placeholders** `{0}`, `{1}`, `{n}` must appear in the translation exactly as written (they are replaced at run time by
   a device name, a number, a file name …). You may move them to where the grammar needs them. Never translate or
   respell them. Do not add placeholders that are not in the English.
3. **Plural entries** are objects. The English has `one` and `other`. Provide exactly the categories for your language:
   `ka`, `hi`, `fa`, `es`, `pt`, `tr`: `one`, `other` · `id`: `other` · `ru`: `one`, `few`, `many`, `other`
   (1, 21 → one · 2–4, 22–24 → few · 0, 5–20, 25–30 → many) · `ar`: `zero`, `one`, `two`, `few` (3–10), `many` (11–99), `other` (100+).
   Each form is a complete phrase and normally contains `{n}` (an Arabic `one`/`two`/`zero` form may omit it).
   The English `one` forms write a literal "1" or "a" ("1 file", "a contact"). In your language the `one` category may cover more
   than the number 1 (`ru`: 21, 31, 101 …; `hi`, `fa`, `pt`: also 0), so in `ru`, `hi`, `fa` and `pt` ALWAYS put `{n}` in the `one`
   form instead of a literal 1 or a number-free wording. In `es`, `tr`, `ka` the `one` form is exactly 1, so a literal or number-free
   wording is fine. Arabic `zero`/`one`/`two` forms may omit `{n}` (they mean exactly 0, 1, 2).
4. Keep ellipsis `…`, quotes `“ ”`, arrows `→`, emoji, `·`, `—` and line breaks (`\n`) where the English has them (use the
   quote style normal for your language). Keep digits as ASCII 0–9 in every language.
5. **Never translate** these (copy as is): `CrossDrop`, `Wi-Fi` (as the product word), `QR`, `Bluetooth`, `WebDAV`, `iPhone`,
   `iPad`, `Mac`, `Windows`, `Android`, `Finder`, `Explorer`, `AirDrop`, `X25519`, `HKDF-SHA256`, `ChaCha20-Poly1305`, `BLAKE3`,
   key names (`⌘K`), units (`KB`, `MB`, `MB/s`), file extensions, device names. If your language writes a common word in
   its own script (hotspot, browser, photo, video, contacts, inbox), use the form real phone users in that country see in
   their phone's own settings and apps (check how Google/Apple phrase it) and **use the same word everywhere** in the file.
6. Pronoun/tone: prefer imperative verbs without pronouns. When a pronoun is needed: `ru` вы · `es` tú · `pt` você · `tr` sen ·
   `id` kamu · `hi` आप · `fa` شما · `ka` შენ · `ar` neutral (avoid gendered forms where you can).
   `ar` is Modern Standard Arabic everyday register; `pt` is Brazilian Portuguese; `es` is neutral Latin American Spanish.
7. **Length**: the screens are small and fixed-width. Prefer the shorter natural phrasing: buttons, chips, tab names and
   labels should not be much longer than the English (about +30% at most); sentences can be longer.
8. **Meaning, not word-for-word.** Many keys are UI labels ("Send", "Open", "Clear") whose meaning depends on the screen:
   read the context. Status words ("Completed", "Failed") are labels of a transfer. "Radar" is the home tab (nearby devices).
   "Inbox" is where received files land. "Drive"/"Wireless Drive" is this device's folder shared on the network. "Send to
   anyone" sends to a device that has no app via a QR code and a web page.
9. **Right-to-left** (`ar`, `fa`): translate normally; do not insert direction marks or reorder Latin words/placeholders.
10. Do not leave an entry equal to the English unless the English is a brand/technical term or genuinely the same word in your language.

## How to work

The file is big (hundreds of entries). Translate in chunks of ~150 entries, writing each chunk to a work file, then merge:

    mkdir -p /tmp/l10n-<lang>; # write /tmp/l10n-<lang>/part1.json, part2.json ... each a JSON object of finished entries
    python3 - <<'PY'
    import json,glob
    out={}
    for p in sorted(glob.glob('/tmp/l10n-<lang>/part*.json')): out.update(json.load(open(p,encoding='utf-8')))
    json.dump(dict(sorted(out.items())), open('l10n/<lang>.json','w',encoding='utf-8'), ensure_ascii=False, indent=1)
    PY
    python3 tools/l10n/l10n.py check | grep '^<lang>'

Fix every error the checker reports for your language (missing keys, placeholder mismatches, missing plural forms) until it
says `<lang>: N/N entries` with no INCOMPLETE marker. Do not touch any other file. Finally re-read a sample of ~30 entries
for naturalness and terminology consistency.

## Report (under 150 words)

Terminology choices you made (hotspot, browser, inbox, drive …), anything you were unsure about, and entries a native
speaker should double-check.
