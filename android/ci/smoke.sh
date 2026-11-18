#!/usr/bin/env bash
# Emülatörde uçtan uca deneme: (A) paylaş menüsüyle düz MP4, (B) uygulama içi tarayıcıda korumalı site.
set -u
PKG=com.korayhan.videoindirici
OUT=/tmp/smoke
DL="/sdcard/Download/Video Indirici"
mkdir -p "$OUT"
APK=$(ls dist/*-x86_64.apk | head -1)
FAIL=0

log() { echo "[$(date +%T)] $*" | tee -a "$OUT/smoke.txt"; }
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null; }
ls_dl() { adb shell ls "\"$DL\"" 2>/dev/null | tr -d '\r'; }

tap_text() {  # ekranda metni içeren öğeye dokun
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  local xy
  xy=$(adb shell cat /sdcard/ui.xml | python3 -c '
import re, sys
xml, want = sys.stdin.read(), sys.argv[1]
for m in re.finditer(r"<node [^>]*>", xml):
    n = m.group(0)
    t = re.search(r"text=\"([^\"]*)\"", n); d = re.search(r"content-desc=\"([^\"]*)\"", n)
    if want in (t.group(1) if t else "") or want in (d.group(1) if d else ""):
        b = list(map(int, re.findall(r"\d+", re.search(r"bounds=\"([^\"]*)\"", n).group(1))))
        print((b[0]+b[2])//2, (b[1]+b[3])//2); break
' "$1")
  if [ -z "$xy" ]; then log "BULUNAMADI: '$1'"; adb shell cat /sdcard/ui.xml > "$OUT/ui-$(date +%s).xml"; return 1; fi
  log "dokun '$1' @ $xy"
  adb shell input tap $xy
}

wait_for() {  # klasörde deseni içeren dosya oluşana kadar bekle
  local pat=$1 secs=$2
  for _ in $(seq 1 $((secs / 5))); do
    if ls_dl | grep -q -- "$pat"; then return 0; fi
    sleep 5
  done
  return 1
}

# --- test sitesi
(cd android/ci/site && python3 ../server.py 8000 > "$OUT/server.log" 2>&1 &)
sleep 2

adb install -r -g "$APK" > "$OUT/install.txt" 2>&1 || { log "KURULUM BAŞARISIZ"; cat "$OUT/install.txt"; exit 1; }
adb logcat -c
adb logcat -v time > "$OUT/logcat.txt" 2>&1 &

log "uygulama açılıyor"
adb shell am start -n $PKG/.MainActivity >/dev/null
sleep 20
shot 01-ana-ekran

# --- (A) Paylaş menüsü: düz MP4
log "A) paylaş ile http://10.0.2.2:8000/direct/test.mp4"
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "http://10.0.2.2:8000/direct/test.mp4" -n $PKG/.MainActivity >/dev/null
if wait_for "test" 180; then log "A) OK: $(ls_dl | grep test)"; else log "A) BAŞARISIZ"; FAIL=1; fi
shot 02-paylas-sonrasi

# --- (B) Uygulama içi tarayıcı: korumalı site (gizli m3u8 + tek kullanımlık altyazı)
log "B) tarayıcı: http://10.0.2.2:8000/izle/14"
adb root >/dev/null 2>&1; adb wait-for-device; sleep 3
adb shell am start -n $PKG/.BrowserActivity --es url "http://10.0.2.2:8000/izle/14" >/dev/null
sleep 30
shot 03-tarayici
if tap_text "Videolar"; then
  sleep 3
  shot 04-videolar-listesi
  if tap_text "En iyisini indir"; then
    if wait_for "Yu-Gi-Oh.*mp4" 240; then
      sleep 10
      log "B) OK: $(ls_dl | grep Yu-Gi-Oh | tr '\n' ' ')"
    else
      log "B) BAŞARISIZ: video inmedi"; FAIL=1
    fi
  else FAIL=1; fi
else FAIL=1; fi
adb shell am start -n $PKG/.MainActivity >/dev/null
sleep 3
shot 05-liste

# --- sonuçlar
log "klasör: $(ls_dl | tr '\n' '|')"
mkdir -p "$OUT/files"
adb pull "$DL" "$OUT/files" > /dev/null 2>&1
for f in "$OUT"/files/*/*.mp4 "$OUT"/files/*.mp4; do
  [ -f "$f" ] || continue
  log "ffprobe $(basename "$f"): $(ffprobe -v error -show_entries stream=codec_type,codec_name:stream_tags=language -of csv=p=0 "$f" | tr '\n' ' ')"
done
find "$OUT/files" -name '*.vtt' -exec sh -c 'echo "--- $(basename "$1")"; tail -2 "$1"' _ {} \; >> "$OUT/smoke.txt"
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then log "UYGULAMA ÇÖKTÜ (logcat)"; FAIL=1; fi
grep -E "AndroidRuntime|youtubedl|VideoIndirici|$PKG" "$OUT/logcat.txt" | tail -200 > "$OUT/logcat-app.txt"
rm -rf "$OUT/files"  # büyük dosyaları dala koyma
log "SONUÇ: $([ $FAIL = 0 ] && echo GEÇTİ || echo KALDI)"
exit $FAIL
