// Generated from core/src/main/resources/vault.html — keep in sync (byte-identical).
enum VaultPage {
    static let html = #"""
<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>CrossDrop Vault</title>
<style>
:root{--bg:#030014;--panel:#141033;--edge:#2b2360;--txt:#eef2ff;--dim:#8b93bb;
--teal:#3bffb0;--cyan:#45e5ff;--violet:#9d6bff;--pink:#ff4fd8;--red:#ff5470}
*{box-sizing:border-box;margin:0}
body{background:radial-gradient(1200px 700px at 50% -10%,#0c0628,transparent),
radial-gradient(700px 500px at 90% 20%,rgba(157,107,255,.12),transparent),
radial-gradient(800px 600px at 10% 90%,rgba(59,255,176,.08),transparent),var(--bg);
color:var(--txt);font:15px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;
min-height:100vh}
.wrap{max-width:760px;margin:0 auto;padding:24px 16px 80px}
h1{font-size:20px;font-weight:800;letter-spacing:.4px;
background:linear-gradient(90deg,var(--teal),var(--cyan),var(--violet));
-webkit-background-clip:text;background-clip:text}
h1{color:transparent}
.sub{color:var(--dim);font-size:12px;margin-top:2px}
.badge{display:inline-block;font:600 10px/1 monospace;letter-spacing:1px;
border:1px solid var(--teal);color:var(--teal);border-radius:99px;
padding:4px 8px;vertical-align:middle;margin-left:8px;
box-shadow:0 0 10px rgba(59,255,176,.25)}
.badge.ro{border-color:var(--cyan);color:var(--cyan);box-shadow:0 0 10px rgba(69,229,255,.25)}
.crumbs{display:flex;flex-wrap:wrap;gap:4px;align-items:center;
margin:18px 0 10px;font-size:13px}
.crumbs a{color:var(--dim);text-decoration:none;padding:3px 6px;border-radius:6px}
.crumbs a:hover{background:var(--panel);color:var(--txt)}
.crumbs .sep{color:var(--edge)}
.card{background:var(--panel);border:1px solid var(--edge);border-radius:16px;
overflow:hidden;box-shadow:0 8px 28px rgba(0,0,0,.35)}
.row{display:flex;align-items:center;gap:12px;padding:11px 14px;
border-bottom:1px solid var(--edge);cursor:pointer;text-decoration:none;color:var(--txt)}
.row:last-child{border-bottom:0}
.row:hover{background:#1d1850}
.ic{flex:none;width:34px;height:34px;border-radius:9px;display:grid;
place-items:center;font-size:15px;background:#0a0620;border:1px solid var(--edge)}
.nm{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.meta{color:var(--dim);font-size:11px;flex:none;text-align:right}
.empty{padding:38px;text-align:center;color:var(--dim);font-size:13px}
.bar{display:flex;gap:10px;margin:14px 0}
button,.btn{font:700 13px/1 inherit;border-radius:12px;padding:11px 16px;
border:1px solid var(--teal);background:var(--teal);color:#030014;cursor:pointer;
box-shadow:0 0 14px rgba(59,255,176,.3)}
button.ghost{background:transparent;color:var(--teal);border-color:var(--edge);
box-shadow:none}
button:disabled{opacity:.4;cursor:default}
#prog{height:4px;border-radius:99px;background:var(--edge);overflow:hidden;
margin:8px 0;display:none}
#prog i{display:block;height:100%;width:0;
background:linear-gradient(90deg,var(--teal),var(--cyan));transition:width .15s}
#err{color:var(--red);font-size:12px;margin-top:8px;min-height:16px}
/* PIN modal */
#pinbox{position:fixed;inset:0;background:rgba(3,0,20,.88);display:none;
place-items:center;backdrop-filter:blur(4px)}
#pinbox.on{display:grid}
#pinbox .card{padding:26px 30px;text-align:center}
#pin{font:700 26px/1 monospace;letter-spacing:10px;text-align:center;width:190px;
background:#0a0620;border:1px solid var(--edge);border-radius:12px;color:var(--txt);
padding:12px 0 12px 10px;margin:14px 0;outline:none}
#pin:focus{border-color:var(--teal);box-shadow:0 0 14px rgba(59,255,176,.25)}
.toast{position:fixed;left:50%;bottom:26px;transform:translateX(-50%);
background:var(--panel);border:1px solid var(--teal);color:var(--txt);
box-shadow:0 0 18px rgba(59,255,176,.25);
padding:10px 18px;border-radius:99px;font-size:13px;display:none}
</style></head>
<body>
<div class="wrap">
  <h1 id="title">CrossDrop Vault<span id="mode" class="badge ro">…</span></h1>
  <div class="sub" id="host"></div>
  <div class="crumbs" id="crumbs"></div>
  <div class="bar">
    <button id="up" class="ghost">↑ Upload</button>
    <input id="files" type="file" multiple hidden>
    <button id="dl-sel" class="ghost" style="display:none">Download</button>
  </div>
  <div id="prog"><i></i></div>
  <div id="err"></div>
  <div class="card" id="list"><div class="empty">Loading…</div></div>
</div>
<div id="pinbox"><div class="card">
  <b>This drive needs a PIN</b>
  <div class="sub">the host screen shows a 4-digit code</div>
  <input id="pin" maxlength="4" inputmode="numeric" autocomplete="off">
  <br><button id="pinok">Unlock</button>
</div></div>
<div class="toast" id="toast"></div>
<script>
"use strict";
const $=s=>document.querySelector(s);
let cwd="/",pin=sessionStorage.getItem("vault-pin")||"";
const enc=p=>p.split("/").map(encodeURIComponent).join("/");
const hdrs=()=>pin?{"X-Vault-Pin":pin}:{};
const toast=t=>{const e=$("#toast");e.textContent=t;e.style.display="block";
  setTimeout(()=>e.style.display="none",2200)};
const fmt=n=>n<1024?n+" B":n<1048576?(n/1024).toFixed(1)+" KB":
  n<1073741824?(n/1048576).toFixed(1)+" MB":(n/1073741824).toFixed(2)+" GB";
const icon=e=>e.isDir?"📁":({jpg:"🖼",jpeg:"🖼",png:"🖼",gif:"🖼",webp:"🖼",heic:"🖼",
  mp4:"🎬",mkv:"🎬",mov:"🎬",webm:"🎬",avi:"🎬",mp3:"🎵",m4a:"🎵",flac:"🎵",ogg:"🎵",wav:"🎵",
  zip:"📦","7z":"📦",rar:"📦",tar:"📦",gz:"📦",pdf:"📕",apk:"🤖"}
  [(e.name.split(".").pop()||"").toLowerCase()]||"📄");
const media=m=>m&&(/^(video|audio|image)\//.test(m)||m==="application/pdf");

async function api(url,opt={}){
  const r=await fetch(url,{...opt,headers:{...hdrs(),...(opt.headers||{})}});
  if(r.status===401){$("#pinbox").classList.add("on");throw{code:401}}
  const j=await r.json().catch(()=>({}));
  if(!r.ok)throw{code:r.status,message:j.message||r.statusText};
  return j;
}
function crumbs(){
  const parts=cwd.split("/").filter(Boolean);
  let html=`<a href="#" data-p="/">⌂ root</a>`,acc="";
  parts.forEach(p=>{acc+="/"+p;
    html+=`<span class=sep>›</span><a href="#" data-p="${acc}">${esc(p)}</a>`});
  $("#crumbs").innerHTML=html;
  $("#crumbs").querySelectorAll("a").forEach(a=>a.onclick=ev=>{
    ev.preventDefault();load(a.dataset.p)});
}
const esc=s=>s.replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
async function load(path){
  cwd=path;crumbs();$("#err").textContent="";
  try{
    const j=await api("/api/fs?path="+encodeURIComponent(cwd));
    $("#mode").textContent=j.writable?"RW":"RO";
    $("#mode").className="badge"+(j.writable?"":" ro");
    $("#up").style.display=j.writable?"":"none";
    const list=$("#list");
    if(!j.entries.length){list.innerHTML=`<div class=empty>Empty folder</div>`;return}
    list.innerHTML=j.entries.map(e=>{
      const p=(cwd==="/"?"":cwd)+"/"+e.name;
      if(e.isDir)return`<a class=row href="#" data-p="${esc(p)}" data-d="1">
        <span class=ic>${icon(e)}</span><span class=nm>${esc(e.name)}</span>
        <span class=meta>${new Date(e.mtime*1000).toLocaleDateString()}</span></a>`;
      const raw="/raw"+enc(p)+(pin?"?pin="+encodeURIComponent(pin):"");
      return`<a class=row href="${raw}${media(e.mime)?'" target=_blank':'?dl=1"'}>
        <span class=ic>${icon(e)}</span><span class=nm>${esc(e.name)}</span>
        <span class=meta>${fmt(e.size)}<br>${new Date(e.mtime*1000).toLocaleDateString()}</span></a>`;
    }).join("");
    list.querySelectorAll("a[data-d]").forEach(a=>a.onclick=ev=>{
      ev.preventDefault();load(a.dataset.p)});
  }catch(e){if(e.code!==401)$("#err").textContent=e.message||"error"}
}
$("#up").onclick=()=>$("#files").click();
$("#files").onchange=async ev=>{
  const files=[...ev.target.files];if(!files.length)return;
  const bar=$("#prog");bar.style.display="block";const fill=bar.firstElementChild;
  for(const f of files){
    const fd=new FormData();fd.append("file",f,f.name);
    try{
      await new Promise((res,rej)=>{
        const x=new XMLHttpRequest();
        x.open("POST","/upload?path="+encodeURIComponent(cwd));
        if(pin)x.setRequestHeader("X-Vault-Pin",pin);
        x.upload.onprogress=e=>{if(e.lengthComputable)
          fill.style.width=(e.loaded/e.total*100)+"%"};
        x.onload=()=>x.status<300?res():rej(JSON.parse(x.responseText||"{}"));
        x.onerror=()=>rej({message:"network error"});
        x.send(fd)});
      toast("Uploaded "+f.name);
    }catch(e){$("#err").textContent=e.message||"upload failed"}
  }
  fill.style.width="0";bar.style.display="none";ev.target.value="";load(cwd);
};
$("#pinok").onclick=()=>{
  pin=$("#pin").value.trim();sessionStorage.setItem("vault-pin",pin);
  $("#pinbox").classList.remove("on");load(cwd)};
$("#pin").addEventListener("keydown",e=>{if(e.key==="Enter")$("#pinok").click()});
api("/api/info").then(j=>{
  $("#title").childNodes[0].textContent=j.name+" ";
  document.title=j.name+" — CrossDrop Vault";
  $("#host").textContent=location.host;
  if(j.pin&&!pin)$("#pinbox").classList.add("on");
}).catch(()=>{});
load("/");
</script>
</body></html>

"""#
}
