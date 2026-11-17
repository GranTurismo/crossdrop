#!/usr/bin/env bash
# Upload an Android App Bundle to Google Play with the Android Publisher API — no third-party action,
# just curl + openssl + jq (all preinstalled on GitHub's runners and on macOS).
#
#   play_upload.sh --check  <package>                         prove the key and its permissions work
#   play_upload.sh --upload <package> <app.aab> <notes.txt>   upload, roll out to production, send for review
#
# The service account key is read from $PLAY_SERVICE_ACCOUNT_JSON (its contents) or, when that is
# unset, from the file in $PLAY_SERVICE_ACCOUNT_FILE. Nothing secret is ever printed.
#
# Options (env): PLAY_TRACK (default production), PLAY_STATUS (completed = roll out now, draft = leave
# the release in Play Console), PLAY_USER_FRACTION (e.g. 0.2 for a 20% staged rollout, needs
# PLAY_STATUS=inProgress), PLAY_RELEASE_NAME, PLAY_NOTES_LANGUAGE (default en-US).
set -euo pipefail

API="https://androidpublisher.googleapis.com/androidpublisher/v3/applications"
UPLOAD="https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications"
TRACK="${PLAY_TRACK:-production}"
STATUS="${PLAY_STATUS:-completed}"
LANG_TAG="${PLAY_NOTES_LANGUAGE:-en-US}"

die() { echo "play_upload: $*" >&2; exit 1; }
need() { command -v "$1" >/dev/null || die "$1 is required"; }
need curl; need openssl; need jq

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
chmod 700 "$TMP"

load_key() {
  if [ -n "${PLAY_SERVICE_ACCOUNT_JSON:-}" ]; then printf '%s' "$PLAY_SERVICE_ACCOUNT_JSON" > "$TMP/sa.json"
  elif [ -n "${PLAY_SERVICE_ACCOUNT_FILE:-}" ]; then cp "$PLAY_SERVICE_ACCOUNT_FILE" "$TMP/sa.json"
  else die "set PLAY_SERVICE_ACCOUNT_JSON or PLAY_SERVICE_ACCOUNT_FILE"; fi
  chmod 600 "$TMP/sa.json"
  jq -e '.type=="service_account" and .client_email and .private_key' "$TMP/sa.json" >/dev/null \
    || die "that is not a service account key"
}

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

# OAuth2 JWT-bearer flow: sign a short-lived assertion with the key, exchange it for an access token.
token() {
  local email now header claim sig
  email="$(jq -r .client_email "$TMP/sa.json")"
  jq -r .private_key "$TMP/sa.json" > "$TMP/key.pem"; chmod 600 "$TMP/key.pem"
  now="$(date +%s)"
  header="$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)"
  claim="$(jq -cn --arg iss "$email" --argjson iat "$now" \
    '{iss:$iss, scope:"https://www.googleapis.com/auth/androidpublisher", aud:"https://oauth2.googleapis.com/token", iat:$iat, exp:($iat+3000)}' | b64url)"
  sig="$(printf '%s.%s' "$header" "$claim" | openssl dgst -sha256 -sign "$TMP/key.pem" | b64url)"
  rm -f "$TMP/key.pem"
  local resp; resp="$(curl -sS https://oauth2.googleapis.com/token \
    -d grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer -d "assertion=$header.$claim.$sig")"
  TOKEN="$(jq -r '.access_token // empty' <<<"$resp")"
  [ -n "$TOKEN" ] || die "could not get a token: $(jq -r '.error_description // .error // .' <<<"$resp")"
}

# call METHOD URL [curl args...] → prints the body, fails with Google's own message on an HTTP error.
call() {
  local method="$1" url="$2"; shift 2
  local out="$TMP/out.json" code
  code="$(curl -sS -o "$out" -w '%{http_code}' -X "$method" -H "Authorization: Bearer $TOKEN" "$@" "$url")"
  if [ "${code:0:1}" != "2" ]; then
    die "$method ${url%%\?*} → HTTP $code: $(jq -r '.error.message // .' "$out" 2>/dev/null | head -c 600)"
  fi
  cat "$out"
}

mode="${1:-}"; PKG="${2:-}"
[ -n "$mode" ] && [ -n "$PKG" ] || die "usage: $0 --check <package> | --upload <package> <app.aab> <notes.txt>"
load_key; token

new_edit() { call POST "$API/$PKG/edits" -H 'Content-Type: application/json' -d '{}' | jq -r .id; }

case "$mode" in
  --check)
    EDIT="$(new_edit)"
    cur="$(call GET "$API/$PKG/edits/$EDIT/tracks/$TRACK" | jq -c '[.releases[]? | {name, status, codes: .versionCodes}]')"
    call DELETE "$API/$PKG/edits/$EDIT" >/dev/null
    echo "ok  $PKG  can create edits; $TRACK releases: $cur"
    ;;

  --upload)
    AAB="${3:-}"; NOTES="${4:-}"
    [ -f "$AAB" ] || die "no such bundle: $AAB"
    [ -f "$NOTES" ] || die "no such notes file: $NOTES"
    [ "$(wc -m < "$NOTES")" -le 500 ] || die "release notes are over Google's 500-character limit"

    EDIT="$(new_edit)"
    echo "edit $EDIT: uploading $(du -h "$AAB" | cut -f1) bundle…"
    up="$(call POST "$UPLOAD/$PKG/edits/$EDIT/bundles?uploadType=media" -H 'Content-Type: application/octet-stream' --data-binary "@$AAB" --max-time 900)"
    CODE="$(jq -r .versionCode <<<"$up")"
    echo "uploaded version code $CODE"

    NAME="${PLAY_RELEASE_NAME:-$CODE}"
    release="$(jq -cn --arg name "$NAME" --arg code "$CODE" --arg status "$STATUS" --arg lang "$LANG_TAG" \
      --rawfile notes "$NOTES" --arg frac "${PLAY_USER_FRACTION:-}" '
      {name:$name, versionCodes:[$code], status:$status, releaseNotes:[{language:$lang, text:($notes|rtrimstr("\n"))}]}
      + (if $frac != "" then {userFraction:($frac|tonumber)} else {} end)')"
    call PUT "$API/$PKG/edits/$EDIT/tracks/$TRACK" -H 'Content-Type: application/json' \
      -d "$(jq -cn --arg track "$TRACK" --argjson r "$release" '{track:$track, releases:[$r]}')" >/dev/null
    call POST "$API/$PKG/edits/$EDIT:validate" >/dev/null
    # With "Managed publishing" off (this app), committing sends the change straight to Google's review.
    call POST "$API/$PKG/edits/$EDIT:commit" >/dev/null
    echo "committed: $PKG $NAME is on the $TRACK track ($STATUS) and sent for review"
    ;;

  *) die "unknown mode $mode" ;;
esac
