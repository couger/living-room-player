@echo off
cd /d "%~dp0"
python -c "import cryptography" >nul 2>nul
if errorlevel 1 (
 echo Missing pairing component. Run: python -m pip install -r tools\web-requirements.txt
 pause
 exit /b 1
)
python -X utf8 tools\web-debug-toolkit.py
pause
