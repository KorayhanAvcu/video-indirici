"""Sahte anime sitesi: çerez + Referer korumalı, uzantısı gizli m3u8 ve altyazı."""
import http.server, sys
from pathlib import Path
ROOT = Path(__file__).parent / "site"
VTT_TR = "WEBVTT\n\n00:00:00.500 --> 00:00:03.000\nHadi düello başlasın!\n".encode()
VTT_EN = b"WEBVTT\n\n00:00:00.500 --> 00:00:03.000\nGet your game on!\n"
LOG = []
USED = set()

class H(http.server.BaseHTTPRequestHandler):
    def send(self, code, body=b"", ctype="text/plain", extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?")[0]
        ref = self.headers.get("Referer", "")
        cookie = self.headers.get("Cookie", "")
        from_player = "/player/" in ref
        print(f"{self.path} ref={'player' if from_player else ref[-30:]!r} cookie={'oturum=gizli' in cookie} xrw={self.headers.get('X-Requested-With')}", file=sys.stderr, flush=True)
        if path == "/izle/14":
            return self.send(200, (ROOT / "izle.html").read_bytes(), "text/html; charset=utf-8")
        if path == "/player/14":
            return self.send(200, (ROOT / "player.html").read_bytes(), "text/html; charset=utf-8",
                             {"Set-Cookie": "oturum=gizli; Path=/; HttpOnly"})
        if path == "/direct/test.mp4":
            return self.send(200, (ROOT / "direct" / "test.mp4").read_bytes(), "video/mp4")
        if path == "/hls.min.js":
            return self.send(200, (ROOT / "hls.min.js").read_bytes(), "application/javascript")
        # Korumalı içerik: player'dan gelen Referer + oturum çerezi şart
        if path.startswith(("/akis/", "/subs/", "/api/")):
            if not from_player or "oturum=gizli" not in cookie:
                return self.send(403, b"Forbidden")
        if path == "/api/altyazi":
            # Tek kullanımlık + başlık korumalı altyazı (süreli/tek seferlik token taklidi)
            if self.headers.get("X-Requested-With") != "XMLHttpRequest" or USED:
                return self.send(403, b"Forbidden")
            USED.add(1)
            return self.send(200, VTT_TR, "text/plain; charset=utf-8")
        if path == "/subs/en.vtt":
            return self.send(200, VTT_EN, "text/vtt")
        if path.startswith("/akis/"):
            if self.headers.get("X-Requested-With") != "XMLHttpRequest":
                return self.send(200, b"security error", "text/html")  # anizmplayer tarzı
            f = ROOT / path.lstrip("/")
            if f.exists():
                return self.send(200, f.read_bytes(), "text/plain" if f.suffix == ".txt" else "video/mp2t")
        self.send(404, b"yok")

    def log_message(self, *a):
        pass

if __name__ == "__main__":
    http.server.ThreadingHTTPServer(("0.0.0.0", int(sys.argv[1])), H).serve_forever()
