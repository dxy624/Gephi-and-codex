@echo off
if not exist "%~dp0.venv\Scripts\pythonw.exe" (
  echo For independent mode, run: py -3.12 scripts\setup.py --mode isolated --install-skill --configure-codex
  pause
  exit /b 1
)
start "" "%~dp0.venv\Scripts\pythonw.exe" "%~dp0scripts\run_dev.py" --wait
