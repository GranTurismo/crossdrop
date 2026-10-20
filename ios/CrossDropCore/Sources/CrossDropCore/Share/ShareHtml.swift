// Generated from core/src/main/resources/share.html — keep in sync with the Kotlin core.
// Embedded as a string constant so the app needs no resource bundle.
enum ShareHtml {
    static let page = #"""
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#030014">
<title>CrossDrop</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; margin: 0; }
  body {
    min-height: 100vh; padding: 32px 20px 48px;
    font: 15px/1.5 -apple-system, "Segoe UI", Roboto, sans-serif;
    color: #EEF2FF;
    background:
      radial-gradient(60% 40% at 15% 0%, rgba(157,107,255,.16), transparent),
      radial-gradient(55% 45% at 90% 15%, rgba(69,229,255,.11), transparent),
      radial-gradient(45% 30% at 85% 70%, rgba(255,79,216,.08), transparent),
      radial-gradient(70% 55% at 50% 100%, rgba(59,255,176,.10), transparent),
      #030014;
  }
  .wrap { max-width: 480px; margin: 0 auto; }
  .mark {
    font-size: 26px; font-weight: 800; letter-spacing: .5px;
    background: linear-gradient(90deg, #3BFFB0, #45E5FF, #9D6BFF, #FF4FD8);
    -webkit-background-clip: text; background-clip: text; color: transparent;
  }
  .from { color: #8B93BB; font-size: 13px; margin: 4px 0 24px; }
  .from b { color: #B8FFE9; font-weight: 600; }
  .card {
    background: linear-gradient(180deg, #1C1550, #100A2E);
    border: 1px solid rgba(139,255,220,.24);
    box-shadow: 0 0 24px rgba(59,255,176,.05), 0 8px 28px rgba(0,0,0,.35);
    border-radius: 18px; padding: 14px; margin-bottom: 10px;
    display: flex; align-items: center; gap: 12px;
  }
  .tile {
    width: 42px; height: 42px; flex: none; border-radius: 12px;
    display: grid; place-items: center;
    font-size: 9px; font-weight: 700; letter-spacing: .5px;
    background: linear-gradient(135deg, rgba(157,107,255,.28), rgba(69,229,255,.10));
    border: 1px solid rgba(157,107,255,.45); color: #B8FFE9;
  }
  .name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 500; }
  .size { color: #8B93BB; font-size: 12px; }
  a.dl {
    flex: none; padding: 8px 14px; border-radius: 12px; text-decoration: none;
    background: #3BFFB0; color: #030014; font-weight: 700; font-size: 13px;
    box-shadow: 0 0 14px rgba(59,255,176,.35);
  }
  a.dl:active { opacity: .8; }
  .all {
    display: block; text-align: center; margin: 18px 0 8px; padding: 15px;
    border-radius: 16px; text-decoration: none; font-weight: 700;
    background: linear-gradient(90deg, #3BFFB0, #45E5FF, #9D6BFF);
    color: #030014;
    box-shadow: 0 0 22px rgba(69,229,255,.28);
  }
  .all:active { opacity: .85; }
  .note { text-align: center; color: #8B93BB; font-size: 11px; margin-top: 6px; }
  .foot {
    margin-top: 34px; text-align: center; color: #8B93BB; font-size: 11px; line-height: 1.7;
  }
  .foot .lock { color: #3BFFB0; }
  .err { color: #FF5470; text-align: center; padding: 40px 0; }
</style>
</head>
<body>
<div class="wrap">
  <div class="mark">CrossDrop</div>
  <div class="from"><b id="dev">A device</b> is sharing files with you — direct over local Wi-Fi. Nothing touches the cloud.</div>
  <div id="list"><div class="note">loading…</div></div>
  <a class="all" id="all" href="all.zip" style="display:none">Save all as .zip</a>
  <div class="foot">
    <span class="lock">&#9672;</span> device-to-device &middot; no account &middot; no app needed<br>
    keep both devices on this screen until downloads finish
  </div>
</div>
<script>
function fmt(b) {
  if (b >= 1073741824) return (b / 1073741824).toFixed(2) + " GB";
  if (b >= 1048576) return (b / 1048576).toFixed(1) + " MB";
  if (b >= 1024) return Math.round(b / 1024) + " KB";
  return b + " B";
}
function ext(n) {
  var e = (n.split(".").pop() || "").slice(0, 4).toUpperCase();
  return e || "\u00B7\u00B7\u00B7";
}
function esc(s) {
  return s.replace(/[&<>"']/g, c => "&#" + c.charCodeAt(0) + ";");
}
fetch("manifest.json").then(r => r.json()).then(m => {
  document.getElementById("dev").textContent = m.device || "A device";
  var list = document.getElementById("list");
  list.innerHTML = "";
  m.files.forEach(f => {
    var card = document.createElement("div"); card.className = "card";
    card.innerHTML =
      '<div class="tile">' + esc(ext(f.name)) + '</div>' +
      '<div class="name">' + esc(f.name) + '<div class="size">' + fmt(f.size) + '</div></div>' +
      '<a class="dl" href="f/' + f.i + '">Save</a>';
    list.appendChild(card);
  });
  if (m.zip) document.getElementById("all").style.display = "block";
}).catch(() => {
  document.getElementById("list").innerHTML =
    '<div class="err">Could not load the file list.<br>Is the sender still sharing?</div>';
});
</script>
</body>
</html>
"""#
}
