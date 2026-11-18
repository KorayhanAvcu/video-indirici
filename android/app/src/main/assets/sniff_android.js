// Video İndirici (Android) — IDM tarzı içerik koklama. Her çerçevede, sayfa betiklerinden önce çalışır.
// Sayfanın fetch/XHR yanıtlarının başına bakar: "#EXTM3U" → akış listesi, WEBVTT/SRT/ASS → altyazı.
// Uzantısı .txt/.jpg diye gizlenmiş listeler ve tek kullanımlık altyazılar böyle yakalanır.
(() => {
  if (window.__viAndroidSniff) return;
  Object.defineProperty(window, "__viAndroidSniff", { value: true });

  const seen = new Set();
  const dec = new TextDecoder();
  const MAX = 2 * 1024 * 1024;

  function kindOf(head) {
    const t = String(head || "").replace(/^\uFEFF/, "").trimStart();
    if (t.startsWith("#EXTM3U")) return "hls";
    if (t.startsWith("WEBVTT")) return "vtt";
    if (t.startsWith("[Script Info]")) return "ass";
    if (/^\d+\s*\r?\n\d\d:\d\d:\d\d,\d{3} --> /.test(t)) return "srt";
    return "";
  }

  function toB64(bytes) {
    let bin = "";
    for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    return btoa(bin);
  }

  function report(url, kind, content) {
    if (!url || !kind || seen.has(url) || !/^https?:/i.test(url)) return;
    const bridge = window.VIAndroid; // uygulamanın enjekte ettiği nesne
    if (!bridge || typeof bridge.postMessage !== "function") return;
    seen.add(url);
    try { bridge.postMessage(JSON.stringify({ kind, url, ...(content || {}) })); } catch (e) {}
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
        if (size > MAX) return null;
      }
    }
    const all = new Uint8Array(size);
    let off = 0;
    for (const p of parts) { all.set(p, off); off += p.length; }
    return all;
  }

  const origFetch = window.fetch;
  if (typeof origFetch === "function") {
    const wrapped = function (...args) {
      const p = origFetch.apply(this, args);
      p.then(res => {
        try {
          if (!res || !res.body || res.type === "opaque") return;
          const ct = (res.headers.get("content-type") || "").toLowerCase();
          if (ct.startsWith("video/") || ct.startsWith("audio/")) return;
          const reader = res.clone().body.getReader();
          reader.read().then(async ({ value, done }) => {
            const kind = value ? kindOf(dec.decode(value.subarray(0, 64))) : "";
            if (!kind || seen.has(res.url)) { reader.cancel().catch(() => {}); return; }
            const all = await readAll(reader, value, done);
            reader.cancel().catch(() => {});
            if (!all) return report(res.url, kind);
            report(res.url, kind, kind === "hls" ? { text: dec.decode(all) } : { b64: toB64(all) });
          }).catch(() => {});
        } catch (e) {}
      }).catch(() => {});
      return p;
    };
    wrapped.toString = () => origFetch.toString();
    window.fetch = wrapped;
  }

  const XHR = window.XMLHttpRequest && window.XMLHttpRequest.prototype;
  if (XHR) {
    const open = XHR.open, send = XHR.send;
    XHR.open = function (method, url, ...rest) {
      try { this.__viUrl = new URL(url, location.href).href; } catch (e) { this.__viUrl = ""; }
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
            report(url, kind, kind && text.length <= MAX ? { text } : null);
          } else if (rt === "arraybuffer" && this.response) {
            const bytes = new Uint8Array(this.response);
            const kind = kindOf(dec.decode(bytes.subarray(0, 64)));
            if (!kind) return;
            report(url, kind, bytes.length <= MAX ? (kind === "hls" ? { text: dec.decode(bytes) } : { b64: toB64(bytes) }) : null);
          }
        } catch (e) {}
      });
      return send.apply(this, args);
    };
  }
})();
