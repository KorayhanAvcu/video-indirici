"""Video İndirici — bağlantı teşhisi.

Çalıştır:  python teshis.py
İsteğe bağlı başka siteler:  python teshis.py ornek.com baska.com

Her site için şunları dener ve tek tabloda gösterir:
  DNS      : Bilgisayarın DNS'i ne diyor, Cloudflare DNS (1.1.1.1, şifreli) ne diyor. Farklıysa
             internet sağlayıcın adresi değiştiriyor (DNS engeli) olabilir.
  TCP v4/v6: Sunucunun 443 portuna IPv4 / IPv6 ile bağlantı kurulabiliyor mu.
  urllib   : yt-dlp'nin normal yolu (Python).
  curl_cffi: yt-dlp'nin tarayıcı taklidi yolu.
"""
import json
import socket
import ssl
import sys
import time
import urllib.request

HOSTS = sys.argv[1:] or ["media.animerank.tv", "anizmplayer.com", "anizm.net", "www.google.com"]
T = 8  # saniye


def ms(t0):
    return f"{(time.time() - t0) * 1000:.0f}ms"


def system_dns(host):
    try:
        infos = socket.getaddrinfo(host, 443, proto=socket.IPPROTO_TCP)
    except Exception as e:
        return [], [], f"HATA {type(e).__name__}"
    v4 = sorted({i[4][0] for i in infos if i[0] == socket.AF_INET})
    v6 = sorted({i[4][0] for i in infos if i[0] == socket.AF_INET6})
    return v4, v6, ""


def doh(host):
    try:
        req = urllib.request.Request(f"https://cloudflare-dns.com/dns-query?name={host}&type=A",
                                     headers={"Accept": "application/dns-json"})
        data = json.loads(urllib.request.urlopen(req, timeout=T).read())
        return sorted({a["data"] for a in data.get("Answer", []) if a.get("type") == 1})
    except Exception as e:
        return [f"HATA {type(e).__name__}"]


def tcp(ip, family):
    if not ip:
        return "-"
    t0 = time.time()
    try:
        with socket.socket(family, socket.SOCK_STREAM) as s:
            s.settimeout(T)
            s.connect((ip, 443))
        return f"OK {ms(t0)}"
    except Exception as e:
        return f"HATA {type(e).__name__} {ms(t0)}"


def via_urllib(host):
    t0 = time.time()
    try:
        r = urllib.request.urlopen(urllib.request.Request(f"https://{host}/", headers={"User-Agent": "Mozilla/5.0"}),
                                   timeout=T, context=ssl.create_default_context())
        return f"HTTP {r.status} {ms(t0)}"
    except urllib.error.HTTPError as e:
        return f"HTTP {e.code} {ms(t0)}"  # sunucuya ulaşıldı (403 vb. yine de bağlantı var demek)
    except Exception as e:
        return f"HATA {type(e).__name__}: {str(e)[:50]} {ms(t0)}"


def via_curl(host):
    try:
        from curl_cffi import requests as cr
    except ImportError:
        return "kurulu değil"
    t0 = time.time()
    try:
        r = cr.get(f"https://{host}/", impersonate="chrome", timeout=T)
        return f"HTTP {r.status_code} {ms(t0)}"
    except Exception as e:
        return f"HATA {str(e)[:70]} {ms(t0)}"


def main():
    print("Python", sys.version.split()[0])
    try:
        import curl_cffi
        print("curl_cffi", curl_cffi.__version__)
    except ImportError:
        print("curl_cffi kurulu değil")
    print("Vekil (proxy) ayarları:", urllib.request.getproxies() or "yok")
    print()
    for host in HOSTS:
        v4, v6, err = system_dns(host)
        print(f"== {host}")
        print(f"   DNS (bilgisayar) : {err or ', '.join(v4 + v6) or 'boş'}")
        print(f"   DNS (1.1.1.1)    : {', '.join(doh(host)) or 'boş'}")
        print(f"   TCP IPv4         : {tcp(v4[0] if v4 else None, socket.AF_INET)}")
        print(f"   TCP IPv6         : {tcp(v6[0] if v6 else None, socket.AF_INET6)}")
        print(f"   urllib (normal)  : {via_urllib(host)}")
        print(f"   curl_cffi (taklit): {via_curl(host)}")
        print()
    print("Bu çıktının tamamını kopyalayıp gönder.")


if __name__ == "__main__":
    main()
