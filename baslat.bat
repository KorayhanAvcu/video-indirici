@echo off
chcp 65001 >nul
title Video İndirici
cd /d "%~dp0"
python app.py
if errorlevel 1 (
  echo.
  echo [!] app.py başlatılamadı. Önce kurulum.bat'ı çalıştır.
)
pause
