"""Altyazı desteği: birim testleri + yerel HLS sunucusuyla uçtan uca indirme."""
import http.server
import importlib.util
import json
import shutil
import subprocess
import tempfile
import threading
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
H = {"X-Video-Indirici": "1"}


@pytest.fixture()
def mod(monkeypatch):
    monkeypatch.setenv("VIDEO_DIR", tempfile.mkdtemp())
    for k in ("no_proxy", "NO_PROXY"):
        monkeypatch.setenv(k, "127.0.0.1,localhost")
    spec = importlib.util.spec_from_file_location("app_sub_test", ROOT / "app.py")
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    m.jobs.clear()
    m.order.clear()
    return m


# ------------------------------------------------------------------ birim

def test_clean_subs_validates(mod):
    out = mod.clean_subs([
        {"url": "https://c/tr.vtt", "format": "VTT", "lang": "tr", "referer": "https://site/izle"},
        {"url": "https://c/tr.vtt", "format": "vtt"},                       # tekrar
        {"url": "javascript:alert(1)", "format": "vtt"},                    # kötü adres
        {"url": "https://c/a.txt", "format": "txt"},                        # bilinmeyen biçim
        {"url": "https://c/en.srt", "format": "srt", "lang": "e/n\n", "referer": "https://x\r\nEvil: 1"},
        "çöp",
    ])
    assert out == [
        {"url": "https://c/tr.vtt", "format": "vtt", "referer": "https://site/izle", "lang": "tr"},
        {"url": "https://c/en.srt", "format": "srt", "referer": "", "lang": "en"},
    ]
    assert mod.clean_subs(None) == []


def test_decode_sub_handles_turkish_encodings_and_rejects_html(mod):
    srt = "1\r\n00:00:01,000 --> 00:00:02,000\r\nŞimdi gidiyoruz, ağabey!\r\n"
    assert mod.decode_sub(srt.encode("cp1254")) == srt
    assert mod.decode_sub(("﻿" + srt).encode("utf-8")) == srt
    assert mod.decode_sub(b"WEBVTT\n\n00:00.000 --> 00:01.000\nx\n").startswith("WEBVTT")
    assert mod.decode_sub(b"<html><body>403 Forbidden</body></html>") is None


def test_sub_path_names_next_to_video(mod, tmp_path):
    v = tmp_path / "Bölüm 14 [abc].mp4"
    v.write_bytes(b"x")
    p1 = mod.sub_path(v, "tr", "vtt")
    assert p1.name == "Bölüm 14 [abc].tr.vtt"
    p1.write_text("x")
    assert mod.sub_path(v, "tr", "vtt").name == "Bölüm 14 [abc].tr.2.vtt"
    assert mod.sub_path(v, "", "srt").name == "Bölüm 14 [abc].und.srt"


def test_build_opts_stream_subs(mod):
    c = mod.app.test_client()
    jid = c.post("/api/ext/add", headers=H, json={"url": "https://a.com/v.m3u8"}).get_json()["id"]
    job = mod.jobs[jid]
    opts = mod.build_opts(job)
    assert opts["writesubtitles"] is True
    assert "tr.*" in opts["subtitleslangs"] and "en.*" in opts["subtitleslangs"]
    assert "writesubtitles" not in mod.build_opts(job, stream_subs=False)
    job["options"]["audio"] = True
    assert "writesubtitles" not in mod.build_opts(job)  # sadece ses: altyazı yok


def test_ext_add_stores_subtitles(mod):
    c = mod.app.test_client()
    r = c.post("/api/ext/add", headers=H, json={
        "url": "https://a.com/v.m3u8",
        "subtitles": [{"url": "https://a.com/tr.vtt", "format": "vtt", "lang": "tr", "referer": "https://a.com/izle"}],
    })
    job = mod.jobs[r.get_json()["id"]]
    assert job["subs"] == [{"url": "https://a.com/tr.vtt", "format": "vtt", "referer": "https://a.com/izle", "lang": "tr"}]


