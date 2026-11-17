// Video İndirici — sniff.js'in (sayfa dünyası) bulduklarını eklentiye iletir.
// Sayfa açılır açılmaz çalışır; böylece video otomatik başlasa bile hiçbir liste kaçmaz.
(() => {
  const TAG = "__video_indirici_sniff__";
  window.addEventListener("message", e => {
    if (e.source !== window || !e.data || e.data[TAG] !== true) return;
    const { kind, url } = e.data;
    if (typeof url !== "string" || !/^https?:/i.test(url) || !["hls", "vtt", "srt", "ass"].includes(kind)) return;
    try {
      chrome.runtime.sendMessage({ type: "sniffed", kind, url }).catch(() => {});
    } catch {} // eklenti yeniden yüklendiyse sayfa yenilenene kadar sessiz kal
  });
})();
