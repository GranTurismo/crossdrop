# Translations

One JSON file per language, shared by Android, desktop and iOS: `l10n/<lang>.json`.
Languages: `ka` Georgian, `hi` Hindi, `id` Indonesian, `ar` Arabic, `fa` Persian, `es` Spanish (neutral
Latin American), `pt` Brazilian Portuguese, `ru` Russian, `tr` Turkish. English is the code itself.

The **English text is the key**: code says `tr("Send to {0}", name)`, the catalog says
`"Send to {0}": "<translation>"`. A missing entry shows the English text, never a blank.
Plurals are objects keyed by CLDR category (`{"one": "...", "other": "..."}`; Russian also `few`/`many`,
Arabic `zero`/`one`/`two`/`few`/`many`/`other`, Indonesian only `other`).
Placeholders are positional (`{0}`, `{1}`) and `{n}` is a plural's count; keep them exactly.

Workflow (`tools/l10n/l10n.py`):

    python3 tools/l10n/l10n.py extract   # code -> tools/l10n/source/en.json (+ context.json: where each string appears)
    python3 tools/l10n/l10n.py check     # every language complete, placeholders and plural forms right
    python3 tools/l10n/l10n.py audit     # user-visible literals still not wrapped in tr()
    python3 tools/l10n/l10n.py plist     # iOS permission texts -> ios/CrossDrop/Resources/<lang>.lproj/InfoPlist.strings

Add a string: wrap it in `tr()` (Kotlin: `com.crossdrop.core.i18n.tr`, Swift: `tr` in `Services/I18n.swift`), run
`extract`, add the key to every `l10n/<lang>.json`, run `check`. The runtime is `core/.../i18n/I18n.kt` and
`ios/CrossDrop/Services/I18n.swift` (keep the two in step).
