"""
Video İndirici - Web arayüzü (yerel)

Kurulum (bir kere):
    pip install -U yt-dlp flask
    (İsteğe bağlı ama önerilir) ffmpeg:  winget install ffmpeg

Çalıştırma:
    python app.py
    -> Tarayıcıda otomatik açılır: http://127.0.0.1:5000

Özellikler:
    - Birden fazla link (her satıra bir link)
    - Aynı anda birden fazla indirme (sayı ayarlanabilir)
    - Video (MP4) / Sadece ses (MP3), kalite seçimi
    - İlerleme çubuğu, hız, kalan süre
    - Tek tek iptal / tekrar dene / sil
    - Biten dosyayı tarayıcıdan indirme butonu
    - Tarayıcı eklentisi (extension/ klasörü) ile her siteden "⬇ İndir"
"""

import os
import re
import shutil
import subprocess
import threading
import time
import uuid
import webbrowser
from pathlib import Path

from flask import Flask, jsonify, request, send_file, abort, Response

try:
    from yt_dlp import YoutubeDL
    from yt_dlp.utils import DownloadError
except ImportError:
    raise SystemExit("yt-dlp kurulu değil:  pip install -U yt-dlp flask")


def _impersonate_available() -> bool:
    """Cloudflare korumalı siteler normal isteklere 403 döner. curl_cffi kuruluysa
    yt-dlp isteği gerçek bir tarayıcı gibi imzalayabilir (TLS parmak izi dahil).
    Kurulu değilse False döner ve indirme eskisi gibi çalışır.
        pip install -U "yt-dlp[default,curl-cffi]"
    """
    try:
        import curl_cffi  # noqa: F401
    except ImportError:
        return False
    return True


IMPERSONATE_OK = _impersonate_available()


def _impersonate_target():
    from yt_dlp.networking.impersonate import ImpersonateTarget
    return ImpersonateTarget()

HOST, PORT = "127.0.0.1", 5000
DOWNLOAD_DIR = Path(os.environ.get("VIDEO_DIR", Path.home() / "Downloads" / "Video Indirici"))
DOWNLOAD_DIR.mkdir(parents=True, exist_ok=True)
FFMPEG = shutil.which("ffmpeg")

app = Flask(__name__)
lock = threading.Lock()
jobs: dict[str, dict] = {}          # id -> iş bilgisi
order: list[str] = []               # listedeki sıra
settings = {"parallel": 3}
MAX_PARALLEL = 30  # aynı anda en fazla kaç indirme


ANSI = re.compile(r"\x1b\[[0-9;]*m|\[[0-9;]*m")


def clean(text) -> str:
    """yt-dlp'nin terminal renk kodlarını ([0;32m gibi) temizler."""
    return ANSI.sub("", str(text or "")).strip()


BAD_CHARS = re.compile(r'[<>:"/\\|?*\x00-\x1f]')
# Eklentiden gelen istek başlıkları (tarayıcının gönderdiklerinin kopyası) — çerezler ayrı: cookies.txt.
# Bağlantıya özel olanlar yt-dlp'ye bırakılır.
SKIP_HEADERS = {"cookie", "host", "connection", "content-length", "content-type", "accept-encoding", "range",
                "if-range", "if-none-match", "if-modified-since", "upgrade", "transfer-encoding", "te",
                "keep-alive", "expect", "proxy-connection", "proxy-authorization"}
HEADER_NAME = re.compile(r"^[A-Za-z0-9-]{1,64}$")


def clean_headers(raw) -> dict:
    out = {}
    if not isinstance(raw, dict):
        return out
    for k, v in list(raw.items())[:40]:
        k, v = str(k), str(v)
        if not HEADER_NAME.match(k) or k.lower() in SKIP_HEADERS or len(v) > 4096 or any(c in v for c in "\r\n\0"):
            continue
        out[k] = v
    return out


class JunkReply(Exception):
    """Sunucu video yerine kısa bir hata metni döndü."""


def junk_reply(path: Path) -> str | None:
    """İndirilen dosya aslında 'security error', 'Forbidden', bir HTML hata sayfası gibi kısa bir metinse
    o metni döndürür. Gerçek video dosyaları ikili veridir ve bu teste takılmaz."""
    try:
        if path.stat().st_size > 64 * 1024:
            return None
        data = path.read_bytes()
    except OSError:
        return None
    if b"\x00" in data[:4096]:
        return None
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        return None
    text = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", text)).strip()
    return text[:120] or "(boş yanıt)"

# ----------------------------------------------------------------- altyazı
SUB_FORMATS = ("vtt", "srt", "ass")
# Akışın (HLS/DASH, YouTube vb.) kendi altyazılarından indirilecek diller: Türkçe, İngilizce, dili belirsiz
SUB_LANGS = ["tr.*", "tur.*", "en.*", "eng.*", "und"]
ISO3 = {"tr": "tur", "en": "eng"}
VIDEO_EXTS = (".mp4", ".m4v", ".mov", ".mkv", ".webm")
MAX_SUB_BYTES = 5 * 1024 * 1024


