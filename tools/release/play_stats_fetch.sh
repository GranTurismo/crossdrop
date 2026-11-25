#!/usr/bin/env bash
# Gather Google Play statistics with the release service account, read-only (curl + openssl + jq, like play_upload.sh):
#
#   - monthly bulk reports from Play's Cloud Storage bucket: installs (overview, country, app version, Android
#     version, device, language), ratings, crashes, store performance (visitors, conversion, traffic source), reviews
#   - Android vitals (daily crash and ANR rates, last 30 days) and error issues: Play Developer Reporting API
#   - the last week's written reviews and the tracks (what is live): Android Publisher API
#
#   play_stats_fetch.sh <out-dir>
#
# Env: PLAY_PACKAGES (space-separated, default com.crossdrop), PLAY_MONTHS (bulk-report months incl. this one,
# default 3), PLAY_REPORTS_BUCKET (Play Console -> Download reports -> "Copy Cloud Storage URI", without gs://;
# empty = try pubsite_prod_rev_<developer id>, then pubsite_prod_<developer id>), PLAY_DEVELOPER_ID.
# Key: $PLAY_SERVICE_ACCOUNT_JSON (contents) or the file in $PLAY_SERVICE_ACCOUNT_FILE.
#
# A source that fails is noted in <out-dir>/errors.txt and the rest carry on. The log only says which source
# worked (and the HTTP status of one that didn't): no key, no numbers, no review text. CI runs this from
# .github/workflows/play-stats.yml, which encrypts <out-dir> before it leaves the runner.
set -euo pipefail

OUT="${1:-}"; [ -n "$OUT" ] || { echo "usage: $0 <out-dir>" >&2; exit 2; }
PACKAGES="${PLAY_PACKAGES:-com.crossdrop}"
MONTHS="${PLAY_MONTHS:-3}"
DEV_ID="${PLAY_DEVELOPER_ID:-4914878283957576311}"
BUCKET="${PLAY_REPORTS_BUCKET:-}"

PUBLISHER="https://androidpublisher.googleapis.com/androidpublisher/v3/applications"
REPORTING="https://playdeveloperreporting.googleapis.com/v1beta1"
STORAGE="https://storage.googleapis.com/storage/v1/b"
SCOPES="https://www.googleapis.com/auth/androidpublisher https://www.googleapis.com/auth/playdeveloperreporting https://www.googleapis.com/auth/devstorage.read_only"

die() { echo "play_stats: $*" >&2; exit 1; }
for t in curl openssl jq iconv; do command -v "$t" >/dev/null || die "$t is required"; done
[[ "$MONTHS" =~ ^[0-9]+$ ]] && [ "$MONTHS" -ge 1 ] && [ "$MONTHS" -le 24 ] || die "PLAY_MONTHS must be 1-24"
for p in $PACKAGES; do [[ "$p" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$ ]] || die "not a package name: $p"; done
[ -z "$BUCKET" ] || [[ "$BUCKET" =~ ^[a-z0-9_.-]+$ ]] || die "not a bucket name: $BUCKET"

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
chmod 700 "$TMP"
mkdir -p "$OUT"
ERRORS="$OUT/errors.txt"; : > "$ERRORS"

if [ -n "${PLAY_SERVICE_ACCOUNT_JSON:-}" ]; then printf '%s' "$PLAY_SERVICE_ACCOUNT_JSON" > "$TMP/sa.json"
elif [ -n "${PLAY_SERVICE_ACCOUNT_FILE:-}" ]; then cp "$PLAY_SERVICE_ACCOUNT_FILE" "$TMP/sa.json"
else die "set PLAY_SERVICE_ACCOUNT_JSON or PLAY_SERVICE_ACCOUNT_FILE"; fi
chmod 600 "$TMP/sa.json"
jq -e '.type=="service_account" and .client_email and .private_key' "$TMP/sa.json" >/dev/null \
  || die "that is not a service account key"

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

# OAuth2 JWT-bearer flow, one token for all three APIs.
email="$(jq -r .client_email "$TMP/sa.json")"
jq -r .private_key "$TMP/sa.json" > "$TMP/key.pem"; chmod 600 "$TMP/key.pem"
now="$(date +%s)"
header="$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)"
claim="$(jq -cn --arg iss "$email" --arg scope "$SCOPES" --argjson iat "$now" \
  '{iss:$iss, scope:$scope, aud:"https://oauth2.googleapis.com/token", iat:$iat, exp:($iat+3000)}' | b64url)"
