// Video İndirici — sniff.js'in (sayfa dünyası) bulduklarını eklentiye iletir.
// Sayfa açılır açılmaz çalışır; böylece video otomatik başlasa bile hiçbir liste kaçmaz.
(() => {
  const TAG = "__video_indirici_sniff__";
  window.addEventListener("message", e => {
    if (e.source !== window || !e.data || e.data[TAG] !== true) return;
    const { kind, url, text, b64 } = e.data;
    if (typeof url !== "string" || !/^https?:/i.test(url) || !["hls", "vtt", "srt", "ass"].includes(kind)) return;
    const msg = { type: "sniffed", kind, url };
    if (typeof b64 === "string" && b64.length < 3e6) msg.b64 = b64;
    else if (typeof text === "string" && text.length < 2.2e6) msg.text = text;
    try {
      chrome.runtime.sendMessage(msg).catch(() => {});
    } catch {} // eklenti yeniden yüklendiyse sayfa yenilenene kadar sessiz kal
  });
})();
