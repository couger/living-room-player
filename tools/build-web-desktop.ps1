param([string]$Python='python')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$dist=Join-Path $root 'outputs/PC-WEB-EXE/LivingRoomWeb'
if(Test-Path -LiteralPath (Join-Path $dist '用户数据')){throw 'Output folder contains portable user data. Preserve it outside the build output before rebuilding.'}
if(Get-Process -Name LivingRoomWeb -ErrorAction SilentlyContinue | Where-Object {$_.Path -eq (Join-Path $dist 'LivingRoomWeb.exe')}){throw 'Stop the running desktop application before rebuilding its files.'}
$env:PYINSTALLER_CONFIG_DIR=Join-Path $root 'local/web-desktop-build/cache'
$env:TEMP=Join-Path $root 'local/web-desktop-build/temp'
$env:TMP=$env:TEMP
New-Item -ItemType Directory -Force $env:TEMP | Out-Null
& $Python -c "import sys,pathlib; sys.path.insert(0,sys.argv[1]); from web_tray import icon_file; pathlib.Path(sys.argv[2]).write_bytes(icon_file())" $PSScriptRoot "$root/local/web-desktop-build/desktop.ico"
if($LASTEXITCODE){throw 'Desktop icon generation failed'}
& $Python -m PyInstaller --noconfirm --onedir --windowed --noupx --name LivingRoomWeb --icon "$root/local/web-desktop-build/desktop.ico" --distpath "$root/outputs/PC-WEB-EXE" --workpath "$root/local/web-desktop-build/work" --specpath "$root/local/web-desktop-build" --paths $PSScriptRoot --hidden-import web-debug-toolkit --exclude-module bcrypt --copy-metadata cryptography --copy-metadata cffi --copy-metadata pycparser --add-data "$root/work/player-control/index.html;work/player-control" "$PSScriptRoot/web_desktop.py"
if($LASTEXITCODE){throw 'Desktop build failed'}
Copy-Item -LiteralPath "$root/LICENSE" -Destination "$root/outputs/PC-WEB-EXE/LivingRoomWeb/LICENSE.txt" -Force
Copy-Item -LiteralPath "$root/docs/web-debug-toolkit.md" -Destination "$root/outputs/PC-WEB-EXE/LivingRoomWeb/使用说明.md" -Force
$pythonHome=& $Python -c "import sys; print(sys.base_prefix)"
if($LASTEXITCODE){throw 'Cannot locate the Python runtime license'}
Copy-Item -LiteralPath (Join-Path $pythonHome 'LICENSE.txt') -Destination "$root/outputs/PC-WEB-EXE/LivingRoomWeb/Python-LICENSE.txt" -Force
Get-Item "$root/outputs/PC-WEB-EXE/LivingRoomWeb/LivingRoomWeb.exe" | Select-Object Name,Length
