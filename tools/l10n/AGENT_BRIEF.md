# Brief: wrap user-visible strings for translation

CrossDrop (the repository root) is being translated into Georgian, Hindi, Indonesian,
Arabic, Persian, Spanish, Portuguese, Russian and Turkish. **Your job is only to wrap every user-visible English string
in your file set in `tr(...)` / `trPlural(...)`.** Do not translate anything, do not build, do not commit. Other agents
are editing other files at the same time: touch only your file set (and your own `tools/l10n/ignore/<yourname>.txt`).
Never edit `l10n/`, `tools/l10n/l10n.py`, `I18n.kt`, `I18n.swift`, or any Theme file.

## The API (already written)

The **English text is the key**. Keep the existing wording, punctuation and capitalisation EXACTLY (do not fix typos,
do not rephrase): the same sentence in Android, desktop and iOS must stay character-identical so it is translated once.

Kotlin (`import com.crossdrop.core.i18n.tr` and `import com.crossdrop.core.i18n.trPlural`; `:core` is available to app and desktop):

    Text("Send to anyone")                       ->  Text(tr("Send to anyone"))
    Text("Send to ${peer.name}")                 ->  Text(tr("Send to {0}", peer.name))
    "Sent $n of $total"                          ->  tr("Sent {0} of {1}", n, total)
    if (n == 1) "1 file" else "$n files"         ->  trPlural(n, "1 file", "{n} files")
    if (n == 1) "1 file from ${p}" else "$n files from ${p}" -> trPlural(n, "1 file from {0}", "{n} files from {0}", p)

Swift (global functions, no import; `Services/I18n.swift`):

    Text("Send to anyone")                       ->  Text(tr("Send to anyone"))
    Text("Send to \(peer.name)")                 ->  Text(tr("Send to {0}", peer.name))
    Button("Cancel") { }                         ->  Button(tr("Cancel")) { }
    PillButton("Next", ...) / ChipButton(text: "Skip", ...) / BackHeader("Settings", ...) -> pass tr("...")
    .navigationTitle / .accessibilityLabel / .accessibilityHint / Label / Toggle / TextField prompt / .alert / .confirmationDialog -> tr("...")
    n == 1 ? "1 file" : "\(n) files"             ->  trPlural(n, one: "1 file", other: "{n} files")

Rules:
1. `tr()` and `trPlural()` take **one string literal** as the text: no variable, no `+` concatenation, no Kotlin `$x` /
   Swift `\(x)` interpolation inside the literal. Dynamic parts become positional placeholders `{0}`, `{1}`, … with the
   values passed as extra arguments (in order); in `trPlural` the count is `{n}`. (`tools/l10n/l10n.py` reads these literals
   with a regex; a variable or `+` is reported as a PROBLEM.) A long sentence may be a long single literal; join text
   that is split across `+` lines into ONE literal (that is the English key).
2. Translate whole sentences, not fragments: if a sentence is built from pieces, rewrite it as one literal with
   placeholders (word order differs between languages). For rich text (an `AnnotatedString`/`Text` concatenation with
   one styled word) keep one sentence with a placeholder for the styled part.
3. `trPlural(n, one, other)`: `n` must be a simple Int expression without commas (put it in a `val` first). The `other`
   form is the key. Where the singular has no number ("a file"), write that as `one`.
4. NEVER call `tr()` in a constant, `const val`, top-level `val`, `companion object` val, `object` val, `lazy`, enum
   constructor argument, `remember { }` cache, or Swift `static let` / stored property initialiser: the language is read
   at call time and a cached value stays in the old language. Use a getter / function / computed property instead
   (`enum class Tab { RADAR; val label get() = when (this) { RADAR -> tr("Radar") ... } }`; Swift `var title: String { switch self { case .radar: return tr("Radar") } }`).
   Keep identity uses of a string (map keys, `when(label)` comparisons, `rawValue`, UserDefaults keys) untouched: wrap only
   what is DISPLAYED.
5. ALL-CAPS display labels ("INCOMING FILES", "TODAY"): write the key in normal case (`tr("Incoming files")`) and show it with
   `.caps()` (Kotlin, `com.crossdrop.ui.theme.caps`) / `.caps` (Swift String extension) so scripts without capitals stay intact.
   Any `letterSpacing` / `.tracking(` on translated text must use `trackingEm(0.06f)` (Kotlin) / `CDFont.tracking(size, em: 0.06)` (Swift),
   which drop to 0 for Arabic, Persian, Hindi and Georgian. Monospace digits (PIN, sizes) can keep fixed spacing.
6. Enum status names shown to users (`status.lowercase().replaceFirstChar { it.uppercase() }`, `rawValue.uppercased()`): replace
   with an explicit `when`/`switch` returning `tr("Completed")`, `tr("Failed")`, … so each word is a literal.

## What to wrap

Everything a person can see or hear: `Text`, titles, subtitles, button/chip/pill labels, placeholders, dialog and sheet text,
section labels, empty states, hints, error and status messages that reach the screen, toasts, notification title/text,
share-sheet labels, `contentDescription` / accessibility labels and hints (TalkBack and VoiceOver users are real), menu and
tray items, window titles. Status/error strings created in services, coordinators or `:core` that end up on screen are
user-visible too (wrap them where they are created).

## What NOT to wrap

Brand and technical names: `CrossDrop`, `Wi-Fi` as a standalone word, `Bluetooth`, `QR`, `WebDAV`, `X25519`, `HKDF-SHA256`,
`ChaCha20-Poly1305`, `BLAKE3`, key names like `⌘K`; file extensions; units (`KB`, `MB/s`); device names; log messages
(`Log.d`, `NSLog`, `print`), exception messages that never reach the screen, JSON/preference/intent keys, MIME types, URLs,
SVG path data, font names, SF Symbol names, debug-only strings (inside `BuildConfig.DEBUG`/`#if DEBUG`, the "Developer"
section: loopback self-test, add peer by address, `dev_*` hooks) — leave those English. HTML/CSS served to browsers
(`core/.../VaultHttp.kt`, `HttpShareServer.kt`, `share.html`, `vault.html`) is out of scope. The iOS share extension
(`ios/CrossDropShare`) is out of scope.

If a string is deliberately left English and the audit would flag it, add the exact text (one per line) to
`tools/l10n/ignore/<yourname>.txt` with a `# reason` comment line above it.

## Verify (read-only; do NOT run gradle, xcodebuild or swift build: other agents share the tree)

    python3 tools/l10n/l10n.py audit <your paths...>     # lists user-visible literals still not wrapped; aim for zero
                                                          # (false positives are fine: ignore-file them or leave them)

Re-read each edited region for syntax: parentheses balanced, the right `import`, named/trailing arguments intact, no
double-wrapping. The Kotlin build and the Swift build will be run by the lead afterwards, so be careful with Swift
type-checking (`tr` returns `String`; `Text(tr(..))` is fine; do not pass an optional).

## Report (short, under 250 words)

Files changed; roughly how many strings wrapped; strings deliberately left English and why; any place you could not
wrap cleanly (cached/initialiser strings, text built from fragments, identity uses); layout spots you expect to break
with 30–40% longer text (fixed widths, single-line chips).
