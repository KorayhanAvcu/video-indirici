// Video İndirici — arka plan: ağ trafiğinden videoları yakalar, app.py'ye gönderir.
// Chrome/Edge'de service worker, Firefox'ta arka plan betiği olarak çalışır.

if (typeof importScripts === "function") importScripts("detect.js"); // Chrome; Firefox manifest'ten yükler

const APP = "http://127.0.0.1:5000";
const GUARD = { "X-Video-Indirici": "1" };
const MAX_ITEMS = 40;
const MAX_SUBS = 20;

// ------------------------------------------------------------ sekme durumu
// Service worker birkaç saniye boşta kalınca kapanabilir; durum storage.session'da da tutulur.
const state = new Map(); // tabId -> { items: [] }
const reqHeaders = new Map(); // requestId -> { Referer, Origin, User-Agent }
// URL -> başlıklar: sayfanın içinden (fetch/XHR koklayarak) yakalanan akışlar için gerçek Referer
const urlHeaders = new Map();
const MAX_URL_HEADERS = 300;
const saveTimers = new Map();

async function getTab(tabId) {
  if (state.has(tabId)) return state.get(tabId);
  let saved = null;
  try {
    saved = (await chrome.storage.session.get(`t${tabId}`))[`t${tabId}`];
  } catch {}
  if (!state.has(tabId)) state.set(tabId, saved || { items: [], subs: [] });
  const t = state.get(tabId);
  if (!t.subs) t.subs = []; // eski sürümden kalan kayıt
  return t;
}

function persist(tabId) {
  clearTimeout(saveTimers.get(tabId));
  saveTimers.set(tabId, setTimeout(() => {
    const t = state.get(tabId);
    if (t) chrome.storage.session.set({ [`t${tabId}`]: t }).catch(() => {});
  }, 300));
}

function resetTab(tabId) {
  state.set(tabId, { items: [], subs: [] });
  chrome.storage.session.remove(`t${tabId}`).catch(() => {});
  updateBadge(tabId);
}

async function updateBadge(tabId) {
  const t = state.get(tabId);
  const n = t ? t.items.filter(i => !i.variantOf).length : 0;
  try {
    await chrome.action.setBadgeBackgroundColor({ tabId, color: "#4c8dff" });
    await chrome.action.setBadgeText({ tabId, text: n ? String(n) : "" });
  } catch {} // sekme kapanmış olabilir
}

// ------------------------------------------------------------ ağ dinleme
const FILTER = { urls: ["<all_urls>"], types: ["media", "xmlhttprequest", "other", "object"] };

// IDM gibi: tarayıcının isteğe eklediği başlıkların hepsi kopyalanır (X-Requested-With, Sec-Fetch-*,
// özel token başlıkları...). Bazı oynatıcılar bunlardan biri eksikse "security error" döner.
// Çerez ayrı gider (cookies.txt); bağlantıya özel başlıklar yt-dlp'ye bırakılır.
const SKIP_REQ_HEADERS = new Set(["cookie", "host", "connection", "content-length", "accept-encoding", "range",
  "if-range", "if-none-match", "if-modified-since", "upgrade", "transfer-encoding", "te", "keep-alive",
  "expect", "proxy-connection", "proxy-authorization"]);
const canonHeader = n => n.toLowerCase().replace(/(^|-)([a-z])/g, (_, d, c) => d + c.toUpperCase());

function onSend(d) {
  if (d.tabId < 0) return;
  const h = {};
  for (const { name, value } of d.requestHeaders || []) {
    const n = name.toLowerCase();
    if (value == null || n.startsWith(":") || SKIP_REQ_HEADERS.has(n)) continue;
    h[canonHeader(n)] = value;
  }
  reqHeaders.set(d.requestId, h);
  urlHeaders.delete(d.url);
  urlHeaders.set(d.url, h);
  if (urlHeaders.size > MAX_URL_HEADERS) urlHeaders.delete(urlHeaders.keys().next().value);
}
try {
  // Chrome'da Referer/Origin'i görmek için "extraHeaders" gerekir; Firefox bunu tanımaz
  chrome.webRequest.onSendHeaders.addListener(onSend, FILTER, ["requestHeaders", "extraHeaders"]);
} catch {
  chrome.webRequest.onSendHeaders.addListener(onSend, FILTER, ["requestHeaders"]);
}

const forget = d => reqHeaders.delete(d.requestId);
chrome.webRequest.onCompleted.addListener(forget, FILTER);
chrome.webRequest.onErrorOccurred.addListener(forget, FILTER);

