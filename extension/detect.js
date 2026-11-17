// Video İndirici — video isteği tanıma (saf fonksiyonlar; tarayıcıda ve Node testlerinde çalışır)

const MIN_FILE_BYTES = 1024 * 1024; // 1 MB altı video dosyaları: önizleme / reklam / parça

const EXT_KIND = { m3u8: "hls", mpd: "dash", mp4: "mp4", m4v: "mp4", mov: "mp4", webm: "webm", mkv: "video", flv: "video" };
const SEGMENT_EXT = new Set(["ts", "m4s", "m4a", "aac", "vtt", "srt", "key", "jpg", "png", "webp", "js", "css", "json", "html"]);
const SKIP_HOSTS = [/(^|\.)googlevideo\.com$/]; // YouTube parça parça (range) gönderir; sayfa linki yt-dlp ile indirilmeli

// Altyazılar: uzantı veya Content-Type ile tanınır
const SUB_EXT = { vtt: "vtt", webvtt: "vtt", srt: "srt", ass: "ass", ssa: "ass" };
const SUB_CT = {
  "text/vtt": "vtt", "application/x-subrip": "srt", "text/srt": "srt",
  "text/x-ssa": "ass", "text/x-ass": "ass", "application/x-ass": "ass",
};
// HLS altyazı parçaları (sub_3.vtt, segment-12.vtt...): tek başına altyazı değil, yt-dlp listeden birleştirir
const SUB_SEGMENT = /(?:^|[\/_.-])(?:seg|segment|chunk|frag|fragment|part|sequence|filesequence)?[_-]?\d{1,6}\.(?:vtt|webvtt)$/i;
const LANGS = {
  tr: "tr", tur: "tr", turkish: "tr", turkce: "tr", "türkçe": "tr", trsub: "tr",
  en: "en", eng: "en", english: "en", ingilizce: "en", "i̇ngilizce": "en",
};

function extOf(url) {
  try {
    const path = new URL(url).pathname.toLowerCase();
    const m = path.match(/\.([a-z0-9]{2,5})$/);
    return m ? m[1] : "";
  } catch {
    return "";
  }
}

/**
 * Bir ağ isteğinin indirilebilir video olup olmadığına karar verir.
 * @returns {{kind: string}|null}
 */
