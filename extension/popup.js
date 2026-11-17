const $ = id => document.getElementById(id);
const APP = "http://127.0.0.1:5000";
let tabId = null;

const send = msg => chrome.runtime.sendMessage(msg);

function say(text, cls = "") {
  $("msg").textContent = text;
  $("msg").className = cls;
}

function label(item) {
  try {
    const u = new URL(item.url);
    const file = decodeURIComponent(u.pathname.split("/").filter(Boolean).pop() || u.hostname);
    return `${u.hostname} · ${file}`;
  } catch {
    return item.url;
  }
}

function meta(item) {
  const parts = [];
  if (item.info && item.info.master && item.info.heights.length) parts.push(item.info.heights.map(h => `${h}p`).join(", "));
  if (item.info && item.info.duration) {
    const s = Math.round(item.info.duration);
    parts.push(`${Math.floor(s / 60)} dk ${s % 60} sn`);
  }
  if (item.size) parts.push(`${(item.size / 1048576).toFixed(1)} MB`);
  return parts.join(" · ") || (item.kind === "hls" ? "akış (parçalı)" : "");
}

function result(res) {
  if (res && res.ok) say("✓ İndirme başladı. İlerlemeyi “İndirmeler”de görebilirsin.", "ok");
  else say(`✗ ${(res && res.error) || "Hata"}`, "err");
}

function renderSubs(subs, streamSubs) {
  const parts = [];
  for (const s of streamSubs || []) parts.push(`${(s.name || s.lang || "?")} (akış içinde)`);
  for (const s of subs || []) parts.push(`${(s.lang || "dil ?").toUpperCase()} · ${s.format.toUpperCase()}`);
  const el = $("subs");
  el.textContent = "";
  if (!parts.length) return;
  const b = document.createElement("b");
  b.textContent = "Altyazı bulundu: ";
  el.append(b, parts.join(", "), " — videoyla birlikte indirilir.");
}

async function render() {
  const { items, best, subs, streamSubs } = await send({ type: "list", tabId });
  renderSubs(subs, streamSubs);
  const ul = $("list");
  ul.innerHTML = "";
  if (!items.length) {
    ul.innerHTML = `<div class="empty">Bu sayfada henüz video yakalanmadı.<br>Videoyu birkaç saniye oynatıp tekrar aç.</div>`;
    return;
  }
  // önerilen en üstte
  items.sort((a, b) => (b.url === best) - (a.url === best));
  for (const item of items) {
    const li = document.createElement("li");
    if (item.url === best) li.className = "best";
    li.innerHTML = `<span class="kind"></span><div class="info"><div class="name"></div><div class="meta"></div></div>`;
    li.querySelector(".kind").textContent = item.kind.toUpperCase();
    li.querySelector(".name").textContent = label(item);
    li.querySelector(".name").title = item.url;
    li.querySelector(".meta").textContent = (item.url === best ? "★ önerilen · " : "") + meta(item);
    const b = document.createElement("button");
    b.textContent = "İndir";
    if (item.url === best) b.className = "primary";
    b.onclick = async () => {
      b.disabled = true;
      result(await send({ type: "send", tabId, url: item.url }));
      b.disabled = false;
    };
    li.appendChild(b);
    ul.appendChild(li);
  }
}

async function init() {
  const forced = new URLSearchParams(location.search).get("tab"); // test/hata ayıklama için
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  tabId = forced ? Number(forced) : tab.id;

  const s = await chrome.storage.local.get({ quality: "", audio: false });
  $("quality").value = s.quality;
  $("audio").checked = s.audio;
  $("quality").onchange = () => chrome.storage.local.set({ quality: $("quality").value });
  $("audio").onchange = () => chrome.storage.local.set({ audio: $("audio").checked });

  $("page").onclick = async () => result(await send({ type: "page", tabId }));
  $("open").onclick = () => chrome.tabs.create({ url: APP });

  const ping = await send({ type: "ping" });
  $("dot").className = `dot ${ping.ok ? "on" : "off"}`;
  $("status").textContent = ping.ok ? "app.py bağlı" : "app.py kapalı";
  if (!ping.ok) say("app.py çalışmıyor. Komut satırında: python app.py", "err");

  await render();
}

init();
