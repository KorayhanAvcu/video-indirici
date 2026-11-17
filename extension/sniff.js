// Video İndirici — IDM tarzı içerik koklama (sayfanın kendi dünyasında çalışır).
// Bazı siteler m3u8 listesini ve altyazıyı .txt / .jpg / uzantısız adreslerle yükler; adrese bakarak
// tanınmazlar. Burada sayfanın fetch/XHR yanıtlarının ilk birkaç baytına bakılır:
// "#EXTM3U" → akış listesi, "WEBVTT" / SRT / ASS → altyazı. Yanıtın kendisine dokunulmaz.
(() => {
  if (window.__videoIndiriciSniff) return;
  Object.defineProperty(window, "__videoIndiriciSniff", { value: true });

  const TAG = "__video_indirici_sniff__";
  const seen = new Set();
  const dec = new TextDecoder();
  const MAX_SUB = 2 * 1024 * 1024; // altyazı içeriği en fazla 2 MB

  // Ham baytları base64 yap: Windows-1254 Türkçe SRT'ler bozulmadan app.py'ye ulaşsın
  function toB64(bytes) {
    let bin = "";
    for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    return btoa(bin);
  }

  function kindOf(head) {
    const t = String(head || "").replace(/^\uFEFF/, "").trimStart();
    if (t.startsWith("#EXTM3U")) return "hls";
    if (t.startsWith("WEBVTT")) return "vtt";
    if (t.startsWith("[Script Info]")) return "ass";
    if (/^\d+\s*\r?\n\d\d:\d\d:\d\d,\d{3} --> /.test(t)) return "srt";
    return "";
  }

  // content: altyazıysa tarayıcının aldığı içeriğin kendisi ({b64} ham bayt ya da {text}).
  // Böylece süreli / tek kullanımlık / korumalı altyazı linkleri tekrar indirilmek zorunda kalmaz.
  function report(url, kind, content) {
    if (!url || !kind || seen.has(url) || !/^https?:/i.test(url)) return;
    seen.add(url);
    // Aynı çerçevedeki relay.js alır ve eklentiye iletir
    window.postMessage({ [TAG]: true, kind, url, ...(content || {}) }, "*");
  }

  async function readAll(reader, first, done) {
    const parts = [first];
    let size = first.length;
    while (!done) {
      const r = await reader.read();
      done = r.done;
      if (r.value) {
        parts.push(r.value);
        size += r.value.length;
        if (size > MAX_SUB) return null;
      }
    }
    const all = new Uint8Array(size);
    let off = 0;
    for (const p of parts) { all.set(p, off); off += p.length; }
    return all;
  }

  // ---- fetch
  const origFetch = window.fetch;
  if (typeof origFetch === "function") {
    const wrapped = function (...args) {
      const p = origFetch.apply(this, args);
      p.then(res => {
        try {
          if (!res || !res.body || res.type === "opaque") return;
          const ct = (res.headers.get("content-type") || "").toLowerCase();
          if (ct.startsWith("video/") || ct.startsWith("audio/")) return; // gerçek medya, liste değil
          const reader = res.clone().body.getReader();
          reader.read().then(async ({ value, done }) => {
            const kind = value ? kindOf(dec.decode(value.subarray(0, 64))) : "";
            if (!kind || kind === "hls" || seen.has(res.url)) {
              reader.cancel().catch(() => {});
              return report(res.url, kind);
            }
            const all = await readAll(reader, value, done); // altyazı: tamamını oku
            reader.cancel().catch(() => {});
            report(res.url, kind, all ? { b64: toB64(all) } : null);
          }).catch(() => {});
        } catch {}
      }).catch(() => {});
      return p;
    };
    wrapped.toString = () => origFetch.toString();
    window.fetch = wrapped;
  }

  // ---- XMLHttpRequest (hls.js, video.js vb. bunu kullanır)
  const XHR = window.XMLHttpRequest && window.XMLHttpRequest.prototype;
  if (XHR) {
    const open = XHR.open, send = XHR.send;
    XHR.open = function (method, url, ...rest) {
      try { this.__viUrl = new URL(url, location.href).href; } catch { this.__viUrl = ""; }
      return open.call(this, method, url, ...rest);
    };
    XHR.send = function (...args) {
      this.addEventListener("load", () => {
        try {
          const url = this.responseURL || this.__viUrl;
          const rt = this.responseType;
          if (rt === "" || rt === "text") {
            const text = String(this.responseText || "");
            const kind = kindOf(text.slice(0, 64));
            report(url, kind, kind && kind !== "hls" && text.length <= MAX_SUB ? { text } : null);
          } else if (rt === "arraybuffer" && this.response) {
            const bytes = new Uint8Array(this.response);
            const kind = kindOf(dec.decode(bytes.subarray(0, 64)));
            report(url, kind, kind && kind !== "hls" && bytes.length <= MAX_SUB ? { b64: toB64(bytes) } : null);
          }
        } catch {}
      });
      return send.apply(this, args);
    };
  }
})();
