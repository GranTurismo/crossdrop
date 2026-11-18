#!/usr/bin/env bash
# One command to release everywhere.
#
#   tools/release/release.sh v0.6.0
#
# Checks the versions and notes, tags and pushes, then waits for GitHub's release workflow, which builds every
# platform and publishes: the GitHub release, Google Play (sent for review) and the Microsoft Store (submitted
# for certification). Pushing the tag by hand does the same; this just adds the checks and the waiting.
set -euo pipefail

tag="${1:-}"
[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "usage: $0 vX.Y.Z" >&2; exit 2; }
version="${tag#v}"

cd "$(git rev-parse --show-toplevel)"
die() { echo "release: $*" >&2; exit 1; }

[ "$(git rev-parse --abbrev-ref HEAD)" = main ] || die "switch to main first (releases are cut from main)"
git fetch -q origin main
[ -z "$(git status --porcelain --untracked-files=no)" ] || die "there are uncommitted changes to tracked files"
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || die "main isn't the same as origin/main (push or pull first)"
if git rev-parse -q --verify "refs/tags/$tag" >/dev/null || git ls-remote --exit-code --tags origin "$tag" >/dev/null 2>&1; then
  die "tag $tag already exists"
fi

gradle_v="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)"
desktop_v="$(sed -n 's/.*APP_VERSION = "\(.*\)".*/\1/p' desktop/src/main/kotlin/com/crossdrop/Main.kt)"
[ "$gradle_v" = "$version" ] || die "app/build.gradle.kts says $gradle_v, not $version"
[ "$desktop_v" = "$version" ] || die "Main.kt says $desktop_v, not $version"
for f in whats-new.md play.txt msstore.txt appstore.txt; do [ -s "release/$version/$f" ] || die "missing release/$version/$f"; done

echo "tagging ${tag}…"
git tag -a "$tag" -m "CrossDrop $version"
git push -q origin "$tag"

echo "waiting for the release workflow of ${tag}…"
run=""
for _ in $(seq 1 30); do
  run="$(gh run list --workflow release.yml --branch "$tag" --limit 1 --json databaseId --jq '.[0].databaseId // empty')"
  [ -n "$run" ] && break
  sleep 4
done
[ -n "$run" ] || die "GitHub didn't start the release workflow for $tag"
echo "run $run: $(gh run view "$run" --json url --jq .url)"

if gh run watch "$run" --interval 20 --exit-status >/dev/null; then
  echo "released: GitHub, Google Play (in Google's review) and the Microsoft Store (in certification)."
  appstore="$(gh run view "$run" --json jobs --jq '.jobs[] | select(.name=="App Store review") | .conclusion')"
  case "$appstore" in
    success) echo "App Store: both builds sent to App Review (they release by themselves after approval)." ;;
    failure) echo "App Store: builds uploaded but NOT submitted. See the 'App Store review' job log, fix it in App Store Connect, then run the 'App Store submit' workflow." ;;
    *) echo "App Store: no new build in this release (or the review step was skipped)." ;;
  esac
else
  gh run view "$run" --json jobs --jq '.jobs[] | select(.conclusion=="failure") | "failed job: \(.name)"'
  die "the release workflow failed. Fix the cause, then use 'Re-run failed jobs' on the run above; jobs that already published are safe to repeat."
fi