function header(list, name) {
  const h = (list || []).find(x => x.name.toLowerCase() === name);
  return h ? h.value : "";
}

function totalSize(d) {
  // 206 yanıtında gerçek boyut Content-Range'in sonunda: "bytes 0-1023/987654321"
  const range = header(d.responseHeaders, "content-range");
  const m = range.match(/\/(\d+)\s*$/);
  if (m) return Number(m[1]);
  const len = header(d.responseHeaders, "content-length");
  return len ? Number(len) : undefined;
}

chrome.webRequest.onHeadersReceived.addListener(d => {
  if (d.tabId < 0 || d.statusCode >= 400) return;
  const ct = header(d.responseHeaders, "content-type");
  const sub = classifySub(d.url, ct);
  if (sub) {
    addSub(d.tabId, { url: d.url, ...sub, frameId: d.frameId,
      headers: reqHeaders.get(d.requestId) || {}, frameUrl: d.documentUrl || d.initiator || "" });
    return;
  }
  const size = totalSize(d);
  const hit = classify(d.url, ct, size);
  if (!hit) return;
  addItem(d.tabId, {
    url: d.url,
    kind: hit.kind,
    size: hit.kind === "hls" || hit.kind === "dash" ? undefined : size, // liste dosyasının boyutu anlamsız
    frameId: d.frameId,
    headers: reqHeaders.get(d.requestId) || {},
    frameUrl: d.documentUrl || d.initiator || "",
    time: Date.now(),
  });
}, FILTER, ["responseHeaders"]);

async function addSub(tabId, sub) {
  const t = await getTab(tabId);
  if (t.subs.some(s => s.url === sub.url)) return;
  t.subs.push(sub);
  if (t.subs.length > MAX_SUBS) t.subs.shift();
  persist(tabId);
}

async function addItem(tabId, item) {
  const t = await getTab(tabId);
  if (t.items.some(i => i.url === item.url)) return;
  if (t.items.some(i => i.info && i.info.master &&
      [...(i.info.variants || []), ...(i.info.renditions || [])].includes(item.url))) {
    item.variantOf = true; // bilinen bir master listenin kalite varyantı → gizle
  }
  t.items.push(item);
  if (t.items.length > MAX_ITEMS) t.items.shift();
  persist(tabId);
  updateBadge(tabId);
  if (item.kind === "hls") inspectHls(tabId, item);
}

async function fetchPlaylist(tabId, item) {
  // Önce videonun oynadığı çerçevenin içinden oku: sayfanın çerezleri ve Referer'ı otomatik gider
  try {
    const res = await chrome.tabs.sendMessage(tabId, { type: "fetchText", url: item.url }, { frameId: item.frameId || 0 });
    if (res && res.ok) return res.text;
  } catch {}
  const r = await fetch(item.url, { credentials: "include" });
  return r.ok ? r.text() : "";
}

async function inspectHls(tabId, item) {
  try {
    const info = parseM3U8(await fetchPlaylist(tabId, item), item.url);
    if (!info) return;
    const t = await getTab(tabId);
    const it = t.items.find(i => i.url === item.url);
    if (!it) return;
    it.info = info;
    if (info.master) {
      const hidden = [...info.variants, ...(info.renditions || [])];
      for (const v of t.items) if (hidden.includes(v.url)) v.variantOf = true;
    }
    persist(tabId);
    updateBadge(tabId);
  } catch {} // Referer isteyen sunucular reddedebilir; sorun değil, yt-dlp yine indirir
}

// ------------------------------------------------------------ sekme yaşam döngüsü
chrome.tabs.onRemoved.addListener(tabId => {
  state.delete(tabId);
  chrome.storage.session.remove(`t${tabId}`).catch(() => {});
});
chrome.tabs.onUpdated.addListener((tabId, info) => {
  if (info.status === "loading" && info.url) resetTab(tabId); // yeni sayfaya geçildi
});

// ------------------------------------------------------------ app.py'ye gönderme
async function cookiesFor(url) {
  try {
    const list = await chrome.cookies.getAll({ url });
    return list.map(c => ({
      domain: c.domain, path: c.path, secure: c.secure, name: c.name, value: c.value,
      expirationDate: c.expirationDate || 0,
    }));
  } catch {
    return [];
  }
}

async function settings() {
  const s = await chrome.storage.local.get({ quality: "", audio: false });
  return { quality: s.quality, audio: !!s.audio };
}

