#!/usr/bin/env python3
"""CrossDrop translation tooling (stdlib only).

    l10n.py extract            code -> tools/l10n/source/en.json (+ context.json)
    l10n.py check              every l10n/<lang>.json is complete and well-formed
    l10n.py audit [paths...]   user-visible string literals still not wrapped in tr()
    l10n.py plist              write ios/CrossDrop/Resources/<lang>.lproj/InfoPlist.strings
    l10n.py review <lang>      write tools/l10n/review/<lang>.html (read, search) and <lang>.csv (edit in a spreadsheet)
    l10n.py import <lang> <csv>  merge an edited review CSV back into l10n/<lang>.json (placeholders are checked)

The English text IS the key (`tr("Send to {0}", name)`); see tools/l10n/README.md.
"""
import json, os, re, sys, glob

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SRC = os.path.join(ROOT, "tools", "l10n", "source")
LANGS = ["ka", "hi", "id", "ar", "fa", "es", "pt", "ru", "tr"]
PLURALS = {  # CLDR categories each language's plural entries must provide
    "ka": ["one", "other"], "hi": ["one", "other"], "id": ["other"], "ar": ["zero", "one", "two", "few", "many", "other"],
    "fa": ["one", "other"], "es": ["one", "other"], "pt": ["one", "other"], "ru": ["one", "few", "many", "other"],
    "tr": ["one", "other"],
}
KOTLIN_ROOTS = ["app/src/main", "desktop/src/main", "core/src/main"]
SWIFT_ROOTS = ["ios/CrossDrop", "ios/CrossDropShare"]

KT_LIT = r'"((?:[^"\\\n]|\\.)*)"'
RE_TR = re.compile(r'(?<![A-Za-z0-9_.])tr\(\s*' + KT_LIT)
RE_PLURAL_KT = re.compile(r'(?<![A-Za-z0-9_.])trPlural\(\s*[^,()]+?(?:\([^()]*\))?[^,()]*,\s*' + KT_LIT + r'\s*,\s*' + KT_LIT)
RE_PLURAL_SW = re.compile(r'(?<![A-Za-z0-9_.])trPlural\(\s*[^,()]+?(?:\([^()]*\))?[^,()]*,\s*one:\s*' + KT_LIT + r'\s*,\s*other:\s*' + KT_LIT)
RE_BAD_CONCAT = re.compile(r'(?<![A-Za-z0-9_.])tr(?:Plural)?\(\s*(?:[^",()]+,\s*)?(?:one:\s*)?' + KT_LIT + r'\s*\+')
RE_BAD_VAR = re.compile(r'(?<![A-Za-z0-9_."])tr\(\s*[A-Za-z_$]')


def files(roots, ext):
    for r in roots:
        for p in glob.glob(os.path.join(ROOT, r, "**", "*" + ext), recursive=True):
            if "/build/" in p or "/.build/" in p:
                continue
            yield p


def unescape(s, swift):
    out, i = [], 0
    while i < len(s):
        c = s[i]
        if c != "\\" or i + 1 >= len(s):
            out.append(c); i += 1; continue
        n = s[i + 1]
        if n == "u":
            if swift and s[i + 2:i + 3] == "{":
                j = s.index("}", i)
                out.append(chr(int(s[i + 3:j], 16))); i = j + 1
            else:
                out.append(chr(int(s[i + 2:i + 6], 16))); i += 6
            continue
        out.append({"n": "\n", "t": "\t", "r": "\r", '"': '"', "\\": "\\", "'": "'", "$": "$", "0": "\0"}.get(n, n))
        i += 2
    return "".join(out)


def split_args(text, start):
    """Top-level arguments of the call whose '(' is just before `start`: (list of source strings, end index) or None."""
    depth, i, cur, out = 1, start, [], []
    while i < len(text):
        c = text[i]
        if c == '"':
            j = i + 1
            while j < len(text) and text[j] != '"':
                j += 2 if text[j] == "\\" else 1
            cur.append(text[i:j + 1]); i = j + 1; continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                out.append("".join(cur).strip()); return out, i
        elif c == "," and depth == 1:
            out.append("".join(cur).strip()); cur = []; i += 1; continue
        cur.append(c); i += 1
    return None