function classify(url, contentType = "", contentLength) {
  if (!/^https?:\/\//i.test(url || "")) return null;
  let host = "";
  try { host = new URL(url).hostname; } catch { return null; }
  if (SKIP_HOSTS.some(r => r.test(host))) return null;

  const ct = String(contentType || "").toLowerCase().split(";")[0].trim();
  const ext = extOf(url);
  const size = Number(contentLength);
  const tooSmall = Number.isFinite(size) && size > 0 && size < MIN_FILE_BYTES;

  // Akış listeleri (boyut önemsiz)
  if (ext === "m3u8" || ct.includes("mpegurl")) return { kind: "hls" };
  if (ext === "mpd" || ct === "application/dash+xml") return { kind: "dash" };

  if (SEGMENT_EXT.has(ext) || ct === "video/mp2t" || ct === "video/iso.segment") return null;

  let kind = EXT_KIND[ext] || null;
  if (!kind && ct.startsWith("video/")) kind = ct === "video/webm" ? "webm" : "mp4";
  if (!kind) return null;
  if (tooSmall) return null;
  return { kind };
}

/**
 * m3u8 metnini ayrıştırır. Master liste ise kaliteleri ve varyant adreslerini,
 * medya listesi ise toplam süreyi döndürür. M3U8 değilse null.
 */
function parseM3U8(text, baseUrl) {
  if (!String(text || "").trimStart().startsWith("#EXTM3U")) return null;
  const lines = text.split(/\r?\n/).map(l => l.trim());
  const abs = u => { try { return new URL(u, baseUrl).href; } catch { return u; } };

  if (lines.some(l => l.startsWith("#EXT-X-STREAM-INF"))) {
    const heights = [];
    const variants = [];
    for (let i = 0; i < lines.length; i++) {
      if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue;
      const m = lines[i].match(/RESOLUTION=\d+x(\d+)/i);
      if (m) heights.push(Number(m[1]));
      const next = lines.slice(i + 1).find(l => l && !l.startsWith("#"));
      if (next) variants.push(abs(next));
    }
    // Ayrı ses / altyazı kanalları (EXT-X-MEDIA): bunlar da tek başına indirilecek video değil
    const renditions = [];
    const subtitles = [];
    for (const l of lines) {
      if (!l.startsWith("#EXT-X-MEDIA:")) continue;
      const a = m3u8Attrs(l);
      if (a.URI) renditions.push(abs(a.URI));
      if ((a.TYPE || "").toUpperCase() === "SUBTITLES") subtitles.push({ lang: a.LANGUAGE || "", name: a.NAME || "" });
    }
    return { master: true, heights: [...new Set(heights)].sort((a, b) => b - a), variants, renditions, subtitles };
  }

  let duration = 0;
  for (const l of lines) {
    const m = l.match(/^#EXTINF:([\d.]+)/);
    if (m) duration += Number(m[1]);
  }
  return { master: false, duration: Math.round(duration * 10) / 10 };
}

/** "#EXT-X-MEDIA:TYPE=SUBTITLES,LANGUAGE="tr",URI="a.m3u8"" satırını {TYPE, LANGUAGE, URI} yapar. */
function m3u8Attrs(line) {
  const out = {};
  const re = /([A-Z0-9-]+)=("([^"]*)"|[^,]*)/g;
  const body = line.slice(line.indexOf(":") + 1);
  let m;
  while ((m = re.exec(body))) out[m[1]] = m[3] !== undefined ? m[3] : m[2];
  return out;
}

/** Adresten altyazı dilini tahmin eder ("tr", "en" ya da ""). Dosya adına yakın kelimeler önceliklidir. */
function guessLang(url) {
  let text = "";
  try {
    const u = new URL(url);
    text = decodeURIComponent(u.pathname + " " + u.search).toLocaleLowerCase("tr");
  } catch {
    return "";
  }
  const words = text.split(/[^a-zçğıöşüi̇]+/).filter(Boolean).reverse();
  for (const w of words) if (LANGS[w]) return LANGS[w];
  return "";
}

/**
 * Bir ağ isteğinin altyazı dosyası olup olmadığına karar verir.
 * @returns {{format: "vtt"|"srt"|"ass", lang: string}|null}
 */
function classifySub(url, contentType = "") {
  if (!/^https?:\/\//i.test(url || "")) return null;
  const ct = String(contentType || "").toLowerCase().split(";")[0].trim();
  const format = SUB_EXT[extOf(url)] || SUB_CT[ct];
  if (!format) return null;
  return { format, lang: guessLang(url) };
}

/** Metnin başı m3u8 / altyazı gibi görünüyor mu? (uzantısı gizlenmiş akışları yakalamak için) */
function sniffText(head) {
  const t = String(head || "").replace(/^\uFEFF/, "").trimStart();
  if (t.startsWith("#EXTM3U")) return "hls";
  if (t.startsWith("WEBVTT")) return "vtt";
  if (t.startsWith("[Script Info]")) return "ass";
  if (/^\d+\s*\r?\n\d\d:\d\d:\d\d,\d{3} --> /.test(t)) return "srt";
  return "";
}

/**
 * Yakalanan altyazılardan gönderilecekleri seçer: HLS parça dosyalarını ve aynı klasörden
 * gelen çok sayıda dosyayı (yine parça) eler, aynı adresi bir kez tutar.
 */
function pickSubs(subs, max = 6) {
  const seen = new Set();
  const groups = new Map();
  for (const s of subs || []) {
    if (!s || seen.has(s.url)) continue;
    seen.add(s.url);
    let dir = "", file = "";
    try {
      const u = new URL(s.url);
      dir = u.origin + u.pathname.replace(/[^/]*$/, "");
      file = u.pathname.split("/").pop() || "";
    } catch {
      continue;
    }
    if (SUB_SEGMENT.test("/" + file)) continue;
    const key = `${dir}|${s.format}`;
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(s);
  }
  const out = [];
  for (const g of groups.values()) if (g.length <= 3) out.push(...g);
  return out.slice(0, max);
}

/** Bir sekmede yakalanan adaylardan indirilecek en iyisini seçer. */
function pickBest(items) {
  const visible = (items || []).filter(i => !i.variantOf);
  if (!visible.length) return null;
  const score = i => {
    if (i.kind === "hls") return i.info && i.info.master ? 4e12 : 3e12;
    if (i.kind === "dash") return 2e12;
    return i.size || 1; // dosyalarda büyük olan
  };
  return visible.reduce((best, i) => (score(i) > score(best) ? i : best));
}

if (typeof module !== "undefined" && module.exports) {
  module.exports = { classify, classifySub, guessLang, sniffText, pickSubs, parseM3U8, pickBest, MIN_FILE_BYTES };
}