# ------------------------------------------------------------------ uçtan uca

needs_ffmpeg = pytest.mark.skipif(not (shutil.which("ffmpeg") and shutil.which("ffprobe")),
                                  reason="ffmpeg/ffprobe gerekli")

VTT_TR = "WEBVTT\n\n00:00:00.500 --> 00:00:02.000\nMerhaba Yugi, düello zamanı!\n"
SRT_EN_CP1254 = "1\r\n00:00:00,500 --> 00:00:02,000\r\nGet your game on!\r\n"


@pytest.fixture(scope="module")
def site():
    """Yerel test sitesi: HLS video (+ akış içi Türkçe altyazı kanalı) ve yalnızca doğru Referer'la
    verilen ayrı altyazı dosyaları. Gerçek sitelerdeki durumun küçük bir kopyası."""
    if not (shutil.which("ffmpeg") and shutil.which("ffprobe")):
        pytest.skip("ffmpeg gerekli")
    root = Path(tempfile.mkdtemp())
    (root / "video").mkdir()
    subprocess.run(["ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=duration=4:size=320x240:rate=10",
                    "-f", "lavfi", "-i", "sine=duration=4", "-c:v", "libx264", "-c:a", "aac", "-f", "hls",
                    "-hls_time", "2", "-hls_playlist_type", "vod", str(root / "video" / "index.m3u8")], check=True)
    (root / "subs").mkdir()
    (root / "subs" / "tr.vtt").write_text(VTT_TR, encoding="utf-8")
    (root / "subs" / "tr.m3u8").write_text(
        "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:4.0,\ntr.vtt\n#EXT-X-ENDLIST\n")
    (root / "master.m3u8").write_text(
        '#EXTM3U\n#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Türkçe",LANGUAGE="tr",URI="subs/tr.m3u8"\n'
        '#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=320x240,SUBTITLES="subs"\nvideo/index.m3u8\n')
    (root / "broken.m3u8").write_text(
        '#EXTM3U\n#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Türkçe",LANGUAGE="tr",URI="subs/yok.m3u8"\n'
        '#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=320x240,SUBTITLES="subs"\nvideo/index.m3u8\n')
    # Uzantısı gizlenmiş liste (.txt) — eklentinin koklayarak yakaladığı türden
    (root / "gizli.txt").write_text((root / "video" / "index.m3u8").read_text().replace(
        "index", "video/index"), encoding="utf-8")
    side = root / "side"
    side.mkdir()
    (side / "tr.vtt").write_text(VTT_TR, encoding="utf-8")
    (side / "en.srt").write_bytes(SRT_EN_CP1254.encode("cp1254"))

    class Handler(http.server.SimpleHTTPRequestHandler):
        def __init__(self, *a, **kw):
            super().__init__(*a, directory=str(root), **kw)

        def do_GET(self):
            # Ayrı altyazılar sadece sayfadan gelen isteğe verilir (gerçek sitelerdeki koruma)
            if self.path.startswith("/side/") and self.headers.get("Referer") != "https://site.test/izle/14":
                self.send_error(403)
                return
            super().do_GET()

        def log_message(self, *a):
            pass

    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{srv.server_address[1]}"
    srv.shutdown()


def ffprobe_subs(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "s", "-show_entries",
                          "stream=codec_name:stream_tags=language", "-of", "json", str(path)],
                         capture_output=True, text=True, check=True).stdout
    return [(s["codec_name"], s.get("tags", {}).get("language")) for s in json.loads(out)["streams"]]


def run(mod, body):
    c = mod.app.test_client()
    jid = c.post("/api/ext/add", headers=H, json=body).get_json()["id"]
    job = mod.jobs[jid]
    mod.run_job(job)
    job["files"] = mod.existing_files(job)  # arayüzün gösterdiği liste: sadece diskte kalanlar
    return job


