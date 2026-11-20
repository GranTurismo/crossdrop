#!/usr/bin/env bash
# Runs INSIDE a clean Fedora container (see .github/workflows/desktop-linux.yml): installs the CrossDrop .rpm and
# starts the installed app under a virtual display.
#
#   docker run --rm -v "$PWD/tools/release:/tools:ro" -v "$PWD/desktop/build/compose/binaries/main/rpm:/rpm:ro" \
#     fedora:41 /tools/linux_smoke_rpm.sh
#
# The point is the package's dependencies: a clean Fedora has none of the X11/GL/font libraries, so the app only
# starts if the rpm asks for them and dnf installs them. Only the test tools (Xvfb, xwininfo) are installed by hand.
set -euo pipefail

rpm_file="$(ls /rpm/*.rpm | head -1)"
dnf -q install -y xorg-x11-server-Xvfb xwininfo "$rpm_file" >/dev/null
rpm -qi crossdrop | grep -E '^(Name|Version|License)'
[ -x /opt/crossdrop/bin/CrossDrop ] || { echo "::error::the launcher isn't at /opt/crossdrop/bin/CrossDrop"; exit 1; }
rpm -qR crossdrop | grep -q 'libfontconfig' || { echo "::error::the rpm doesn't declare its libraries"; rpm -qR crossdrop; exit 1; }

export HOME=/tmp/smoke-home; mkdir -p "$HOME"
export JAVA_TOOL_OPTIONS="-Dcrossdrop.debug=1 -Duser.home=$HOME"
Xvfb :99 -screen 0 1280x800x24 >/dev/null 2>&1 &
export DISPLAY=:99
sleep 2
/opt/crossdrop/bin/CrossDrop >/tmp/app.log 2>&1 &
app=$!
trap 'kill "$app" 2>/dev/null || true' EXIT
for _ in $(seq 1 60); do
  if ! kill -0 "$app" 2>/dev/null; then echo "::error::the app exited early"; cat /tmp/app.log; exit 1; fi
  if grep -q 'listening on :' /tmp/app.log && xwininfo -root -tree | grep -q '"CrossDrop"'; then
    echo "fedora: window is up, $(grep -o 'listening on :[0-9]*' /tmp/app.log | head -1)"
    exit 0
  fi
  sleep 1
done
echo "::error::no CrossDrop window and listener after 60 s"; cat /tmp/app.log; exit 1
