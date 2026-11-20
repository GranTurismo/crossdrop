#!/usr/bin/env bash
# Installs the CrossDrop .deb and starts the installed app under a virtual display, as a user would.
#
#   tools/release/linux_smoke.sh path/to/crossdrop_1.7.0_amd64.deb
#
# It must: install (apt resolves the package's dependencies), open a window titled "CrossDrop", listen on its transfer
# port (the radar's own liveness probe is a bare connect-and-close), put its files where the Linux conventions say,
# and turn a second launch away while the first keeps running. Needs Xvfb and xwininfo (x11-utils); uses sudo unless root.
# Set SMOKE_SHOT=/path/shot.png to also save a screenshot of the window (needs ImageMagick's `import`).
set -euo pipefail

# apt takes anything not starting with / or ./ for a package name, so hand it an absolute path.
deb="$(realpath "${1:?usage: linux_smoke.sh <package.deb>}")"
[ -f "$deb" ] || { echo "::error::no such package: $deb"; exit 1; }
SUDO=""; [ "$(id -u)" = 0 ] || SUDO=sudo
work="$(mktemp -d)"

# Every desktop already has the freedesktop menu folders; a bare container does not, and the package's post-install
# (xdg-desktop-menu) refuses to run without them.
$SUDO mkdir -p /usr/share/applications /usr/share/desktop-directories /etc/xdg/menus
$SUDO apt-get install -y "$deb"
[ -x /opt/crossdrop/bin/CrossDrop ] || { echo "::error::the launcher isn't at /opt/crossdrop/bin/CrossDrop"; dpkg -L crossdrop | head -30; exit 1; }
# the entry ships in /opt/crossdrop/lib and the post-install registers it with the desktop's menu
[ -f /opt/crossdrop/lib/crossdrop-CrossDrop.desktop ] || { echo "::error::the package has no .desktop entry"; exit 1; }
# xdg-desktop-menu picks the first writable applications folder of XDG_DATA_DIRS (/usr/local/share before /usr/share)
entry=""
for d in /usr/local/share/applications /usr/share/applications; do
  [ -f "$d/crossdrop-CrossDrop.desktop" ] && { entry="$d/crossdrop-CrossDrop.desktop"; break; }
done
[ -n "$entry" ] || { echo "::error::the menu entry wasn't registered (xdg-desktop-menu)"; ls /usr/local/share/applications /usr/share/applications 2>&1 | head; exit 1; }
grep -q '^Exec=/opt/crossdrop/bin/CrossDrop' "$entry" || { echo "::error::the menu entry doesn't launch CrossDrop"; exit 1; }
echo "menu entry: $entry"

export HOME="$work/home"; mkdir -p "$HOME"
log="$work/app.log"
# Java takes user.home from the passwd entry, not $HOME, so say where the throw-away profile is.
export JAVA_TOOL_OPTIONS="-Dcrossdrop.debug=1 -Duser.home=$HOME"
Xvfb :99 -screen 0 1280x800x24 >/dev/null 2>&1 &
xvfb=$!
export DISPLAY=:99
sleep 2
/opt/crossdrop/bin/CrossDrop >"$log" 2>&1 &
app=$!
trap 'kill "$app" "$xvfb" 2>/dev/null || true' EXIT

ok=false
for _ in $(seq 1 60); do
  if ! kill -0 "$app" 2>/dev/null; then echo "::error::the app exited early"; cat "$log"; exit 1; fi
  if grep -q "listening on :" "$log" && xwininfo -root -tree | grep -q '"CrossDrop"'; then ok=true; break; fi
  sleep 1
done
$ok || { echo "::error::no CrossDrop window and listener after 60 s"; cat "$log"; xwininfo -root -tree || true; exit 1; }
port="$(grep -o 'listening on :[0-9]*' "$log" | head -1 | grep -o '[0-9]*$')"
echo "window is up; transfer port $port"
(exec 3<>"/dev/tcp/127.0.0.1/$port") || { echo "::error::nothing is listening on $port"; cat "$log"; exit 1; }

# The Linux conventions: state under XDG data home, received files in Downloads/CrossDrop.
[ -d "$HOME/.local/share/CrossDrop" ] || { echo "::error::no state folder at ~/.local/share/CrossDrop"; find "$HOME" -maxdepth 3; exit 1; }
[ -d "$HOME/Downloads/CrossDrop" ] || { echo "::error::no inbox at ~/Downloads/CrossDrop"; find "$HOME" -maxdepth 3; exit 1; }
echo "folders: ok"

if [ -n "${SMOKE_SHOT:-}" ] && command -v import >/dev/null; then sleep 3; import -window root "$SMOKE_SHOT" && echo "screenshot: $SMOKE_SHOT"; fi

# A second launch must hand over to the running copy and exit by itself (single instance).
timeout 30 /opt/crossdrop/bin/CrossDrop >"$log.2" 2>&1 || { echo "::error::a second launch did not exit"; cat "$log.2"; exit 1; }
kill -0 "$app" 2>/dev/null || { echo "::error::the first copy died when a second was started"; cat "$log"; exit 1; }
echo "single instance: ok"
grep -E '^W/|Exception' "$log" || true
echo "smoke test passed"
