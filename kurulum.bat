@echo off
chcp 65001 >nul
title Video İndirici - Kurulum
cd /d "%~dp0"
echo ============================================
echo   Video İndirici kurulumu
echo ============================================
echo.

where winget >nul 2>nul
if errorlevel 1 (
  set "NOWINGET=1"
  echo [!] winget bulunamadı. Eksik programları README'deki bağlantılardan elle indir.
)

rem ---------- 1) Python 3.10+
python --version >nul 2>nul
if errorlevel 1 (
  echo [..] Python bulunamadı.
  if defined NOWINGET (
    echo      İndir: https://www.python.org/downloads/  ^(kurarken "Add python.exe to PATH" işaretle^)
    pause & exit /b 1
  )
  echo      winget ile kuruluyor...
  winget install -e --id Python.Python.3.12 --accept-source-agreements --accept-package-agreements
  echo.
  echo [!] Python kuruldu. Bu pencereyi KAPAT ve kurulum.bat'ı tekrar çalıştır.
  pause & exit /b 0
)
python -c "import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)"
if errorlevel 1 (
  echo [!] Python 3.10 veya üstü gerekli. Kurulu sürüm:
  python --version
  echo      İndir: https://www.python.org/downloads/
  pause & exit /b 1
)
for /f "delims=" %%v in ('python --version') do echo [OK] %%v

rem ---------- 2) Python paketleri: yt-dlp, curl_cffi, flask
echo [..] Python paketleri kuruluyor (yt-dlp, curl_cffi, flask)...
python -m pip install -q --upgrade pip
python -m pip install -U -r requirements.txt
if errorlevel 1 (
  echo [!] Paket kurulumu başarısız. Yukarıdaki hatayı kontrol et.
  pause & exit /b 1
)
python -c "import yt_dlp, flask, curl_cffi; print('[OK] yt-dlp', yt_dlp.version.__version__, '/ curl_cffi', curl_cffi.__version__)"

rem ---------- 3) ffmpeg (parçaları birleştirme, MP4, altyazı gömme)
where ffmpeg >nul 2>nul
if errorlevel 1 (
  if defined NOWINGET (
    echo [!] ffmpeg bulunamadı. İndir: https://www.gyan.dev/ffmpeg/builds/
  ) else (
    echo [..] ffmpeg kuruluyor...
    winget install -e --id Gyan.FFmpeg --accept-source-agreements --accept-package-agreements
    echo [!] ffmpeg'in görünmesi için bu pencereyi kapatıp yeni bir terminal açman gerekebilir.
  )
) else (
  echo [OK] ffmpeg
)

echo.
echo ============================================
echo   Kurulum bitti.
echo   1^) Programı aç:  baslat.bat
echo   2^) Eklentiyi kur: chrome://extensions ^> Geliştirici modu ^> Paketlenmemiş öğe yükle ^> extension klasörü
echo ============================================
pause