def clean_subs(raw) -> list[dict]:
    """Eklentinin yakaladığı altyazı listesini doğrular."""
    out, seen = [], set()
    for s in (raw if isinstance(raw, list) else [])[:6]:
        if not isinstance(s, dict):
            continue
        url = str(s.get("url") or "").strip()
        fmt = str(s.get("format") or "").lower()
        if not url.startswith(("http://", "https://")) or fmt not in SUB_FORMATS or url in seen:
            continue
        ref = str(s.get("referer") or "").strip()
        if not ref.startswith(("http://", "https://")) or any(ch in ref for ch in "\r\n"):
            ref = ""
        seen.add(url)
        out.append({"url": url, "format": fmt, "referer": ref,
                    "lang": re.sub(r"[^a-z]", "", str(s.get("lang") or "").lower())[:8]})
    return out


def decode_sub(data: bytes) -> str | None:
    """Altyazı baytlarını metne çevirir; altyazıya benzemiyorsa (HTML hata sayfası vb.) None.
    Türkçe SRT'ler sıklıkla Windows-1254 kodlamalıdır, UTF-8 değilse ona düşülür."""
    for enc in ("utf-8-sig", "cp1254"):
        try:
            text = data.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    else:
        return None
    head = text.lstrip()[:2000]
    if head.startswith("WEBVTT") or head.startswith("[Script Info]") or "-->" in head:
        return text
    return None


def glob_escape(text: str) -> str:
    """Dosya adındaki [abc123] gibi kısımlar glob deseni sanılmasın."""
    return re.sub(r"([\[\]*?])", r"[\1]", text)


def sub_path(video: Path, lang: str, fmt: str) -> Path:
    """film.mp4 → film.tr.vtt (oynatıcılar yandaki altyazıyı otomatik açar); çakışırsa film.tr.2.vtt"""
    base = video.with_suffix("")
    tag = lang or "und"
    path = base.with_name(f"{base.name}.{tag}.{fmt}")
    n = 2
    while path.exists():
        path = base.with_name(f"{base.name}.{tag}.{n}.{fmt}")
        n += 1
    return path


def fetch_sub(ydl, sub: dict) -> str | None:
    """Altyazıyı videoyla aynı çerezler/User-Agent ile (gerekirse tarayıcı taklidiyle) indirir."""
    from yt_dlp.networking import Request
    headers = {"Referer": sub["referer"]} if sub.get("referer") else {}
    attempts = [{"impersonate": _impersonate_target()}] if IMPERSONATE_OK else []
    attempts.append({})
    for ext in attempts:
        try:
            with ydl.urlopen(Request(sub["url"], headers=headers, extensions=ext)) as r:
                data = r.read(MAX_SUB_BYTES + 1)
        except Exception:
            continue
        if len(data) <= MAX_SUB_BYTES:
            return decode_sub(data)
    return None


def embed_subs(video: Path, subs: list[tuple[Path, str]]) -> bool:
    """Altyazıları ffmpeg ile videonun içine gömer (MP4: mov_text, MKV: olduğu gibi). Başarısızsa
    video olduğu gibi kalır; altyazılar zaten yanında ayrı dosya olarak duruyor."""
    ext = video.suffix.lower()
    if not FFMPEG or not subs or ext not in (".mp4", ".m4v", ".mov", ".mkv"):
        return False
    tmp = video.with_name(video.stem + ".altyazili" + video.suffix)
    cmd = [FFMPEG, "-y", "-loglevel", "error", "-i", str(video)]
    for path, _ in subs:
        cmd += ["-i", str(path)]
    cmd += ["-map", "0"]
    for i in range(len(subs)):
        cmd += ["-map", str(i + 1)]
    cmd += ["-c", "copy", "-c:s", "mov_text" if ext != ".mkv" else "copy"]
    # Mevcut altyazı kanallarından sonra gelecek yeni kanalların dil etiketi
    existing = _count_sub_streams(video)
    for i, (_, lang) in enumerate(subs):
        cmd += [f"-metadata:s:s:{existing + i}", f"language={ISO3.get(lang, 'und')}"]
    cmd.append(str(tmp))
    try:
        subprocess.run(cmd, check=True, capture_output=True, timeout=600,
                       creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        os.replace(tmp, video)
        return True
    except Exception:
        tmp.unlink(missing_ok=True)
        return False


def _count_sub_streams(video: Path) -> int:
    probe = shutil.which("ffprobe") or (str(Path(FFMPEG).with_name("ffprobe" + Path(FFMPEG).suffix)) if FFMPEG else None)
    if not probe or not Path(probe).exists():
        return 0
    try:
        out = subprocess.run([probe, "-v", "error", "-select_streams", "s", "-show_entries", "stream=index",
                              "-of", "csv=p=0", str(video)], capture_output=True, text=True, timeout=60,
                             creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0)).stdout
        return len([l for l in out.splitlines() if l.strip()])
    except Exception:
        return 0


