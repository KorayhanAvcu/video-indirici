const test = require("node:test");
const assert = require("node:assert");
const { classify, classifySub, guessLang, sniffText, pickSubs, parseM3U8, pickBest } = require("../extension/detect.js");

test("uzantıdan tanır", () => {
  assert.equal(classify("https://x.com/a/master.m3u8?t=1").kind, "hls");
  assert.equal(classify("https://x.com/a/manifest.mpd").kind, "dash");
  assert.equal(classify("https://x.com/v/film.mp4").kind, "mp4");
  assert.equal(classify("https://x.com/v/film.webm").kind, "webm");
});

test("parçaları ve alakasızları eler", () => {
  assert.equal(classify("https://x.com/seg-12.ts"), null);
  assert.equal(classify("https://x.com/chunk.m4s"), null);
  assert.equal(classify("https://x.com/sub.vtt"), null);
  assert.equal(classify("https://x.com/app.js"), null);
  assert.equal(classify("blob:https://x.com/1234"), null);
  assert.equal(classify("https://rr1.googlevideo.com/videoplayback?itag=22", "video/mp4", 5e7), null);
});

test("content-type ile tanır", () => {
  assert.equal(classify("https://x.com/play?id=5", "application/vnd.apple.mpegurl").kind, "hls");
  assert.equal(classify("https://x.com/play?id=5", "application/x-mpegURL; charset=utf-8").kind, "hls");
  assert.equal(classify("https://x.com/play?id=5", "application/dash+xml").kind, "dash");
  assert.equal(classify("https://x.com/get?id=5", "video/mp4", 80e6).kind, "mp4");
  assert.equal(classify("https://x.com/get?id=5", "video/mp2t", 80e6), null);
});

test("küçük video dosyaları (önizleme/reklam parçası) elenir", () => {
  assert.equal(classify("https://x.com/get?id=5", "video/mp4", 300e3), null);
  assert.equal(classify("https://x.com/preview.mp4", "video/mp4", 300e3), null);
  // boyut bilinmiyorsa tutulur
  assert.equal(classify("https://x.com/get?id=5", "video/mp4").kind, "mp4");
});

test("m3u8 master ayrıştırma", () => {
  const txt = `#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
360/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
1080/index.m3u8`;
  const r = parseM3U8(txt, "https://cdn.x.com/v/master.m3u8");
  assert.equal(r.master, true);
  assert.deepEqual(r.heights, [1080, 360]);
  assert.deepEqual(r.variants, ["https://cdn.x.com/v/360/index.m3u8", "https://cdn.x.com/v/1080/index.m3u8"]);
});

test("m3u8 medya listesi süresi", () => {
  const r = parseM3U8("#EXTM3U\n#EXTINF:10.0,\na.ts\n#EXTINF:5.5,\nb.ts\n#EXT-X-ENDLIST", "https://x/a.m3u8");
  assert.equal(r.master, false);
  assert.equal(r.duration, 15.5);
  assert.equal(parseM3U8("<html>", "https://x/a.m3u8"), null);
});

test("en iyi adayı seçer: master hls > dash > büyük dosya; varyantlar gizlenir", () => {
  const items = [
    { url: "https://c/360/index.m3u8", kind: "hls", info: { master: false }, variantOf: "m" },
    { url: "https://c/small.mp4", kind: "mp4", size: 2e6 },
    { url: "https://c/master.m3u8", kind: "hls", info: { master: true } },
    { url: "https://c/big.mp4", kind: "mp4", size: 9e8 },
  ];
  assert.equal(pickBest(items).url, "https://c/master.m3u8");
  assert.equal(pickBest(items.filter(i => i.kind === "mp4")).url, "https://c/big.mp4");
  assert.equal(pickBest([]), null);
});

test("altyazıları uzantı ve content-type ile tanır, video sanmaz", () => {
  assert.deepEqual(classifySub("https://x.com/subs/turkce.vtt"), { format: "vtt", lang: "tr" });
  assert.deepEqual(classifySub("https://x.com/a/eng.srt?t=1"), { format: "srt", lang: "en" });
  assert.deepEqual(classifySub("https://x.com/a/b.ass"), { format: "ass", lang: "" });
  assert.deepEqual(classifySub("https://x.com/get?id=1", "text/vtt; charset=utf-8"), { format: "vtt", lang: "" });
  assert.equal(classifySub("https://x.com/a.mp4", "video/mp4"), null);
  assert.equal(classifySub("blob:https://x.com/1"), null);
  assert.equal(classify("https://x.com/sub.vtt"), null); // video listesine girmez
});

test("dil tahmini dosya adını tercih eder", () => {
  assert.equal(guessLang("https://cdn.x/tr/video/altyazi_eng.vtt"), "en");
  assert.equal(guessLang("https://cdn.x/Türkçe.srt"), "tr");
  assert.equal(guessLang("https://cdn.x/sub?lang=tur"), "tr");
  assert.equal(guessLang("https://cdn.x/a.vtt"), "");
});

test("içerik koklama: gizlenmiş m3u8 ve altyazılar", () => {
  assert.equal(sniffText("\uFEFF#EXTM3U\n#EXT-X-VERSION:3"), "hls");
  assert.equal(sniffText("  WEBVTT\n\n00:00.000 --> 00:01.000"), "vtt");
  assert.equal(sniffText("1\r\n00:00:01,000 --> 00:00:02,000\r\nMerhaba"), "srt");
  assert.equal(sniffText("[Script Info]\nTitle: x"), "ass");
  assert.equal(sniffText("<!doctype html>"), "");
  assert.equal(sniffText(""), "");
});

test("altyazı seçimi: HLS parçaları ve kalabalık klasörler elenir, tekrarlar tekleşir", () => {
  const subs = [
    { url: "https://c/s/sub_0.vtt", format: "vtt" },
    { url: "https://c/s/segment-12.vtt", format: "vtt" },
    { url: "https://c/s/tr.vtt", format: "vtt" },
    { url: "https://c/s/en.vtt", format: "vtt" },
    { url: "https://c/s/tr.vtt", format: "vtt" },
    { url: "https://c/x/a.vtt", format: "vtt" }, { url: "https://c/x/b.vtt", format: "vtt" },
    { url: "https://c/x/c.vtt", format: "vtt" }, { url: "https://c/x/d.vtt", format: "vtt" },
  ];
  assert.deepEqual(pickSubs(subs).map(s => s.url), ["https://c/s/tr.vtt", "https://c/s/en.vtt"]);
  assert.deepEqual(pickSubs([]), []);
});

test("master m3u8: altyazı/ses kanalları okunur", () => {
  const txt = `#EXTM3U
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Türkçe",LANGUAGE="tr",URI="subs/tr.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Japonca",LANGUAGE="ja",URI="audio/ja.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=1280x720,SUBTITLES="subs",AUDIO="aud"
720.m3u8`;
  const r = parseM3U8(txt, "https://c/v/master.m3u8");
  assert.deepEqual(r.renditions, ["https://c/v/subs/tr.m3u8", "https://c/v/audio/ja.m3u8"]);
  assert.deepEqual(r.subtitles, [{ lang: "tr", name: "Türkçe" }]);
});