def arg_problems(text, rel, swift):
    """tr()/trPlural() calls that pass fewer arguments than the text has {0}-style placeholders."""
    problems = []
    for m in re.finditer(r'(?<![A-Za-z0-9_.])(tr|trPlural)\(', text):
        kind = m.group(1)
        parsed = split_args(text, m.end())
        if not parsed:
            continue
        args, _ = parsed
        line = text.count("\n", 0, m.start()) + 1
        src_line = text.splitlines()[line - 1]
        if "rich(" in src_line or "keycapText(" in src_line:
            continue        # rich text: the placeholder is kept and styled by the helper
        if kind == "tr":
            if not args or not args[0].startswith('"'):
                continue
            literal = args[0][1:-1]
            extra = len(args) - 1
        else:
            if len(args) < 3:
                continue
            literal = args[2].split(":", 1)[-1].strip()[1:-1] if swift else args[2][1:-1]
            extra = len(args) - 3
        idx = [int(x) for x in re.findall(r"\{(\d+)\}", literal)]
        need = max(idx) + 1 if idx else 0
        if extra < need:
            problems.append(f"{rel}:{line}: '{literal[:60]}' needs {need} argument(s) but gets {extra}")
        elif extra > need:
            problems.append(f"{rel}:{line}: '{literal[:60]}' gets {extra} argument(s) but only has {need} placeholder(s)")
    return problems


def scan():
    """-> (strings: {key: [refs]}, plurals: {other: (one, [refs])}, problems: [str])"""
    strings, plurals, problems = {}, {}, []
    for ext, roots, swift, plural_re in ((".kt", KOTLIN_ROOTS, False, RE_PLURAL_KT), (".swift", SWIFT_ROOTS, True, RE_PLURAL_SW)):
        for path in files(roots, ext):
            if path.endswith("I18n.kt") or path.endswith("I18n.swift"):
                continue
            rel = os.path.relpath(path, ROOT)
            text = open(path, encoding="utf-8").read()
            for m in RE_TR.finditer(text):
                line = text.count("\n", 0, m.start()) + 1
                strings.setdefault(unescape(m.group(1), swift), []).append(f"{rel}:{line}")
            for m in plural_re.finditer(text):
                line = text.count("\n", 0, m.start()) + 1
                one, other = unescape(m.group(1), swift), unescape(m.group(2), swift)
                prev = plurals.get(other)
                if prev and prev[0] != one:
                    problems.append(f"{rel}:{line}: plural '{other}' has two different singular forms ('{prev[0]}' / '{one}')")
                plurals.setdefault(other, (one, []))[1].append(f"{rel}:{line}")
            problems.extend(arg_problems(text, rel, swift))
            for rx, msg in ((RE_BAD_CONCAT, "tr()/trPlural() must take ONE string literal (no '+')"),
                            (RE_BAD_VAR, "tr() must take a string literal, not a variable")):
                for m in rx.finditer(text):
                    problems.append(f"{rel}:{text.count(chr(10), 0, m.start()) + 1}: {msg}")
    extra = os.path.join(SRC, "extra.json")
    if os.path.exists(extra):
        for k in json.load(open(extra, encoding="utf-8")):
            strings.setdefault(k, []).append("tools/l10n/source/extra.json")
    for k in set(strings) & set(plurals):
        problems.append(f"'{k}' is used both as plain text and as a plural")
    return strings, plurals, problems


def placeholders(s):
    return sorted(re.findall(r"\{(?:n|\d+)\}", s))


