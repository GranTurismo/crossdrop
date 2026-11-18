#!/usr/bin/env python3
"""Submit an uploaded CrossDrop build to App Review through the App Store Connect API (stdlib + openssl only).

    appstore_submit.py --version 1.3.0 --build 4 --notes release/0.6.0/appstore.txt            # read-only: show the state
    appstore_submit.py --version 1.3.0 --build 4 --notes release/0.6.0/appstore.txt --submit   # do it

Needs ASC_KEY_ID, ASC_ISSUER_ID and ASC_KEY_PATH (the .p8). Run by GitHub Actions (the key only exists there).
For each platform (iPhone/iPad = IOS, the Mac Catalyst app = MAC_OS) it waits for the uploaded build to finish
processing, finds or creates the version, attaches the build, sets "What's New", and submits it for review. A version
that is already waiting, in review or live is left alone, so re-running is safe. Releases automatically after approval.
Exit status is 1 when any platform could not be submitted (the reasons are printed).
"""
import argparse, base64, json, os, subprocess, sys, time, urllib.error, urllib.parse, urllib.request

BASE = os.environ.get("ASC_BASE", "https://api.appstoreconnect.apple.com")
BUNDLE_ID = "com.granturismo.crossdrop"
EDITABLE = {"PREPARE_FOR_SUBMISSION", "DEVELOPER_REJECTED", "REJECTED", "METADATA_REJECTED", "INVALID_BINARY", "WAITING_FOR_EXPORT_COMPLIANCE"}
DRAFT = {"READY_FOR_REVIEW"}
SENT = {"WAITING_FOR_REVIEW", "IN_REVIEW", "PENDING_DEVELOPER_RELEASE", "PENDING_APPLE_RELEASE", "PROCESSING_FOR_APP_STORE",
        "PROCESSING_FOR_DISTRIBUTION", "ACCEPTED", "READY_FOR_SALE", "READY_FOR_DISTRIBUTION", "REPLACED_WITH_NEW_VERSION"}
PENDING = "PENDING_DEVELOPER_RELEASE"      # approved by Apple, waiting for the developer to press Release
RELEASING = {PENDING, "PROCESSING_FOR_DISTRIBUTION", "PROCESSING_FOR_APP_STORE"}
POLL = int(os.environ.get("ASC_POLL_SECONDS", "30"))


class ApiError(Exception):
    def __init__(self, status, body):
        self.status = status
        try:
            self.errors = json.loads(body).get("errors", [])
        except Exception:
            self.errors = [{"detail": body.decode("utf-8", "replace")[:300]}]
        super().__init__(f"HTTP {status}: " + "; ".join(e.get("detail") or e.get("title", "?") for e in self.errors))


def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def der_to_raw(der: bytes) -> bytes:
    """An ECDSA signature from openssl (DER: 30 len 02 rlen r 02 slen s) as the 64 raw bytes a JWT wants."""
    assert der[0] == 0x30
    i = 2 if der[1] < 0x80 else 2 + (der[1] & 0x7F)
    parts = []
    for _ in range(2):
        assert der[i] == 0x02
        n = der[i + 1]
        parts.append(der[i + 2:i + 2 + n].lstrip(b"\0").rjust(32, b"\0"))
        i += 2 + n
    return parts[0] + parts[1]


def token(key_id: str, issuer: str, key_path: str) -> str:
    now = int(time.time())
    head = b64(json.dumps({"alg": "ES256", "kid": key_id, "typ": "JWT"}, separators=(",", ":")).encode())
    body = b64(json.dumps({"iss": issuer, "iat": now, "exp": now + 1200, "aud": "appstoreconnect-v1"}, separators=(",", ":")).encode())
    signing_input = f"{head}.{body}".encode()
    der = subprocess.run(["openssl", "dgst", "-sha256", "-sign", key_path], input=signing_input, capture_output=True, check=True).stdout
    return f"{head}.{body}.{b64(der_to_raw(der))}"


