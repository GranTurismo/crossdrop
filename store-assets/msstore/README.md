# Microsoft Store listing text

The Windows app's Store listing, one folder per Partner Center language code:
`en-us`, `ar-sa`, `es-mx`, `pt-br`, `ru-ru`, `tr-tr`, `hi-in`, `id-id`, `ka-ge`, `fa-ir`.

Each folder has four plain-text files:

| File | Partner Center field | Limit |
|---|---|---|
| `description.txt` | Description | 10,000 characters, plain text, no URLs or HTML |
| `short_description.txt` | Short description | 1,000 characters (some views show only the first 270, so keep it shorter) |
| `features.txt` | Product features | One feature per line, at most 20 lines, 200 characters each, no bullet characters (the Store adds them) |
| `search_terms.txt` | Search terms | One term per line, at most 7 terms, 30 characters each, 21 words in total |

The English text is the source. The nine other languages are machine translations that reuse the
wording of the Play listing (`store-assets/listing/`) and the app's own UI terms (`l10n/*.json`).
They still need a native reader before they go live.

There is no upload tool yet. Enter them in Partner Center (the app → the next submission →
Store listings → pick the language; add missing languages with "Manage additional languages"),
or import them with the `msstore` CLI or Partner Center's listing CSV import, together with the
next submission.

Unlike the App Store text in `store-assets/appstore/`, these listings may name other platforms
(the Windows app works with Android, iPhone, iPad, Mac and Linux).