sig="$(printf '%s.%s' "$header" "$claim" | openssl dgst -sha256 -sign "$TMP/key.pem" | b64url)"
rm -f "$TMP/key.pem" "$TMP/sa.json"
resp="$(curl -sS https://oauth2.googleapis.com/token \
  -d grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer -d "assertion=$header.$claim.$sig")"
TOKEN="$(jq -r '.access_token // empty' <<<"$resp")"
[ -n "$TOKEN" ] || die "could not get a token: $(jq -r '.error_description // .error // "no answer"' <<<"$resp")"
unset resp

# fetch LABEL FILE METHOD URL [curl args...]: writes the body to FILE; on an HTTP error notes it in errors.txt,
# removes FILE and returns 1. Prints one status line.
fetch() {
  local label="$1" file="$2" method="$3" url="$4"; shift 4
  local code
  code="$(curl -sS -o "$file" -w '%{http_code}' -X "$method" -H "Authorization: Bearer $TOKEN" "$@" "$url")" || code=000
  if [ "${code:0:1}" = "2" ]; then return 0; fi
  {
    printf '%s: %s %s -> HTTP %s\n' "$label" "$method" "${url%%\?*}" "$code"
    jq -r '.error.message // .' "$file" 2>/dev/null | head -c 1500 || true
    printf '\n\n'
  } >> "$ERRORS"
  rm -f "$file"
  echo "  $label: failed (HTTP $code)"
  return 1
}

# The months to fetch, newest first: YYYYMM.
month_list() {
  local y m i
  y="$(date -u +%Y)"; m="$((10#$(date -u +%m)))"
  for ((i = 0; i < MONTHS; i++)); do
    printf '%04d%02d\n' "$y" "$m"
    m=$((m - 1)); if [ "$m" -eq 0 ]; then m=12; y=$((y - 1)); fi
  done
}

# ---- bulk reports -------------------------------------------------------------------------------------------
echo "bulk reports"
if [ -z "$BUCKET" ]; then
  for cand in "pubsite_prod_rev_$DEV_ID" "pubsite_prod_$DEV_ID"; do
    if fetch "bucket $cand" "$TMP/probe.json" GET "$STORAGE/$cand/o?maxResults=1&prefix=stats/"; then
      BUCKET="$cand"; break
    fi
  done
fi
if [ -n "$BUCKET" ]; then
  echo "$BUCKET" > "$OUT/bucket.txt"
  for pkg in $PACKAGES; do
    dir="$OUT/reports/$pkg"; mkdir -p "$dir"
    names="$TMP/names.txt"; : > "$names"
    for prefix in "stats/installs/installs_${pkg}_" "stats/ratings/ratings_${pkg}_" "stats/crashes/crashes_${pkg}_" \
                  "stats/store_performance/store_performance_${pkg}_" "reviews/reviews_${pkg}_"; do
      token=""
      while :; do
        q="prefix=$(jq -rn --arg s "$prefix" '$s|@uri')&fields=items(name),nextPageToken"
        [ -z "$token" ] || q="$q&pageToken=$(jq -rn --arg s "$token" '$s|@uri')"
        fetch "list ${prefix%_}" "$TMP/list.json" GET "$STORAGE/$BUCKET/o?$q" || break
        jq -r '.items[]?.name' "$TMP/list.json" >> "$names"
        token="$(jq -r '.nextPageToken // empty' "$TMP/list.json")"
        [ -n "$token" ] || break
      done
    done
    n=0
    for ym in $(month_list); do
      while IFS= read -r name; do
        [ -n "$name" ] || continue
        base="${name##*/}"
        fetch "report $base" "$TMP/raw.csv" GET "$STORAGE/$BUCKET/o/$(jq -rn --arg s "$name" '$s|@uri')?alt=media" || continue
        # Play writes these as UTF-16 with a byte-order mark; store them as UTF-8.
        if head -c 2 "$TMP/raw.csv" | od -An -tx1 | grep -Eq 'ff fe|fe ff'; then
          iconv -f UTF-16 -t UTF-8 "$TMP/raw.csv" > "$dir/$base" 2>/dev/null || cp "$TMP/raw.csv" "$dir/$base"
        else
          cp "$TMP/raw.csv" "$dir/$base"
        fi
        n=$((n + 1))
      done < <(grep -E "_${ym}(_[a-z_]+)?\.csv$" "$names" || true)
    done
    echo "  $pkg: $n report files"
  done