@needs_ffmpeg
def test_e2e_captured_sidecar_subs_saved_and_embedded(mod, site):
    job = run(mod, {
        "url": f"{site}/video/index.m3u8", "title": "Yu-Gi-Oh GX 14",
        "headers": {"Referer": "https://site.test/izle/14"},
        "subtitles": [
            {"url": f"{site}/side/tr.vtt", "format": "vtt", "lang": "tr", "referer": "https://site.test/izle/14"},
            {"url": f"{site}/side/en.srt", "format": "srt", "lang": "en", "referer": "https://site.test/izle/14"},
        ],
    })
    assert job["status"] == "done", job["message"]
    names = sorted(Path(p).name for p in job["files"])
    video = next(Path(p) for p in job["files"] if p.endswith(".mp4"))
    assert any(n.endswith(".tr.vtt") for n in names) and any(n.endswith(".en.srt") for n in names)
    # Windows-1254 SRT, UTF-8 olarak kaydedilmiş olmalı
    srt = next(Path(p) for p in job["files"] if p.endswith(".en.srt"))
    assert "Get your game on!" in srt.read_text(encoding="utf-8")
    assert sorted(ffprobe_subs(video)) == [("mov_text", "eng"), ("mov_text", "tur")]
    assert "gömüldü" in job["message"]


@needs_ffmpeg
def test_e2e_sidecar_without_referer_is_skipped_but_video_ok(mod, site):
    job = run(mod, {
        "url": f"{site}/video/index.m3u8", "title": "Referersiz",
        "subtitles": [{"url": f"{site}/side/tr.vtt", "format": "vtt", "lang": "tr", "referer": ""}],
    })
    assert job["status"] == "done"
    assert any(p.endswith(".mp4") for p in job["files"])
    assert not any(p.endswith(".vtt") for p in job["files"])
    assert job["message"] == "Altyazı bulundu ama indirilemedi"


@needs_ffmpeg
def test_e2e_stream_subtitle_track_from_master(mod, site):
    job = run(mod, {"url": f"{site}/master.m3u8", "title": "Master"})
    assert job["status"] == "done", job["message"]
    video = next(Path(p) for p in job["files"] if p.endswith(".mp4"))
    assert any(p.endswith(".tr.vtt") for p in job["files"])
    assert ffprobe_subs(video) == [("mov_text", "tur")]


@needs_ffmpeg
def test_e2e_broken_stream_subtitle_does_not_lose_video(mod, site):
    job = run(mod, {"url": f"{site}/broken.m3u8", "title": "Bozuk altyazı"})
    assert job["status"] == "done", job["message"]
    assert any(p.endswith(".mp4") for p in job["files"])
    assert "altyazısız" in job["message"]


@needs_ffmpeg
def test_e2e_disguised_playlist(mod, site):
    job = run(mod, {"url": f"{site}/gizli.txt", "title": "Gizli liste"})
    assert job["status"] == "done", job["message"]
    assert any(p.endswith(".mp4") for p in job["files"])


# ------------------------------------------------------------------ "security error" koruması

@pytest.fixture(scope="module")
def guarded_site(site):
    """anizmplayer tarzı koruma: X-Requested-With yoksa 200 + 'security error' metni döner."""
    import urllib.parse
    base = site  # aynı HLS dosyalarını kullan
    root = Path(tempfile.mkdtemp())

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            if self.headers.get("X-Requested-With") != "XMLHttpRequest":
                body = b"security error"
                self.send_response(200)
                self.send_header("Content-Type", "text/html")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            # İzinliyse asıl siteden aktar
            import urllib.request
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            data = opener.open(base + self.path).read()
            self.send_response(200)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, *a):
            pass

    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{srv.server_address[1]}"
    srv.shutdown()


@needs_ffmpeg
def test_e2e_security_error_is_reported_not_saved(mod, guarded_site):
    job = run(mod, {"url": f"{guarded_site}/video/index.m3u8", "title": "Korumalı"})
    assert job["status"] == "error"
    assert "security error" in job["message"]
    assert job["files"] == []  # 14 baytlık çöp dosya bırakılmaz
    assert not [f for f in Path(mod.DOWNLOAD_DIR).iterdir() if f.is_file()]