def save_captured_subs(ydl, job: dict, video: Path | None) -> str:
    """Eklentinin sayfada yakaladığı altyazıları indirip videonun (son konumunun) yanına kaydeder ve
    videoya gömer. Kullanıcıya gösterilecek kısa bir not döndürür."""
    subs = job.get("subs") or []
    if not subs or job["options"].get("audio") or not video or not video.exists():
        return ""
    saved = []
    for sub in subs:
        if job["cancel"]:
            break
        text = fetch_sub(ydl, sub)
        if text is None:
            continue
        path = sub_path(video, sub["lang"], sub["format"])
        path.write_text(text, encoding="utf-8")
        saved.append((path, sub["lang"]))
        with lock:
            job["files"].append(str(path))
    if not saved:
        return "Altyazı bulundu ama indirilemedi"
    langs = ", ".join(sorted({(l or "?").upper() for _, l in saved}))
    if embed_subs(video, saved):
        return f"Altyazı ({langs}) videoya gömüldü ve yanına da kaydedildi"
    return f"Altyazı ({langs}) videonun yanına kaydedildi"


def safe_name(title) -> str:
    """Sayfa başlığını güvenli dosya adına çevirir (yt-dlp şablonu için % kaçırılır)."""
    name = BAD_CHARS.sub(" ", str(title or ""))
    name = re.sub(r"\s+", " ", name).strip(" .")[:110].strip(" .")
    return name.replace("%", "%%")


def make_options(data: dict) -> dict:
    return {
        "audio": bool(data.get("audio")),
        "quality": int(data["quality"]) if str(data.get("quality") or "").isdigit() else None,
        "playlist": bool(data.get("playlist")),
    }


def clean_cookies(raw) -> list[dict]:
    """Eklentiden gelen çerez listesini doğrular (sekme/satır sonu içeren alanlar dosyayı bozar)."""
    out = []
    for c in (raw if isinstance(raw, list) else [])[:300]:
        if not isinstance(c, dict):
            continue
        vals = [str(c.get(k) or "") for k in ("domain", "path", "name", "value")]
        if not vals[0] or not vals[2] or any(ch in v for v in vals for ch in "\t\r\n"):
            continue
        try:
            exp = int(float(c.get("expirationDate") or 0))
        except (TypeError, ValueError):
            exp = 0
        out.append({"domain": vals[0], "path": vals[1] or "/", "name": vals[2], "value": vals[3],
                    "secure": bool(c.get("secure")), "expires": max(exp, 0)})
    return out


def write_cookiefile(path: Path, cookies: list[dict]) -> None:
    lines = ["# Netscape HTTP Cookie File"]
    for c in cookies:
        sub = "TRUE" if c["domain"].startswith(".") else "FALSE"
        lines.append("\t".join([c["domain"], sub, c["path"], "TRUE" if c["secure"] else "FALSE",
                                str(c["expires"]), c["name"], c["value"]]))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def new_job(url: str, options: dict, title: str = "", headers: dict | None = None,
            cookies: list[dict] | None = None, subs: list[dict] | None = None) -> str:
    """Yeni iş oluşturur (lock tutulurken çağrılmalı)."""
    jid = uuid.uuid4().hex[:10]
    jobs[jid] = {"id": jid, "url": url, "title": title, "status": "queued", "percent": 0.0,
                 "speed": "", "eta": "", "message": "", "files": [], "cancel": False,
                 "options": options, "headers": headers or {}, "cookies": cookies or [],
                 "subs": subs or [], "name": safe_name(title)}
    order.append(jid)
    return jid


class Cancelled(Exception):
    pass


# ----------------------------------------------------------------- indirme