async function post(body) {
  let r;
  try {
    r = await fetch(`${APP}/api/ext/add`, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...GUARD },
      body: JSON.stringify(body),
    });
  } catch {
    return { ok: false, error: "app.py çalışmıyor. Komut satırında: python app.py" };
  }
  const data = await r.json().catch(() => ({}));
  return r.ok ? { ok: true, id: data.id } : { ok: false, error: data.error || `HTTP ${r.status}` };
}

async function sendMedia(item, tab) {
  const headers = { ...(item.headers || {}) };
  headers.Referer = headers.Referer || item.frameUrl || tab.url;
  headers["User-Agent"] = headers["User-Agent"] || navigator.userAgent;
  const cookies = await cookiesFor(item.url);
  const subtitles = await subsFor(tab, cookies);
  return post({ url: item.url, page_url: tab.url, title: tab.title || "", headers, cookies, subtitles,
    ...(await settings()) });
}

/** Sekmede yakalanan altyazılar; her biri kendi Referer'ı ile, çerezleri video ile aynı dosyadan. */
async function subsFor(tab, videoCookies) {
  const t = await getTab(tab.id);
  const out = [];
  for (const s of pickSubs(t.subs)) {
    const referer = (s.headers && s.headers.Referer) || s.frameUrl || tab.url;
    // Altyazı başka bir alan adındaysa onun çerezlerini de ekle
    for (const c of await cookiesFor(s.url)) {
      if (!videoCookies.some(v => v.domain === c.domain && v.name === c.name && v.path === c.path)) videoCookies.push(c);
    }
    out.push({ url: s.url, format: s.format, lang: s.lang || "", referer });
  }
  return out;
}

async function sendPage(tab) {
  const headers = { "User-Agent": navigator.userAgent };
  const cookies = await cookiesFor(tab.url);
  // Başlık boş: yt-dlp siteyi tanıyorsa gerçek video başlığını kendisi bulur
  return post({ url: tab.url, page_url: tab.url, title: "", headers, cookies, ...(await settings()) });
}

async function sendBest(tab, src, frameUrl) {
  const t = await getTab(tab.id);
  const best = pickBest(t.items);
  if (best) return sendMedia(best, tab);
  if (/^https?:/i.test(src || "")) {
    return sendMedia({ url: src, headers: urlHeaders.get(src) || { Referer: frameUrl || tab.url }, frameUrl }, tab);
  }
  return sendPage(tab); // blob: video ve yakalanan istek yok → sayfayı yt-dlp denesin
}

// ------------------------------------------------------------ mesajlar (popup + sayfa butonu)
chrome.runtime.onMessage.addListener((msg, sender, reply) => {
  (async () => {
    if (msg.type === "best") {
      return sendBest(sender.tab, msg.src, sender.url);
    }
    if (msg.type === "sniffed") {
      // content.js: sayfanın fetch/XHR yanıtı m3u8 ya da altyazı çıktı (uzantısı gizli olsa bile)
      if (!sender.tab || !/^https?:/i.test(msg.url || "")) return { ok: false };
      const base = { url: msg.url, frameId: sender.frameId, frameUrl: sender.url || "",
        headers: urlHeaders.get(msg.url) || { Referer: sender.url || "" }, time: Date.now() };
      if (msg.kind === "hls") await addItem(sender.tab.id, { ...base, kind: "hls", sniffed: true });
      else if (["vtt", "srt", "ass"].includes(msg.kind)) {
        await addSub(sender.tab.id, { ...base, format: msg.kind, lang: guessLang(msg.url) });
      }
      return { ok: true };
    }
    const tab = msg.tabId != null ? await chrome.tabs.get(msg.tabId) : null;
    if (msg.type === "list") {
      const t = await getTab(msg.tabId);
      const best = pickBest(t.items) || {};
      return { items: t.items.filter(i => !i.variantOf), best: best.url,
        subs: pickSubs(t.subs).map(s => ({ url: s.url, format: s.format, lang: s.lang })),
        streamSubs: (best.info && best.info.subtitles) || [] };
    }
    if (msg.type === "send") {
      const t = await getTab(msg.tabId);
      const item = t.items.find(i => i.url === msg.url);
      return item ? sendMedia(item, tab) : { ok: false, error: "Video listede yok, sayfayı yenile." };
    }
    if (msg.type === "page") return sendPage(tab);
    if (msg.type === "ping") {
      try {
        const r = await fetch(`${APP}/api/ping`);
        return { ok: r.ok };
      } catch {
        return { ok: false };
      }
    }
    return { ok: false, error: "Bilinmeyen istek" };
  })().then(reply, e => reply({ ok: false, error: String(e && e.message || e) }));
  return true; // yanıt async
});