@needs_ffmpeg
def test_e2e_browser_headers_copied_like_idm(mod, guarded_site):
    job = run(mod, {"url": f"{guarded_site}/video/index.m3u8", "title": "Korumalı",
                    "headers": {"Referer": "https://site.test/player", "X-Requested-With": "XMLHttpRequest",
                                "Sec-Fetch-Mode": "cors"}})
    assert job["status"] == "done", job["message"]
    assert any(p.endswith(".mp4") for p in job["files"])


def test_junk_reply_detection(mod, tmp_path):
    f = tmp_path / "a.unknown_video"
    f.write_bytes(b"security error")
    assert mod.junk_reply(f) == "security error"
    f.write_bytes(b"<html><body><h1>403 Forbidden</h1></body></html>")
    assert mod.junk_reply(f) == "403 Forbidden"
    f.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"\x00" * 100)  # gerçek MP4 başlangıcı
    assert mod.junk_reply(f) is None


# ------------------------------------------------------------------ tarayıcı taklidi sadece gerektiğinde

def test_impersonation_only_after_cloudflare_block(mod, monkeypatch):
    calls = []

    def fake(job, stream_subs, impersonate=False):
        calls.append(impersonate)
        if not impersonate:
            raise mod.DownloadError("[generic] Got HTTP Error 403 caused by Cloudflare anti-bot challenge")
        return {"title": "t"}, ""

    monkeypatch.setattr(mod, "IMPERSONATE_OK", True)
    monkeypatch.setattr(mod, "_download", fake)
    with mod.lock:
        jid = mod.new_job("https://a.com/v.mp4", mod.make_options({}))
    mod._run_job(mod.jobs[jid])
    assert calls == [False, True]
    assert mod.jobs[jid]["status"] == "done"


def test_normal_sites_never_use_impersonation(mod, monkeypatch):
    calls = []
    monkeypatch.setattr(mod, "IMPERSONATE_OK", True)
    monkeypatch.setattr(mod, "_download", lambda job, stream_subs, impersonate=False: calls.append(impersonate) or ({}, ""))
    with mod.lock:
        jid = mod.new_job("https://a.com/v.mp4", mod.make_options({}))
    mod._run_job(mod.jobs[jid])
    assert calls == [False]
    assert "extractor_args" not in mod.build_opts(mod.jobs[jid])
    assert mod.build_opts(mod.jobs[jid], impersonate=True)["extractor_args"] == {"generic": {"impersonate": [""]}}


def test_curl_timeout_after_impersonation_gives_clear_message(mod, monkeypatch):
    def fake(job, stream_subs, impersonate=False):
        if not impersonate:
            raise mod.DownloadError("Got HTTP Error 403 caused by Cloudflare anti-bot challenge")
        raise mod.DownloadError("Unable to download webpage: Failed to perform, curl: (28) Connection timed out")

    monkeypatch.setattr(mod, "IMPERSONATE_OK", True)
    monkeypatch.setattr(mod, "_download", fake)
    with mod.lock:
        jid = mod.new_job("https://a.com/v.mp4", mod.make_options({}))
    mod._run_job(mod.jobs[jid])
    assert mod.jobs[jid]["status"] == "error"
    assert "teshis.py" in mod.jobs[jid]["message"]


def test_subtitle_failure_falls_back(mod, monkeypatch):
    calls = []

    def fake(job, stream_subs, impersonate=False):
        calls.append(stream_subs)
        if stream_subs:
            raise mod.DownloadError("Unable to download video subtitles for 'tr': HTTP Error 404")
        return {}, ""

    monkeypatch.setattr(mod, "_download", fake)
    with mod.lock:
        jid = mod.new_job("https://a.com/v.mp4", mod.make_options({}))
    mod._run_job(mod.jobs[jid])
    assert calls == [True, False]
    assert "altyazısız" in mod.jobs[jid]["message"]
