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

  function kindOf(head) {
    const t = String(head || "").replace(/^\uFEFF/, "").trimStart();
    if (t.startsWith("#EXTM3U")) return "hls";
    if (t.startsWith("WEBVTT")) return "vtt";
    if (t.startsWith("[Script Info]")) return "ass";
    if (/^\d+\s*\r?\n\d\d:\d\d:\d\d,\d{3} --> /.test(t)) return "srt";
    return "";
  }

  function report(url, head) {
    if (!url || seen.has(url) || !/^https?:/i.test(url)) return;
    const kind = kindOf(head);
    if (!kind) return;
    seen.add(url);
    // Aynı çerçevedeki relay.js alır ve eklentiye iletir
    window.postMessage({ [TAG]: true, kind, url }, "*");
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
          reader.read().then(({ value }) => {
            reader.cancel().catch(() => {});
            if (value) report(res.url, dec.decode(value.subarray(0, 64)));
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
          if (rt === "" || rt === "text") report(url, String(this.responseText || "").slice(0, 64));
          else if (rt === "arraybuffer" && this.response) {
            report(url, dec.decode(new Uint8Array(this.response, 0, Math.min(64, this.response.byteLength))));
          }
        } catch {}
      });
      return send.apply(this, args);
    };
  }
})();
