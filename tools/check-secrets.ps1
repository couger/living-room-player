$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$cache=Join-Path $root 'local/tooling'
New-Item -ItemType Directory -Force $cache | Out-Null
$zip=Join-Path $cache 'gitleaks_8.30.1_windows_x64.zip'
if(!(Test-Path -LiteralPath $zip)){
 Invoke-WebRequest 'https://github.com/gitleaks/gitleaks/releases/download/v8.30.1/gitleaks_8.30.1_windows_x64.zip' -OutFile $zip
}
$expected='D29144DEFF3A68AA93CED33DDDF84B7FDC26070ADD4AA0F4513094C8332AFC4E'
if((Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash -ne $expected){throw 'Secret scanner checksum mismatch.'}
$tool=Join-Path $cache 'gitleaks-8.30.1'
Expand-Archive -LiteralPath $zip -DestinationPath $tool -Force
& "$tool/gitleaks.exe" git $root --log-opts='--all' --redact --no-banner
if($LASTEXITCODE){throw 'Secret scan failed. Do not publish; inspect redacted findings locally.'}
