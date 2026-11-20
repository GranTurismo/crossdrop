#!/usr/bin/env python3
"""Read or update the App Store text of CrossDrop (description, promo text, keywords, subtitle) through the App Store Connect API.

    appstore_listing.py show                                  # read-only: print every field of every locale/platform, flag other platforms' names
    appstore_listing.py set --dir store-assets/appstore --apply   # write <dir>/<locale>/{description,promotional_text,keywords}.txt

Needs ASC_KEY_ID, ASC_ISSUER_ID and ASC_KEY_PATH (run by GitHub Actions: the key only exists there). `set` without --apply
only prints what would change. It writes to the version that is still editable (the one Apple rejected, or a draft);
a version that is waiting, in review or live is never touched. Apple's text rule (Guideline 2.3.10): the listing must not
mention other platforms such as Android, so `show` and `set` both warn about those words.
"""
import argparse, difflib, os, re, sys
from appstore_submit import Asc, ApiError, BUNDLE_ID, EDITABLE, DRAFT, state_of

PLATFORMS = ("IOS", "MAC_OS")
FIELDS = {"description": "description", "promotional_text": "promotionalText", "keywords": "keywords"}
LIMITS = {"description": 4000, "promotionalText": 170, "keywords": 100}
OTHER_PLATFORMS = re.compile(r"\b(android|google play|play store|chromebook|samsung|pixel)\b", re.I)


def versions_of(asc, app_id, platform):
    return asc.get(f"/v1/apps/{app_id}/appStoreVersions", **{"filter[platform]": platform, "limit": "20"}).get("data", [])


def flag(text):
    return sorted({m.group(0).lower() for m in OTHER_PLATFORMS.finditer(text or "")})


def show(asc, app_id):
    for platform in PLATFORMS:
        for v in versions_of(asc, app_id, platform):
            print(f"=== {platform} {v['attributes']['versionString']}: {state_of(v)}")
            for loc in asc.get(f"/v1/appStoreVersions/{v['id']}/appStoreVersionLocalizations").get("data", []):
                a = loc["attributes"]
                print(f"--- locale {a['locale']}")
                for name in ("description", "promotionalText", "keywords", "whatsNew"):
                    text = a.get(name) or ""
                    bad = flag(text)
                    print(f"[{name}] ({len(text)} chars)" + (f"   <-- mentions: {', '.join(bad)}" if bad else ""))
                    print(text)
                    print()
    for info in asc.get(f"/v1/apps/{app_id}/appInfos").get("data", []):
        print(f"=== appInfo {info['id']}: {info['attributes'].get('appStoreState') or info['attributes'].get('state')}")
        for loc in asc.get(f"/v1/appInfos/{info['id']}/appInfoLocalizations").get("data", []):
            a = loc["attributes"]
            for name in ("name", "subtitle"):
                bad = flag(a.get(name))
                print(f"[{a['locale']} {name}] {a.get(name)!r}" + (f"   <-- mentions: {', '.join(bad)}" if bad else ""))


def read(dirpath, locale, file):
    path = os.path.join(dirpath, locale, file + ".txt")
    if not os.path.exists(path):
        return None
    return open(path, encoding="utf-8").read().strip()


def set_listing(asc, app_id, dirpath, apply):
    changed = 0
    for platform in PLATFORMS:
        editable = [v for v in versions_of(asc, app_id, platform) if state_of(v) in EDITABLE | DRAFT]
        if not editable:
            print(f"[{platform}] no editable version (nothing rejected or in draft): left alone")
            continue
        v = editable[0]
        print(f"[{platform}] version {v['attributes']['versionString']} is {state_of(v)}")
        for loc in asc.get(f"/v1/appStoreVersions/{v['id']}/appStoreVersionLocalizations").get("data", []):
            locale = loc["attributes"]["locale"]
            attrs = {}
            for file, attr in FIELDS.items():
                new = read(dirpath, locale, file)
                if new is None:
                    continue
                if len(new) > LIMITS[attr]:
                    raise SystemExit(f"{locale}/{file}.txt has {len(new)} characters; Apple allows {LIMITS[attr]}")
                if flag(new):
                    raise SystemExit(f"{locale}/{file}.txt still mentions {', '.join(flag(new))}")
                old = (loc["attributes"].get(attr) or "").strip()
                if old != new:
                    attrs[attr] = new
                    print("".join(difflib.unified_diff((old + "\n").splitlines(True), (new + "\n").splitlines(True),
                                                       f"{locale}/{attr} (App Store Connect now)", f"{locale}/{attr} (file)", n=1)))
            for file, attr in FIELDS.items():
                if read(dirpath, locale, file) is None and flag(loc["attributes"].get(attr)):
                    print(f"  WARNING {locale}: {attr} mentions {', '.join(flag(loc['attributes'].get(attr)))} and has no file in {dirpath}")
            if not attrs:
                print(f"  {locale}: nothing to change")
                continue
            print(f"  {locale}: {'updating' if apply else 'would update'} {', '.join(attrs)}")
            if apply:
                asc.call("PATCH", f"/v1/appStoreVersionLocalizations/{loc['id']}", {"data": {
                    "type": "appStoreVersionLocalizations", "id": loc["id"], "attributes": attrs}})
            changed += 1
    print(f"{changed} localization(s) {'updated' if apply else 'would be updated (pass --apply)'}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("show")
    s = sub.add_parser("set")
    s.add_argument("--dir", default="store-assets/appstore")
    s.add_argument("--apply", action="store_true")
    a = p.parse_args()
    asc = Asc(os.environ["ASC_KEY_ID"], os.environ["ASC_ISSUER_ID"], os.environ["ASC_KEY_PATH"])
    apps = asc.get("/v1/apps", **{"filter[bundleId]": BUNDLE_ID, "limit": "1"}).get("data", [])
    if not apps:
        sys.exit(f"no app with bundle id {BUNDLE_ID} in this App Store Connect account")
    try:
        show(asc, apps[0]["id"]) if a.cmd == "show" else set_listing(asc, apps[0]["id"], a.dir, a.apply)
    except ApiError as e:
        sys.exit(f"App Store Connect said: {e}")


if __name__ == "__main__":
    main()