else
  printf 'bulk reports: no readable bucket. Copy the Cloud Storage URI from Play Console -> Download reports and pass it as PLAY_REPORTS_BUCKET.\n\n' >> "$ERRORS"
  echo "  no readable bucket"
fi

# ---- vitals -------------------------------------------------------------------------------------------------
echo "vitals"
fetch "apps:search" "$OUT/vitals-apps.json" GET "$REPORTING/apps:search?pageSize=100" && echo "  apps: ok" || true
for pkg in $PACKAGES; do
  dir="$OUT/vitals/$pkg"; mkdir -p "$dir"
  for set in crashRateMetricSet anrRateMetricSet; do
    case "$set" in
      crashRateMetricSet) metrics='["crashRate","userPerceivedCrashRate","distinctUsers"]' ;;
      anrRateMetricSet)   metrics='["anrRate","userPerceivedAnrRate","distinctUsers"]' ;;
    esac
    fetch "$pkg $set" "$TMP/fresh.json" GET "$REPORTING/apps/$pkg/$set" || continue
    end="$(jq -c '[.freshnessInfo.freshnesses[]? | select(.aggregationPeriod=="DAILY") | .latestEndTime][0] // empty' "$TMP/fresh.json")"
    if [ -z "$end" ]; then echo "  $pkg $set: no daily data yet"; continue; fi
    body="$(jq -cn --argjson end "$end" --argjson metrics "$metrics" '
      ([$end.year, $end.month - 1, $end.day, 0, 0, 0, 0, 0] | mktime - 30 * 86400 | gmtime) as $s
      | {timelineSpec: {aggregationPeriod: "DAILY",
                        startTime: {year: $s[0], month: ($s[1] + 1), day: $s[2], timeZone: $end.timeZone},
                        endTime: $end},
         metrics: $metrics, pageSize: 1000}')"
    fetch "$pkg $set query" "$dir/$set.json" POST "$REPORTING/apps/$pkg/$set:query" \
      -H 'Content-Type: application/json' -d "$body" && echo "  $pkg $set: ok" || true
  done
  fetch "$pkg errorIssues" "$dir/errorIssues.json" GET "$REPORTING/apps/$pkg/errorIssues:search?pageSize=50" \
    && echo "  $pkg error issues: ok" || true
done

# ---- reviews (last week, written ones) and tracks --------------------------------------------------------------
echo "reviews and tracks"
for pkg in $PACKAGES; do
  dir="$OUT/publisher/$pkg"; mkdir -p "$dir"
  echo '[]' > "$dir/reviews.json"
  token=""
  while :; do
    q="maxResults=100"; [ -z "$token" ] || q="$q&token=$(jq -rn --arg s "$token" '$s|@uri')"
    fetch "$pkg reviews" "$TMP/rev.json" GET "$PUBLISHER/$pkg/reviews?$q" || break
    jq -s '.[0] + (.[1].reviews // [])' "$dir/reviews.json" "$TMP/rev.json" > "$TMP/acc.json" && mv "$TMP/acc.json" "$dir/reviews.json"
    token="$(jq -r '.tokenPagination.nextPageToken // empty' "$TMP/rev.json")"
    [ -n "$token" ] || break
  done
  echo "  $pkg reviews: $(jq length "$dir/reviews.json")"
  if fetch "$pkg edit" "$TMP/edit.json" POST "$PUBLISHER/$pkg/edits" -H 'Content-Type: application/json' -d '{}'; then
    edit="$(jq -r .id "$TMP/edit.json")"
    fetch "$pkg tracks" "$dir/tracks.json" GET "$PUBLISHER/$pkg/edits/$edit/tracks" && echo "  $pkg tracks: ok" || true
    fetch "$pkg edit delete" "$TMP/del.json" DELETE "$PUBLISHER/$pkg/edits/$edit" || true
  fi
done

date -u +%Y-%m-%dT%H:%M:%SZ > "$OUT/fetched-at.txt"
echo "done ($(grep -c 'HTTP' "$ERRORS" || true) failed requests, see errors.txt)"