def build_opts(job: dict, stream_subs: bool = True, impersonate: bool = False) -> dict:
    o = job["options"]
    h = f"[height<={o['quality']}]" if o.get("quality") else ""

    def hook(d):
        if job["cancel"]:
            raise Cancelled()
        if d["status"] == "downloading":
            total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
            done = d.get("downloaded_bytes") or 0
            with lock:
                job["status"] = "downloading"
                job["percent"] = round(done * 100 / total, 1) if total else job["percent"]
                job["speed"] = clean(d.get("_speed_str"))
                job["eta"] = clean(d.get("_eta_str"))
                info = d.get("info_dict") or {}
                if info.get("title") and not job.get("name"):  # eklentiden gelen başlığı koru
                    job["title"] = info["title"]
        elif d["status"] == "finished":
            with lock:
                job["status"] = "processing"
                job["speed"] = job["eta"] = ""

    def pp_hook(d):
        if d["status"] == "finished":
            path = (d.get("info_dict") or {}).get("filepath")
            if path:
                with lock:
                    if path not in job["files"]:
                        job["files"].append(path)

    # Eklentiden gelen işlerde sayfa başlığı dosya adı olur (doğrudan video linklerinde
    # yt-dlp'nin bulduğu başlık genelde "index" / "master" gibi anlamsız oluyor)
    base = f"{job['name']} [{job['id'][:6]}]" if job.get("name") else "%(title)s [%(id)s]"
    outtmpl = f"{base} (ses).%(ext)s" if o.get("audio") else f"{base}.%(ext)s"

    opts = {
        # Ses dosyalarına ayrı isim: aynı videonun MP4'ü ile çakışıp onu silmesin
        "outtmpl": outtmpl,
        # Her iş kendi geçici klasörünü kullanır: aynı video iki kez eklense bile .part dosyaları çakışmaz
        "paths": {"home": str(DOWNLOAD_DIR), "temp": str(DOWNLOAD_DIR / ".gecici" / job["id"])},
        "progress_hooks": [hook],
        "postprocessor_hooks": [pp_hook],
        "noplaylist": not o.get("playlist"),
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
        # Renk kodlarını hiç üretme (arayüzde [0;32m gibi görünmesin)
        "color": {"stdout": "no_color", "stderr": "no_color"},
        # SSL sertifika doğrulamasını kapat (CERTIFICATE_VERIFY_FAILED hatasını önler)
        "nocheckcertificate": True,
        # Hız: parçalı (HLS/DASH) videolarda aynı anda 8 parça indir
        "concurrent_fragment_downloads": 8,
        # Hız: tek parça videoları 10 MB'lık bloklar hâlinde iste (bazı sitelerin hız kısıtlamasını aşar)
        "http_chunk_size": 10 * 1024 * 1024,
    }
    if impersonate and IMPERSONATE_OK:
        # İsteği gerçek tarayıcı gibi imzala: Cloudflare 403 hatasını önler. Sadece Cloudflare engeli
        # çıkınca kullanılır; bazı ağlarda curl_cffi hiç bağlanamıyor (curl hata 28), normal yol çalışıyor.
        # Global "impersonate" seçeneği yerine extractor-arg kullanılıyor; yt-dlp'nin
        # kendi hata mesajının önerdiği yol bu ve indirme aşamasında sorun çıkarmıyor.
        opts["extractor_args"] = {"generic": {"impersonate": [""]}}
    if job.get("cookies"):
        # Sitenin oturum çerezleri (tarayıcıdan); iş bitince geçici klasörle birlikte silinir
        cookie_path = DOWNLOAD_DIR / ".gecici" / job["id"] / "cookies.txt"
        write_cookiefile(cookie_path, job["cookies"])
        opts["cookiefile"] = str(cookie_path)
    if job.get("headers"):
        # Referer / Cookie olmadan pek çok site videoyu 403 ile reddeder
        opts["http_headers"] = dict(job["headers"])
    if FFMPEG:
        opts["ffmpeg_location"] = FFMPEG

    if o.get("audio"):
        if FFMPEG:
            opts["format"] = "bestaudio/best"
            opts["postprocessors"] = [{"key": "FFmpegExtractAudio", "preferredcodec": "mp3", "preferredquality": "192"}]
        else:
            opts["format"] = "bestaudio[ext=m4a]/bestaudio/best"
    else:
        if FFMPEG:
            opts["format"] = f"bestvideo{h}+bestaudio/best{h}/best"
            opts["merge_output_format"] = "mp4"
        else:
            opts["format"] = f"best{h}[vcodec!=none][acodec!=none]/best{h}/best"
        if stream_subs:
            # Akışın kendi altyazıları (HLS altyazı kanalı, YouTube, Dailymotion vb.):
            # videonun yanına kaydet ve ffmpeg varsa videoya da göm
            opts["writesubtitles"] = True
            opts["subtitleslangs"] = list(SUB_LANGS)
            if FFMPEG:
                opts["postprocessors"] = [{"key": "FFmpegEmbedSubtitle", "already_have_subtitle": True}]
    return opts


def run_job(job: dict):
    try:
        _run_job(job)
    finally:
        shutil.rmtree(DOWNLOAD_DIR / ".gecici" / job["id"], ignore_errors=True)
        try:
            (DOWNLOAD_DIR / ".gecici").rmdir()   # boşsa sil
        except OSError:
            pass


def _download(job: dict, stream_subs: bool, impersonate: bool = False) -> tuple[dict | None, str]:
    note = ""
    with YoutubeDL(build_opts(job, stream_subs=stream_subs, impersonate=impersonate)) as ydl:
        info = ydl.extract_info(job["url"], download=True)
        # Son dosya yolunu bul (postprocessor hook'u kaçırırsa diye). requested_downloads, dosyalar
        # geçici klasörden asıl klasöre taşındıktan sonraki yolu verir.
        finals = []
        entries = (info.get("entries") or [info]) if info else []
        for e in entries:
            for rd in (e or {}).get("requested_downloads") or []:
                p = rd.get("filepath")
                if p:
                    finals.append(Path(p))
                    if p not in job["files"]:
                        with lock:
                            job["files"].append(p)
        for f in finals:
            reply = junk_reply(f) if f.exists() else None
            if reply is not None:
                f.unlink(missing_ok=True)
                raise JunkReply(reply)
        video = next((f for f in finals if f.suffix.lower() in VIDEO_EXTS and f.exists()), None)
        if video:
            # yt-dlp'nin akıştan indirip videonun yanına taşıdığı altyazılar (film.tr.vtt ...)
            for f in sorted(video.parent.glob(glob_escape(video.stem) + ".*")):
                if f.suffix.lower().lstrip(".") in SUB_FORMATS and str(f) not in job["files"]:
                    with lock:
                        job["files"].append(str(f))
        try:
            note = save_captured_subs(ydl, job, video)
        except Exception:
            note = "Altyazı kaydedilemedi"
    return info, note


