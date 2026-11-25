#!/usr/bin/env bash
# Pull Google Play statistics through the "Play statistics" workflow (.github/workflows/play-stats.yml), which
# holds the service account key. A one-off key pair is made here, the workflow encrypts its results to the
# certificate, and only this machine can open them. The private key is gone when the script ends, and the
# (encrypted) artifact is deleted from GitHub once downloaded.
#
#   play_stats.sh <out-dir> [packages] [months]
#
#   packages  space-separated, default com.crossdrop
#   months    bulk-report months incl. this one, default 3
#   env PLAY_REPORTS_BUCKET   Play's reports bucket, if the workflow can't guess it
#   env PLAY_STATS_REF        branch to run the workflow from (default: the repository's default branch)
#
# <out-dir>/stats then holds reports/<pkg>/*.csv (UTF-8), vitals/<pkg>/*.json, publisher/<pkg>/{reviews,tracks}.json
# and errors.txt. Keep it out of the repository: it is private data.
set -euo pipefail

OUT="${1:-}"; PACKAGES="${2:-com.crossdrop}"; MONTHS="${3:-3}"
die() { echo "play_stats: $*" >&2; exit 1; }
[ -n "$OUT" ] || die "usage: $0 <out-dir> [packages] [months]"
for t in gh openssl jq tar base64; do command -v "$t" >/dev/null || die "$t is required"; done

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
chmod 700 "$TMP"
openssl req -x509 -newkey rsa:3072 -noenc -keyout "$TMP/key.pem" -out "$TMP/cert.pem" \
  -subj "/CN=crossdrop-play-stats" -days 2 2>/dev/null || die "could not make a key pair"

ref_args=(); [ -z "${PLAY_STATS_REF:-}" ] || ref_args=(--ref "$PLAY_STATS_REF")
since="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
gh workflow run play-stats.yml ${ref_args[@]+"${ref_args[@]}"} \
  -f recipient="$(base64 < "$TMP/cert.pem" | tr -d '\n')" \
  -f packages="$PACKAGES" -f months="$MONTHS" -f bucket="${PLAY_REPORTS_BUCKET:-}" >/dev/null

run=""
for _ in $(seq 1 30); do
  run="$(gh run list --workflow play-stats.yml --event workflow_dispatch --limit 5 \
    --json databaseId,createdAt --jq "[.[] | select(.createdAt >= \"$since\")] | last | .databaseId // empty")"
  [ -n "$run" ] && break
  sleep 2
done
[ -n "$run" ] || die "the workflow run didn't show up"
echo "run $run: $(gh run view "$run" --json url --jq .url)"

gh run watch "$run" --exit-status --interval 5 >/dev/null || die "the run failed: gh run view $run --log-failed"
gh run download "$run" -n play-stats -D "$TMP/dl"
openssl cms -decrypt -binary -inform DER -in "$TMP/dl/stats.p7m" -inkey "$TMP/key.pem" -recip "$TMP/cert.pem" \
  -out "$TMP/stats.tgz" || die "could not decrypt the result"

mkdir -p "$OUT"
rm -rf "$OUT/stats"
tar -xzf "$TMP/stats.tgz" -C "$OUT"

artifact="$(gh api "repos/{owner}/{repo}/actions/runs/$run/artifacts" --jq '.artifacts[] | select(.name=="play-stats") | .id')"
[ -z "$artifact" ] || gh api -X DELETE "repos/{owner}/{repo}/actions/artifacts/$artifact" >/dev/null || true

echo "statistics in $OUT/stats"
if [ -s "$OUT/stats/errors.txt" ]; then echo "some sources failed, see $OUT/stats/errors.txt"; fi