def cmd_extract():
    strings, plurals, problems = scan()
    for p in problems:
        print("PROBLEM", p)
    en = {}
    for k in sorted(strings):
        en[k] = k
    for other, (one, _) in plurals.items():
        en[other] = {"one": one, "other": other}
    en = dict(sorted(en.items()))
    ctx = {k: refs[:3] for k, refs in {**strings, **{o: r for o, (_, r) in plurals.items()}}.items()}
    os.makedirs(SRC, exist_ok=True)
    json.dump(en, open(os.path.join(SRC, "en.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1, sort_keys=True)
    json.dump(dict(sorted(ctx.items())), open(os.path.join(SRC, "context.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"{len(strings)} strings + {len(plurals)} plurals -> tools/l10n/source/en.json")
    return 1 if problems else 0


def cmd_check():
    en_path = os.path.join(SRC, "en.json")
    en = json.load(open(en_path, encoding="utf-8"))
    errors = warnings = 0
    for lang in LANGS:
        path = os.path.join(ROOT, "l10n", lang + ".json")
        if not os.path.exists(path):
            print(f"{lang}: MISSING"); errors += 1; continue
        try:
            cat = json.load(open(path, encoding="utf-8"))
        except Exception as e:
            print(f"{lang}: invalid JSON: {e}"); errors += 1; continue
        missing = [k for k in en if k not in cat]
        stale = [k for k in cat if k not in en]
        bad = 0
        for k, src in en.items():
            if k not in cat:
                continue
            v = cat[k]
            if isinstance(src, dict):
                if not isinstance(v, dict):
                    print(f"{lang}: '{k}' must be a plural object"); bad += 1; continue
                need = PLURALS[lang]
                for cat_name in need:
                    if not v.get(cat_name):
                        print(f"{lang}: '{k}' lacks plural form '{cat_name}'"); bad += 1
                for cat_name, text in v.items():
                    if cat_name not in need:
                        print(f"{lang}: '{k}' has unused plural form '{cat_name}'"); warnings += 1
                    elif placeholders(text) not in (placeholders(src["other"]), []) and placeholders(text) != placeholders(src["other"]):
                        # a form may drop {n} when the word itself carries the number ("one": "a file")
                        extra = set(placeholders(text)) - set(placeholders(src["other"]))
                        if extra:
                            print(f"{lang}: '{k}' [{cat_name}] has unknown placeholders {sorted(extra)}"); bad += 1
            else:
                if not isinstance(v, str) or not v.strip():
                    print(f"{lang}: '{k}' is empty"); bad += 1; continue
                if placeholders(v) != placeholders(k):
                    print(f"{lang}: '{k}' placeholders {placeholders(k)} != {placeholders(v)} in '{v}'"); bad += 1
        for k in missing[:15]:
            print(f"{lang}: missing '{k}'")
        if len(missing) > 15:
            print(f"{lang}: ... and {len(missing) - 15} more missing")
        for k in stale[:5]:
            print(f"{lang}: stale (not in the code any more) '{k}'"); warnings += 1
        errors += len(missing) + bad
        print(f"{lang}: {len(en) - len(missing)}/{len(en)} entries" + ("" if not (missing or bad) else "  <-- INCOMPLETE"))
    print(f"errors={errors} warnings={warnings}")
    return 1 if errors else 0


# ---------------------------------------------------------------------------------------------- audit

AUDIT_SKIP_LINE = re.compile(
    r'^\s*(//|\*|/\*|import |package |@|#)|\b(Log\.[a-z]\(|NSLog\(|print\(|assert|precondition|fatalError\(|logger\.|'
    r'getSharedPreferences|SharedPreferences|UserDefaults|putExtra|getStringExtra|hasExtra|forKey:|key:|Notification\.Name|'
    r'Font\.custom|\.custom\(|systemName:|Image\(|UIImage\(|named:|identifier:|ContentType|UTType|MIME|mime|\.json|\.png)')
LIT = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
NOT_TEXT = re.compile(
    r'^[a-z0-9_.\-/:+*#@%$&=?!,;|\\<>()\[\]{}\' ]*$'             # identifiers, paths, formats, keys (no capital letters)
    r'|^[MmLlHhVvCcSsQqTtAaZz0-9 ,.\-]+$'                       # SVG path data
    r'|^[A-Z][A-Z0-9_]+$'                                       # CONSTANTS
    r'|^[A-Za-z0-9_.\-]+\.[A-Za-z0-9_.\-]+$'                    # dotted names, file names
    r'|^(http|file|content|market|mailto|WIFI|crossdrop)[:/]'    # URLs / schemes
    r'|^[A-Za-z][A-Za-z0-9]*([A-Z][a-z0-9]+)+$'                 # camelCase / PascalCase identifiers
    r'|^[A-Za-z]+-[A-Za-z]+(-[A-Za-z]+)*$'                      # font / kebab names (Sora-SemiBold)
    r'|^(%|\{|\$)')
INSIDE_TR = re.compile(r'(?<![A-Za-z0-9_.])tr(?:Plural)?\([^"\n]*$')


def audit_ignored():
    out = set()
    for p in [os.path.join(ROOT, "tools", "l10n", "audit_ignore.txt")] + glob.glob(os.path.join(ROOT, "tools", "l10n", "ignore", "*.txt")):
        if os.path.exists(p):
            out |= {l.rstrip("\n") for l in open(p, encoding="utf-8") if l.strip() and not l.startswith("#")}
    return out


def cmd_audit(paths):
    ignore = audit_ignored()
    hits = []
    targets = []
    if paths:
        for p in paths:
            p = os.path.join(ROOT, p) if not os.path.isabs(p) else p
            if os.path.isdir(p):
                targets += [q for ext in (".kt", ".swift") for q in glob.glob(os.path.join(p, "**", "*" + ext), recursive=True)]
            else:
                targets.append(p)
    else:
        targets = list(files(KOTLIN_ROOTS, ".kt")) + list(files(SWIFT_ROOTS, ".swift"))
    for path in sorted(set(targets)):
        if "/build/" in path or "/.build/" in path or path.endswith("I18n.kt") or path.endswith("I18n.swift") or path.endswith("CdIcons.kt") or path.endswith("CdIcons.swift"):
            continue
        swift = path.endswith(".swift")
        in_block = False
        for n, line in enumerate(open(path, encoding="utf-8"), 1):
            stripped = line.strip()
            if "/*" in stripped and "*/" not in stripped:
                in_block = True
            if in_block:
                if "*/" in stripped:
                    in_block = False
                continue
            if AUDIT_SKIP_LINE.search(line):
                continue
            code = line.split("//")[0] if "//" in line and "://" not in line else line
            for m in LIT.finditer(code):
                raw = m.group(1)
                text = unescape(raw, swift)
                if text in ignore or NOT_TEXT.match(text):
                    continue
                if not re.search(r"[A-Za-z]{3,}", re.sub(r"\$\{[^}]*\}|\$[A-Za-z_]+|\\\([^)]*\)", "", text)):
                    continue
                if INSIDE_TR.search(code[:m.start()]):
                    continue
                hits.append(f"{os.path.relpath(path, ROOT)}:{n}: {text[:90]!r}")
    for h in hits:
        print(h)
    print(f"{len(hits)} candidate string(s) not wrapped in tr()")
    return 1 if hits else 0


# ---------------------------------------------------------------------------------------------- iOS plist

PLIST_KEYS = {  # Info.plist key -> English text (also listed in source/extra.json so it gets translated)
    "NSLocalNetworkUsageDescription": "CrossDrop uses your local network to find nearby devices and send files directly to them — nothing leaves your Wi-Fi.",
    "NSCameraUsageDescription": "CrossDrop's camera sends each photo or video to the devices you picked the moment you take it.",
    "NSMicrophoneUsageDescription": "Sound for the videos you record in CrossDrop's camera.",
    "NSPhotoLibraryUsageDescription": "To switch phones, CrossDrop reads your photos and videos and moves them straight to your other device.",
}


def cmd_plist():
    n = 0
    for lang in LANGS:
        path = os.path.join(ROOT, "l10n", lang + ".json")
        if not os.path.exists(path):
            continue
        cat = json.load(open(path, encoding="utf-8"))
        lines = []
        for key, english in PLIST_KEYS.items():
            text = cat.get(english)
            if isinstance(text, str) and text:
                lines.append('"%s" = "%s";' % (key, text.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")))
        if lines:
            folder = os.path.join(ROOT, "ios", "CrossDrop", "Resources", ("pt-BR" if lang == "pt" else lang) + ".lproj")
            os.makedirs(folder, exist_ok=True)
            open(os.path.join(folder, "InfoPlist.strings"), "w", encoding="utf-8").write("\n".join(lines) + "\n")
            n += 1
    print(f"wrote InfoPlist.strings for {n} languages")
    return 0


# ---------------------------------------------------------------------------------------------- review

SCREENS = [  # source file stem -> the screen a person knows it by (in review order)
    ("WelcomeScreen", "Welcome tour"), ("RadarScreen", "Radar"), ("RadarCanvas", "Radar"), ("IncomingOfferSheet", "Incoming files"),
    ("TransferScreen", "Transfer in progress"), ("GroupTransferScreen", "Transfer in progress"), ("TransfersScreen", "Transfers tab"),
    ("InboxScreen", "Inbox"), ("SettingsScreen", "Settings"), ("LanguageUi", "Language"),
    ("ConnectHelp", "Can't see the other device?"), ("ConnectDirectScreen", "Connect directly"), ("DirectLink", "Connect directly"),
    ("QrShareScreen", "Send to anyone (QR)"), ("QrShareManager", "Send to anyone (QR)"),
    ("DriveHostScreen", "Wireless Drive"), ("DriveGuideScreen", "Wireless Drive"), ("VaultExplorerScreen", "Drive explorer"),
    ("SwitchScreens", "Switch phones"), ("SwitchReceiveScreen", "Switch phones"), ("SwitchPhones", "Switch phones"), ("SwitchImporter", "Switch phones"),
    ("SwitchSender", "Switch phones"), ("DesktopSwitchReceiver", "Switch phones"),
    ("CameraScreen", "Live camera"), ("LiveViewerScreen", "Live camera"),
    ("ContactDropCard", "Contacts"), ("ContactPickerScreen", "Contacts"), ("ContactShare", "Contacts"),
    ("DeviceNameDialog", "Device name"), ("OfferNotifier", "Notifications"), ("TransferService", "Notifications"), ("ShareActivity", "Share from other apps"),
    ("Components", "Common buttons"), ("FileTypeTile", "File types"), ("Root", "Navigation"), ("Main", "Desktop window and tray"),
    ("Platform", "Desktop window and tray"), ("TransferCoordinator", "Status messages"), ("TransferEngine", "Status messages"),
]
SCREEN_OF = dict(SCREENS)
SCREEN_ORDER = list(dict.fromkeys(name for _, name in SCREENS))


def screen_of(refs):
    for ref in refs:
        stem = os.path.splitext(os.path.basename(ref.split(":")[0]))[0]
        if stem in SCREEN_OF:
            return SCREEN_OF[stem]
    return "Other"


def review_rows(lang):
    en = json.load(open(os.path.join(SRC, "en.json"), encoding="utf-8"))
    ctx = json.load(open(os.path.join(SRC, "context.json"), encoding="utf-8"))
    cat = json.load(open(os.path.join(ROOT, "l10n", lang + ".json"), encoding="utf-8"))
    rows = []
    for key, src in en.items():
        refs = ctx.get(key, [])
        screen = screen_of(refs)
        where = refs[0] if refs else ""
        if isinstance(src, dict):
            for form in PLURALS[lang]:
                english = src.get(form) or (src["one"] if form == "one" else src["other"])
                rows.append((screen, f"{key}||{form}", english + (f"   [plural: {form}]"), cat.get(key, {}).get(form, ""), where))
        else:
            rows.append((screen, key, key, cat.get(key, ""), where))
    order = {n: i for i, n in enumerate(SCREEN_ORDER)}
    rows.sort(key=lambda r: (order.get(r[0], 999), r[0], r[2].lower()))
    return rows


def cmd_review(lang):
    import csv, html
    if lang not in LANGS:
        print("language must be one of", LANGS); return 2
    rows = review_rows(lang)
    out = os.path.join(ROOT, "tools", "l10n", "review"); os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, lang + ".csv"), "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(["key (do not edit)", "screen", "English", "translation (edit this column)", "where it appears"])
        for screen, key, english, text, where in rows:
            w.writerow([key, screen, english, text, where])
    parts, last = [], None
    for screen, key, english, text, where in rows:
        if screen != last:
            parts.append(f'<tr class="grp"><td colspan="2">{html.escape(screen)}</td></tr>'); last = screen
        parts.append(f'<tr><td class="en">{html.escape(english)}</td><td class="tr" lang="{lang}">{html.escape(text)}</td></tr>')
    page = f"""<!doctype html><html><head><meta charset="utf-8"><title>CrossDrop review: {lang}</title>
<meta name="viewport" content="width=device-width,initial-scale=1"><style>
body{{font:15px/1.45 -apple-system,Segoe UI,Roboto,sans-serif;margin:0;color:#14181d;background:#f6f7f9}}
header{{position:sticky;top:0;background:#fff;border-bottom:1px solid #dde1e6;padding:12px 20px;z-index:2}}
h1{{font-size:17px;margin:0 0 8px}} input{{width:100%;max-width:520px;padding:8px 10px;font-size:15px;border:1px solid #c5ccd4;border-radius:8px}}
table{{border-collapse:collapse;width:100%;max-width:1100px;margin:0 auto 60px}} td{{padding:9px 20px;border-bottom:1px solid #e6e9ed;vertical-align:top}}
td.en{{width:46%;color:#4a5560}} td.tr{{font-size:16px;color:#0b1f17}} tr.grp td{{background:#e9f7f1;font-weight:700;padding-top:18px;color:#0a6b4a}}
tr:hover td:not([colspan]){{background:#fffbe6}} .hide{{display:none}}
</style></head><body><header><h1>CrossDrop translation review: {lang} ({len(rows)} entries). Left: English. Right: the translation.</h1>
<input id="q" placeholder="Search English or translation…" autofocus></header><table id="t">{''.join(parts)}</table>
<script>const q=document.getElementById('q');q.oninput=()=>{{const v=q.value.toLowerCase();document.querySelectorAll('#t tr:not(.grp)').forEach(r=>r.classList.toggle('hide',v&&!r.textContent.toLowerCase().includes(v)))}}</script>
</body></html>"""
    open(os.path.join(out, lang + ".html"), "w", encoding="utf-8").write(page)
    print(f"{len(rows)} entries -> tools/l10n/review/{lang}.html and {lang}.csv")
    return 0


def cmd_import(lang, path):
    import csv
    p = os.path.join(ROOT, "l10n", lang + ".json")
    cat = json.load(open(p, encoding="utf-8"))
    en = json.load(open(os.path.join(SRC, "en.json"), encoding="utf-8"))
    changed = bad = 0
    with open(path, encoding="utf-8-sig", newline="") as f:
        for row in csv.reader(f):
            if len(row) < 4 or row[0].startswith("key (do not edit)"):
                continue
            key, text = row[0], row[3].strip()
            form = None
            if "||" in key:
                key, form = key.split("||", 1)
            if key not in en or not text:
                continue
            if form:
                old = cat.get(key, {}).get(form, "")
                if text != old:
                    if placeholders(text) != placeholders(en[key].get(form) or en[key]["other"]) and "{n}" in placeholders(en[key]["other"]) and "{n}" not in placeholders(text) and form in ("other", "many", "few") :
                        print(f"SKIPPED (placeholders): {row[0]} -> {text}"); bad += 1; continue
                    cat.setdefault(key, {})[form] = text; changed += 1
            else:
                if text != cat.get(key, ""):
                    if placeholders(text) != placeholders(key):
                        print(f"SKIPPED (placeholders {placeholders(key)} expected): {key} -> {text}"); bad += 1; continue
                    cat[key] = text; changed += 1
    json.dump(dict(sorted(cat.items())), open(p, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"updated {changed} entries in l10n/{lang}.json" + (f", skipped {bad}" if bad else ""))
    return 1 if bad else 0


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "extract": sys.exit(cmd_extract())
    if cmd == "check": sys.exit(cmd_check())
    if cmd == "audit": sys.exit(cmd_audit(sys.argv[2:]))
    if cmd == "plist": sys.exit(cmd_plist())
    if cmd == "review" and len(sys.argv) > 2: sys.exit(cmd_review(sys.argv[2]))
    if cmd == "import" and len(sys.argv) > 3: sys.exit(cmd_import(sys.argv[2], sys.argv[3]))
    print(__doc__); sys.exit(2)