def is_cloudflare_block(msg: str) -> bool:
    m = msg.lower()
    return "cloudflare" in m or "impersonat" in m


def _download_with_fallbacks(job: dict) -> tuple[dict | None, str]:
    """Önce normal bağlantıyla dener. Cloudflare engeli çıkarsa tarayıcı taklidiyle (curl_cffi),
    akışın altyazısı inmezse altyazısız tekrar dener."""
    impersonate, stream_subs, notes = False, True, []
    while True:
        try:
            info, note = _download(job, stream_subs=stream_subs, impersonate=impersonate)
            return info, " · ".join(n for n in [note, *notes] if n)
        except DownloadError as e:
            msg = str(e)
            if job["cancel"]:
                raise
            if not impersonate and IMPERSONATE_OK and is_cloudflare_block(msg):
                print(f"[{job['id'][:6]}] Cloudflare engeli: tarayıcı taklidiyle tekrar deneniyor", flush=True)
                impersonate = True
                continue
            if stream_subs and "subtitles" in msg.lower():
                stream_subs = False
                notes.append("Akışın altyazısı indirilemedi, video altyazısız indi")
                continue
            if impersonate and "curl: (28)" in msg:
                raise DownloadError(
                    "Site Cloudflare korumalı ve tarayıcı taklidi (curl_cffi) bu ağda sunucuya bağlanamadı "
                    "(curl hata 28). Klasördeki 'python teshis.py' komutunun çıktısını gönder.") from e
            raise


def _run_job(job: dict):
    print(f"[{job['id'][:6]}] {job['url'][:120]}"
          + (f"  | başlıklar: {', '.join(job['headers'])}" if job.get("headers") else "")
          + (f"  | çerez: {len(job['cookies'])}" if job.get("cookies") else ""), flush=True)
    try:
        info, note = _download_with_fallbacks(job)
        with lock:
            job["title"] = job["title"] or (info or {}).get("title", "")
            job["status"] = "done"
            job["percent"] = 100
            job["speed"] = job["eta"] = ""
            job["message"] = note
    except Cancelled:
        with lock:
            job["status"] = "cancelled"
    except JunkReply as e:
        with lock:
            job["status"] = "error"
            job["message"] = (f'Sunucu video yerine şunu döndü: "{e}". Link süreli/tek kullanımlık olabilir: '
                              "sayfayı yenile, videoyu oynat ve eklentiden hemen tekrar gönder.")
    except DownloadError as e:
        msg = clean(e).replace("ERROR: ", "").strip()[:260]
        if not IMPERSONATE_OK and ("403" in msg or "impersonat" in msg.lower()):
            msg += '  |  Kurulum gerekli: pip install -U "yt-dlp[default,curl-cffi]"'
        with lock:
            job["status"] = "cancelled" if job["cancel"] else "error"
            job["message"] = msg
    except Exception as e:  # beklenmeyen hata
        with lock:
            job["status"] = "error"
            job["message"] = clean(e)[:300]


def scheduler():
    """Bekleyen işleri, aynı anda en fazla 'parallel' tane olacak şekilde başlatır."""
    while True:
        with lock:
            active = sum(1 for j in jobs.values() if j["status"] in ("starting", "downloading", "processing"))
            free = settings["parallel"] - active
            to_start = []
            if free > 0:
                for jid in order:
                    j = jobs.get(jid)
                    if j and j["status"] == "queued":
                        j["status"] = "starting"
                        to_start.append(j)
                        if len(to_start) >= free:
                            break
        for j in to_start:
            threading.Thread(target=run_job, args=(j,), daemon=True).start()
        time.sleep(0.5)


# ----------------------------------------------------------------- API

def existing_files(j: dict) -> list[str]:
    return [p for p in j["files"] if Path(p).exists()]


def public(j: dict) -> dict:
    return {k: j[k] for k in ("id", "url", "title", "status", "percent", "speed", "eta", "message")} | {
        "files": [{"index": i, "name": Path(p).name} for i, p in enumerate(existing_files(j))]
    }


@app.before_request
def guard():
    """Değiştiren her API isteği özel başlık ister. Tarayıcılar bir web sayfasının bu başlıkla
    başka bir adrese (127.0.0.1) istek atmasına CORS yüzünden izin vermez; arayüz ve eklenti gönderebilir."""
    if request.method not in ("GET", "HEAD", "OPTIONS") and request.path.startswith("/api/"):
        if request.headers.get("X-Video-Indirici") != "1":
            return jsonify({"error": "Yetkisiz istek."}), 403


