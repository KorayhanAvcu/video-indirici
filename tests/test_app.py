import importlib.util
import os
import sys
import tempfile
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent


@pytest.fixture()
def mod(monkeypatch):
    monkeypatch.setenv("VIDEO_DIR", tempfile.mkdtemp())
    spec = importlib.util.spec_from_file_location("app_under_test", ROOT / "app.py")
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    m.jobs.clear()
    m.order.clear()
    return m


@pytest.fixture()
def client(mod):
    return mod.app.test_client()


H = {"X-Video-Indirici": "1"}


def test_ping(client):
    r = client.get("/api/ping")
    assert r.status_code == 200
    assert r.get_json()["app"] == "video-indirici"


def test_post_without_guard_header_is_rejected(client, mod):
    r = client.post("/api/jobs", data='{"links":"https://a.com/v.mp4"}', content_type="text/plain")
    assert r.status_code == 403
    r = client.post("/api/ext/add", json={"url": "https://a.com/v.mp4"})
    assert r.status_code == 403
    r = client.post("/api/clear")
    assert r.status_code == 403
    assert mod.jobs == {}


def test_existing_ui_add_still_works_with_header(client, mod):
    r = client.post("/api/jobs", json={"links": "https://a.com/1\nhttps://a.com/2"}, headers=H)
    assert r.get_json()["added"] == 2
    assert len(mod.jobs) == 2


def test_ext_add_creates_job_with_headers_and_title(client, mod):
    body = {
        "url": "https://cdn.example.com/hls/master.m3u8?token=x",
        "page_url": "https://example.com/izle/1",
        "title": 'Bölüm 1: "Test" / Final?',
        "headers": {"Referer": "https://example.com/izle/1", "User-Agent": "UA",
                    "Cookie": "a=1", "X-Requested-With": "XMLHttpRequest", "Host": "evil",
                    "Range": "bytes=0-", "Bad Name": "x", "X-Inject": "a\r\nEvil: 1"},
        "cookies": [
            {"domain": ".example.com", "path": "/", "secure": True, "name": "sess", "value": "abc",
             "expirationDate": 1999999999.5},
            {"domain": "cdn.example.com", "path": "/hls", "secure": False, "name": "k", "value": "v"},
            {"domain": "bad\tdomain", "name": "x", "value": "y"},
        ],
        "quality": "720",
        "audio": False,
    }
    r = client.post("/api/ext/add", json=body, headers=H)
    assert r.status_code == 200
    jid = r.get_json()["id"]
    job = mod.jobs[jid]
    assert job["url"] == body["url"]
    assert job["title"] == body["title"]
    # Tarayıcının başlıkları aynen geçer; Cookie (cookies.txt ile gider), bağlantı başlıkları ve bozuklar elenir
    assert job["headers"] == {"Referer": "https://example.com/izle/1", "User-Agent": "UA",
                              "X-Requested-With": "XMLHttpRequest"}
    assert len(job["cookies"]) == 2
    assert job["options"]["quality"] == 720

    opts = mod.build_opts(job)
    assert opts["http_headers"]["Referer"] == "https://example.com/izle/1"
    name = opts["outtmpl"]
    for bad in '<>:"/\\|?*':
        assert bad not in name
    assert name.startswith("Bölüm 1")
    assert name.endswith(".%(ext)s")


def test_ext_add_rejects_bad_url(client):
    r = client.post("/api/ext/add", json={"url": "blob:https://x.com/abc"}, headers=H)
    assert r.status_code == 400


def test_title_percent_is_escaped(mod):
    assert "%%" in mod.safe_name("100% gerçek")
    assert mod.safe_name("   ") == ""
    assert len(mod.safe_name("a" * 500)) <= 120


def test_page_job_without_title_uses_default_template(client, mod):
    r = client.post("/api/jobs", json={"links": "https://a.com/1"}, headers=H)
    job = next(iter(mod.jobs.values()))
    assert mod.build_opts(job)["outtmpl"] == "%(title)s [%(id)s].%(ext)s"
    assert "http_headers" not in mod.build_opts(job)


def test_cookies_written_as_netscape_file(client, mod):
    r = client.post("/api/ext/add", headers=H, json={
        "url": "https://cdn.example.com/a.m3u8",
        "cookies": [
            {"domain": ".example.com", "path": "/", "secure": True, "name": "sess", "value": "abc",
             "expirationDate": 1999999999.5},
            {"domain": "cdn.example.com", "path": "/hls", "secure": False, "name": "k", "value": "v"},
        ]})
    job = mod.jobs[r.get_json()["id"]]
    opts = mod.build_opts(job)
    text = Path(opts["cookiefile"]).read_text(encoding="utf-8")
    assert text.startswith("# Netscape HTTP Cookie File")
    assert ".example.com\tTRUE\t/\tTRUE\t1999999999\tsess\tabc" in text
    assert "cdn.example.com\tFALSE\t/hls\tFALSE\t0\tk\tv" in text

    # yt-dlp dosyayı okuyabilmeli
    from yt_dlp.cookies import YoutubeDLCookieJar
    jar = YoutubeDLCookieJar(opts["cookiefile"])
    jar.load()
    assert {c.name for c in jar} == {"sess", "k"}


def test_ext_title_not_overwritten_by_progress_hook(client, mod):
    r = client.post("/api/ext/add", headers=H, json={"url": "https://a.com/v.mp4", "title": "Sayfa Başlığı"})
    job = mod.jobs[r.get_json()["id"]]
    hook = mod.build_opts(job)["progress_hooks"][0]
    hook({"status": "downloading", "downloaded_bytes": 1, "total_bytes": 10, "info_dict": {"title": "index"}})
    assert job["title"] == "Sayfa Başlığı"


def test_parallel_limit_is_30(client, mod):
    client.post("/api/settings", json={"parallel": 25}, headers=H)
    assert mod.settings["parallel"] == 25
    client.post("/api/settings", json={"parallel": 99}, headers=H)
    assert mod.settings["parallel"] == 30