class Asc:
    def __init__(self, key_id, issuer, key_path):
        self.creds = (key_id, issuer, key_path)

    def call(self, method, path, body=None):
        url = path if path.startswith("http") else BASE + path
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(url, data=data, method=method, headers={
            "Authorization": "Bearer " + token(*self.creds), "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=90) as r:
                raw = r.read()
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as e:
            raise ApiError(e.code, e.read())

    def get(self, path, **params):
        q = ("?" + urllib.parse.urlencode(params, safe="[],")) if params else ""
        return self.call("GET", path + q)


def state_of(v):
    a = v["attributes"]
    return a.get("appVersionState") or a.get("appStoreState") or "UNKNOWN"


def find_build(asc, app_id, platform, version, build):
    r = asc.get("/v1/builds", **{"filter[app]": app_id, "sort": "-uploadedDate", "limit": "40",
                                 "include": "preReleaseVersion", "fields[preReleaseVersions]": "version,platform"})
    pre = {i["id"]: i["attributes"] for i in r.get("included", []) if i["type"] == "preReleaseVersions"}
    for b in r.get("data", []):
        p = pre.get(((b.get("relationships", {}).get("preReleaseVersion") or {}).get("data") or {}).get("id"), {})
        if b["attributes"].get("version") == str(build) and p.get("version") == version and p.get("platform") == platform:
            return b
    return None


def wait_for_build(asc, app_id, platform, version, build, minutes, log):
    deadline = time.time() + minutes * 60
    while True:
        b = find_build(asc, app_id, platform, version, build)
        state = b["attributes"].get("processingState") if b else "NOT_FOUND_YET"
        if b and state == "VALID":
            return b
        if b and state in ("FAILED", "INVALID"):
            raise RuntimeError(f"Apple marked build {build} as {state}; check the email/App Store Connect")
        if time.time() >= deadline:
            raise RuntimeError(f"build {version} ({build}) is {state} after waiting {minutes} min")
        log(f"  build {version} ({build}) is {state}; waiting…")
        time.sleep(POLL)


def release_pending(asc, app_id, platform, pending, minutes, log):
    """Release an approved version that is waiting for the developer, then wait until Apple allows a new version."""
    asc.call("POST", "/v1/appStoreVersionReleaseRequests", {"data": {"type": "appStoreVersionReleaseRequests", "relationships": {
        "appStoreVersion": {"data": {"type": "appStoreVersions", "id": pending["id"]}}}}})
    log(f"  released {pending['attributes']['versionString']} (it was approved and waiting for release)")
    deadline = time.time() + minutes * 60
    while True:
        versions = asc.get(f"/v1/apps/{app_id}/appStoreVersions", **{"filter[platform]": platform, "limit": "20"}).get("data", [])
        now = next((v for v in versions if v["id"] == pending["id"]), None)
        if now is None or state_of(now) not in RELEASING:
            return versions
        if time.time() >= deadline:
            raise RuntimeError(f"{pending['attributes']['versionString']} is still {state_of(now)} after {minutes} min")
        log(f"  {pending['attributes']['versionString']} is {state_of(now)}; waiting…")
        time.sleep(POLL)


def whats_new_text(path):
    text = open(path, encoding="utf-8").read().strip()
    if not text or len(text) > 4000:
        raise RuntimeError(f"{path} must have 1-4000 characters (has {len(text)})")
    return text


def process(asc, app_id, platform, a, text, log):
    log(f"[{platform}]")
    versions = asc.get(f"/v1/apps/{app_id}/appStoreVersions", **{"filter[platform]": platform, "limit": "20"}).get("data", [])
    for v in versions:
        log(f"  version {v['attributes']['versionString']}: {state_of(v)}")
    target = next((v for v in versions if v["attributes"]["versionString"] == a.version), None)
    if target and state_of(target) in SENT:
        return f"{platform}: {a.version} is already {state_of(target)}: nothing to do"
    pending = next((v for v in versions if state_of(v) == PENDING and v is not target), None)
    if pending and not a.release_pending:
        what = f"{pending['attributes']['versionString']} is approved but still waiting for you to release it (Pending Developer Release)"
        if not a.submit:
            return f"{platform}: BLOCKED: {what}. Apple allows no new version until it is released."
        raise RuntimeError(what + ". Apple allows no new version until it is released: press Release in App Store Connect, "
                                  "or run again with release_pending on to let this script release it, then submit")

    build = (find_build(asc, app_id, platform, a.version, a.build) if not a.submit else
             wait_for_build(asc, app_id, platform, a.version, a.build, a.wait_minutes, log))
    log(f"  build {a.version} ({a.build}): " + (build["attributes"]["processingState"] if build else "not found (not uploaded yet?)"))
    if not a.submit:
        editable = [v for v in versions if state_of(v) in EDITABLE | DRAFT]
        plan = ("would reuse the editable version" if target else
                f"would {'rename the editable version ' + editable[0]['attributes']['versionString'] + ' to' if editable else 'create'} {a.version}")
        return f"{platform}: read-only, {plan}, attach build, set What's New, submit"
    if build is None:
        raise RuntimeError("the build isn't in App Store Connect")

    if pending and a.release_pending:
        versions = release_pending(asc, app_id, platform, pending, a.wait_minutes, log)
    if not target:
        editable = [v for v in versions if state_of(v) in EDITABLE | DRAFT]
        if editable:
            target = asc.call("PATCH", f"/v1/appStoreVersions/{editable[0]['id']}", {"data": {
                "type": "appStoreVersions", "id": editable[0]["id"], "attributes": {"versionString": a.version}}})["data"]
            log(f"  renamed the editable version to {a.version}")
        else:
            target = asc.call("POST", "/v1/appStoreVersions", {"data": {
                "type": "appStoreVersions", "attributes": {"platform": platform, "versionString": a.version, "releaseType": "AFTER_APPROVAL"},
                "relationships": {"app": {"data": {"type": "apps", "id": app_id}}}}})["data"]
            log(f"  created version {a.version}")
    vid = target["id"]

    asc.call("PATCH", f"/v1/appStoreVersions/{vid}/relationships/build", {"data": {"type": "builds", "id": build["id"]}})
    log("  attached the build")

    for loc in asc.get(f"/v1/appStoreVersions/{vid}/appStoreVersionLocalizations").get("data", []):
        locale = loc["attributes"]["locale"]
        try:
            asc.call("PATCH", f"/v1/appStoreVersionLocalizations/{loc['id']}", {"data": {
                "type": "appStoreVersionLocalizations", "id": loc["id"], "attributes": {"whatsNew": text}}})
            log(f"  What's New set for {locale}" + ("" if locale.startswith("en") else " (English text: no translation supplied)"))
        except ApiError as e:
            log(f"  What's New not set for {locale}: {e}")

    # one draft submission per platform: reuse an unsubmitted one, else open a new one
    subs = asc.get(f"/v1/apps/{app_id}/reviewSubmissions", **{"filter[platform]": platform, "filter[state]": "READY_FOR_REVIEW", "limit": "5"}).get("data", [])
    if subs:
        sid = subs[0]["id"]
    else:
        sid = asc.call("POST", "/v1/reviewSubmissions", {"data": {
            "type": "reviewSubmissions", "attributes": {"platform": platform},
            "relationships": {"app": {"data": {"type": "apps", "id": app_id}}}}})["data"]["id"]
    try:
        asc.call("POST", "/v1/reviewSubmissionItems", {"data": {"type": "reviewSubmissionItems", "relationships": {
            "reviewSubmission": {"data": {"type": "reviewSubmissions", "id": sid}},
            "appStoreVersion": {"data": {"type": "appStoreVersions", "id": vid}}}}})
    except ApiError as e:
        if "already" not in str(e).lower():
            raise
    asc.call("PATCH", f"/v1/reviewSubmissions/{sid}", {"data": {"type": "reviewSubmissions", "id": sid, "attributes": {"submitted": True}}})
    return f"{platform}: {a.version} ({a.build}) submitted for App Review (releases automatically after approval)"


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--version", required=True)
    p.add_argument("--build", required=True)
    p.add_argument("--notes", required=True, help="file with the English What's New text")
    p.add_argument("--platforms", default="IOS,MAC_OS")
    p.add_argument("--wait-minutes", type=int, default=40, help="how long to wait for Apple to process the build")
    p.add_argument("--submit", action="store_true", help="really submit (the default only reads and prints)")
    p.add_argument("--release-pending", action="store_true", help="first release an older version that Apple approved but that still waits for the developer (a public release!)")
    a = p.parse_args()
    asc = Asc(os.environ["ASC_KEY_ID"], os.environ["ASC_ISSUER_ID"], os.environ["ASC_KEY_PATH"])
    log = lambda s: print(s, flush=True)

    text = whats_new_text(a.notes)
    apps = asc.get("/v1/apps", **{"filter[bundleId]": BUNDLE_ID, "limit": "1"}).get("data", [])
    if not apps:
        sys.exit(f"no app with bundle id {BUNDLE_ID} in this App Store Connect account")
    app_id = apps[0]["id"]
    log(f"app {apps[0]['attributes'].get('name')} ({app_id}), version {a.version} build {a.build}, {'SUBMIT' if a.submit else 'read-only'}")

    results, failed = [], False
    for platform in a.platforms.split(","):
        try:
            results.append(process(asc, app_id, platform, a, text, log))
        except (ApiError, RuntimeError) as e:
            failed = True
            results.append(f"{platform}: NOT submitted: {e}")
            if isinstance(e, ApiError):
                for err in e.errors:
                    log(f"  error: {err.get('title', '')} {err.get('detail', '')} {err.get('source', '')}")
    log("")
    for r in results:
        log(r)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write("### App Store review\n\n" + "\n".join(f"- {r}" for r in results) + "\n")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