@app.get("/api/jobs")
def list_jobs():
    with lock:
        return jsonify({
            "jobs": [public(jobs[i]) for i in order if i in jobs],
            "parallel": settings["parallel"],
            "folder": str(DOWNLOAD_DIR),
            "ffmpeg": bool(FFMPEG),
        })


@app.post("/api/jobs")
def add_jobs():
    data = request.get_json(force=True)
    links = []
    for line in (data.get("links") or "").split():
        line = line.strip()
        if line.startswith(("http://", "https://")) and line not in links:
            links.append(line)
    if not links:
        return jsonify({"error": "Geçerli link bulunamadı (http/https ile başlamalı)."}), 400

    options = make_options(data)
    with lock:
        for url in links:
            new_job(url, options)
    return jsonify({"added": len(links)})


@app.get("/api/ping")
def ping():
    return jsonify(ok=True, app="video-indirici", ffmpeg=bool(FFMPEG))


@app.post("/api/ext/add")
def ext_add():
    """Tarayıcı eklentisinden gelen tek video."""
    data = request.get_json(force=True) or {}
    url = str(data.get("url") or "").strip()
    if not url.startswith(("http://", "https://")):
        return jsonify({"error": "Geçersiz video adresi."}), 400
    headers = clean_headers(data.get("headers"))
    title = str(data.get("title") or "").strip()[:300]
    with lock:
        jid = new_job(url, make_options(data), title=title, headers=headers,
                      cookies=clean_cookies(data.get("cookies")), subs=clean_subs(data.get("subtitles")))
    return jsonify({"added": 1, "id": jid})


@app.post("/api/settings")
def set_settings():
    data = request.get_json(force=True)
    with lock:
        settings["parallel"] = max(1, min(MAX_PARALLEL, int(data.get("parallel", 3))))
    return jsonify(settings)


@app.post("/api/jobs/<jid>/cancel")
def cancel_job(jid):
    with lock:
        j = jobs.get(jid) or abort(404)
        if j["status"] == "queued":
            j["status"] = "cancelled"
        j["cancel"] = True
    return jsonify(ok=True)


@app.post("/api/jobs/<jid>/retry")
def retry_job(jid):
    with lock:
        j = jobs.get(jid) or abort(404)
        if j["status"] in ("error", "cancelled"):
            j.update(status="queued", percent=0.0, speed="", eta="", message="", cancel=False)
    return jsonify(ok=True)


@app.delete("/api/jobs/<jid>")
def delete_job(jid):
    with lock:
        j = jobs.get(jid)
        if j:
            j["cancel"] = True
            jobs.pop(jid, None)
            if jid in order:
                order.remove(jid)
    return jsonify(ok=True)


@app.post("/api/clear")
def clear_done():
    with lock:
        for jid in [i for i in order if jobs[i]["status"] == "done"]:
            jobs.pop(jid, None)
            order.remove(jid)
    return jsonify(ok=True)


@app.post("/api/cancel-all")
def cancel_all():
    with lock:
        for j in jobs.values():
            if j["status"] == "queued":
                j["status"] = "cancelled"
            j["cancel"] = True
    return jsonify(ok=True)


@app.get("/api/jobs/<jid>/file/<int:idx>")
def get_file(jid, idx):
    with lock:
        j = jobs.get(jid) or abort(404)
        files = existing_files(j)
    if idx >= len(files):
        abort(404)
    p = Path(files[idx]).resolve()
    if DOWNLOAD_DIR.resolve() not in p.parents:
        abort(403)
    return send_file(p, as_attachment=True, download_name=p.name)


@app.post("/api/open-folder")
def open_folder():
    try:
        if os.name == "nt":
            os.startfile(DOWNLOAD_DIR)  # type: ignore[attr-defined]
        else:
            import subprocess
            subprocess.Popen(["xdg-open" if shutil.which("xdg-open") else "open", str(DOWNLOAD_DIR)])
    except Exception as e:
        return jsonify(error=str(e)), 500
    return jsonify(ok=True)


@app.get("/")
def index():
    return Response(PAGE, mimetype="text/html")


# ----------------------------------------------------------------- sayfa

PAGE = r"""<!doctype html>
<html lang="tr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Video İndirici</title>
<style>
  :root { --bg:#0f1115; --card:#181b22; --line:#2a2f3a; --text:#e8eaf0; --muted:#8b93a7;
          --blue:#4c8dff; --green:#3fb37f; --red:#e5596b; --amber:#e8a93b; }
  * { box-sizing:border-box; }
  body { margin:0; font:14px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif; background:var(--bg); color:var(--text); }
  .wrap { max-width:1100px; margin:0 auto; padding:24px 16px 60px; }
  h1 { font-size:22px; margin:0 0 4px; }
  .sub { color:var(--muted); margin-bottom:18px; font-size:13px; }
  .card { background:var(--card); border:1px solid var(--line); border-radius:12px; padding:16px; margin-bottom:16px; }
  textarea { width:100%; min-height:110px; resize:vertical; background:#0c0e12; color:var(--text);
             border:1px solid var(--line); border-radius:8px; padding:10px; font:13px ui-monospace,Consolas,monospace; }
  textarea:focus, select:focus, input:focus { outline:2px solid var(--blue); outline-offset:-1px; }
  .row { display:flex; flex-wrap:wrap; gap:14px; align-items:center; margin-top:12px; }
  label { color:var(--muted); font-size:13px; display:flex; gap:6px; align-items:center; }
  select, input[type=number] { background:#0c0e12; color:var(--text); border:1px solid var(--line);
             border-radius:6px; padding:6px 8px; font-size:13px; }
  input[type=number] { width:60px; }
  button { background:#262b36; color:var(--text); border:1px solid var(--line); border-radius:8px;
           padding:8px 14px; font-size:13px; cursor:pointer; }
  button:hover { border-color:#3c4454; background:#2c3240; }
  button.primary { background:var(--blue); border-color:var(--blue); color:#fff; font-weight:600; }
  button.primary:hover { filter:brightness(1.1); }
  button.small { padding:4px 9px; font-size:12px; }
  .spacer { flex:1; }
  .note { color:var(--amber); font-size:12px; margin-top:8px; }
  .stats { color:var(--muted); font-size:13px; }
  table { width:100%; border-collapse:collapse; }
  th { text-align:left; color:var(--muted); font-weight:500; font-size:12px; padding:8px; border-bottom:1px solid var(--line); }
  td { padding:10px 8px; border-bottom:1px solid var(--line); vertical-align:middle; }
  td.title { max-width:380px; }
  td.title .t { white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
  td.title .u { color:var(--muted); font-size:11px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
  td.title .err { color:var(--red); font-size:12px; margin-top:2px; white-space:normal; }
  td.title .note { color:var(--green); font-size:12px; margin-top:2px; white-space:normal; }
  .bar { position:relative; height:18px; width:160px; background:#0c0e12; border:1px solid var(--line); border-radius:9px; overflow:hidden; }
  .bar > i { position:absolute; inset:0 auto 0 0; background:var(--blue); transition:width .4s; }
  .bar > span { position:relative; display:block; text-align:center; font-size:11px; line-height:16px; }
  .st { font-size:12px; padding:2px 8px; border-radius:10px; background:#262b36; white-space:nowrap; }
  .st.done { background:rgba(63,179,127,.15); color:var(--green); }
  .st.error { background:rgba(229,89,107,.15); color:var(--red); }
  .st.downloading, .st.processing, .st.starting { background:rgba(76,141,255,.15); color:var(--blue); }
  .actions { display:flex; gap:6px; flex-wrap:wrap; justify-content:flex-end; }
  .empty { text-align:center; color:var(--muted); padding:30px; }
  a.dl { color:#fff; background:var(--green); border-radius:8px; padding:4px 9px; font-size:12px; text-decoration:none; }
</style>
</head>
<body>
<div class="wrap">
  <h1>🎬 Video İndirici</h1>
  <div class="sub">Linkleri yapıştır, ayarları seç, indir. Dosyalar: <b id="folder"></b></div>

  <div class="card">
    <textarea id="links" placeholder="Her satıra bir link yapıştır...&#10;https://...&#10;https://..."></textarea>
    <div class="row">
      <label>Format
        <select id="format"><option value="video">Video (MP4)</option><option value="audio">Sadece ses (MP3)</option></select>
      </label>
      <label>Kalite
        <select id="quality">
          <option value="">En iyi</option><option value="2160">2160p (4K)</option><option value="1440">1440p</option>
          <option value="1080">1080p</option><option value="720">720p</option><option value="480">480p</option><option value="360">360p</option>
        </select>
      </label>
      <label>Aynı anda <input type="number" id="parallel" min="1" max="30" value="3"></label>
      <label><input type="checkbox" id="playlist"> Playlist ise tümünü indir</label>
      <div class="spacer"></div>
      <button class="primary" id="add">⬇ İndirmeye Başla</button>
    </div>
    <div class="note" id="ffmpegNote" hidden>⚠ ffmpeg bulunamadı: en yüksek kalite ve MP3 dönüştürme için ffmpeg kurman önerilir (winget install ffmpeg).</div>
  </div>

  <div class="card">
    <div class="row" style="margin-top:0;margin-bottom:8px">
      <div class="stats" id="stats"></div>
      <div class="spacer"></div>
      <button id="openFolder">📂 Klasörü Aç</button>
      <button id="clear">Tamamlananları Temizle</button>
      <button id="cancelAll">⏹ Tümünü Durdur</button>
    </div>
    <table>
      <thead><tr><th>#</th><th>Başlık / Link</th><th>Durum</th><th>İlerleme</th><th>Hız</th><th>Kalan</th><th></th></tr></thead>
      <tbody id="tbody"></tbody>
    </table>
  </div>
</div>

<script>
const $ = id => document.getElementById(id);
const LABEL = { queued:"Sırada", starting:"Başlıyor", downloading:"İndiriliyor", processing:"Hazırlanıyor",
                done:"Tamamlandı ✔", error:"Hata ✘", cancelled:"İptal" };
const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));

async function api(path, method="GET", body) {
  const headers = {"X-Video-Indirici": "1"};
  if (body) headers["Content-Type"] = "application/json";
  const r = await fetch(path, { method, headers,
                                body: body ? JSON.stringify(body) : undefined });
  const data = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(data.error || r.statusText);
  return data;
}

$("add").onclick = async () => {
  const links = $("links").value.trim();
  if (!links) return $("links").focus();
  try {
    await api("/api/settings", "POST", { parallel: +$("parallel").value });
    await api("/api/jobs", "POST", {
      links, audio: $("format").value === "audio", quality: $("quality").value, playlist: $("playlist").checked });
    $("links").value = "";
    refresh();
  } catch (e) { alert(e.message); }
};
$("parallel").onchange = () => api("/api/settings", "POST", { parallel: +$("parallel").value });
$("format").onchange = () => $("quality").disabled = $("format").value === "audio";
$("clear").onclick = () => api("/api/clear", "POST").then(refresh);
$("cancelAll").onclick = () => api("/api/cancel-all", "POST").then(refresh);
$("openFolder").onclick = () => api("/api/open-folder", "POST").catch(e => alert(e.message));

async function act(id, what) {
  if (what === "delete") await api(`/api/jobs/${id}`, "DELETE");
  else await api(`/api/jobs/${id}/${what}`, "POST");
  refresh();
}

function row(j, n) {
  const active = ["queued","starting","downloading","processing"].includes(j.status);
  const color = j.status === "done" ? "var(--green)" : j.status === "error" ? "var(--red)" : j.status === "cancelled" ? "#555" : "var(--blue)";
  const files = j.files.map(f => `<a class="dl" href="/api/jobs/${j.id}/file/${f.index}" title="${esc(f.name)}">💾 İndir</a>`).join(" ");
  return `<tr>
    <td>${n}</td>
    <td class="title"><div class="t" title="${esc(j.title || j.url)}">${esc(j.title || j.url)}</div>
        ${j.title ? `<div class="u">${esc(j.url)}</div>` : ""}
        ${j.message ? `<div class="${j.status === "done" ? "note" : "err"}">${esc(j.message)}</div>` : ""}</td>
    <td><span class="st ${j.status}">${LABEL[j.status] || j.status}</span></td>
    <td><div class="bar"><i style="width:${j.percent}%;background:${color}"></i><span>${j.percent.toFixed(1)}%</span></div></td>
    <td>${esc(j.speed)}</td>
    <td>${esc(j.eta)}</td>
    <td><div class="actions">
      ${files}
      ${active ? `<button class="small" onclick="act('${j.id}','cancel')">İptal</button>` : ""}
      ${["error","cancelled"].includes(j.status) ? `<button class="small" onclick="act('${j.id}','retry')">Tekrar</button>` : ""}
      <button class="small" onclick="act('${j.id}','delete')" title="Listeden sil">✕</button>
    </div></td></tr>`;
}

let first = true;
async function refresh() {
  try {
    const d = await api("/api/jobs");
    $("folder").textContent = d.folder;
    $("ffmpegNote").hidden = d.ffmpeg;
    if (first) { $("parallel").value = d.parallel; first = false; }
    const c = s => d.jobs.filter(j => s.includes(j.status)).length;
    $("stats").textContent = `İndiriliyor: ${c(["starting","downloading","processing"])} · Sırada: ${c(["queued"])} · Tamamlandı: ${c(["done"])} · Hata: ${c(["error"])}`;
    $("tbody").innerHTML = d.jobs.length ? d.jobs.map((j, i) => row(j, i + 1)).join("")
      : `<tr><td colspan="7" class="empty">Henüz link eklenmedi.</td></tr>`;
  } catch (e) { $("stats").textContent = "Sunucuya bağlanılamadı. app.py çalışıyor mu?"; }
}
refresh();
setInterval(refresh, 1000);
</script>
</body>
</html>"""


if __name__ == "__main__":
    threading.Thread(target=scheduler, daemon=True).start()
    url = f"http://{HOST}:{PORT}"
    print(f"Video İndirici çalışıyor: {url}")
    print(f"Dosyalar buraya kaydediliyor: {DOWNLOAD_DIR}")
    print("Cloudflare korumalı siteler (impersonation): " + ("acik" if IMPERSONATE_OK else
          'KAPALI -> kurulum: pip install -U "yt-dlp[default,curl-cffi]"'))
    print("Kapatmak için bu pencerede Ctrl+C")
    threading.Timer(1.2, lambda: webbrowser.open(url)).start()
    app.run(host=HOST, port=PORT, threaded=True)
