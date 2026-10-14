param([switch]$Check)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
if(!$env:JAVA_HOME){throw 'Set JAVA_HOME to JDK 17.'}
$cache=Join-Path $root 'local/tooling'
New-Item -ItemType Directory -Force $cache | Out-Null
$jar=Join-Path $cache 'google-java-format-1.22.0-all-deps.jar'
if(!(Test-Path -LiteralPath $jar)){
 Invoke-WebRequest 'https://github.com/google/google-java-format/releases/download/v1.22.0/google-java-format-1.22.0-all-deps.jar' -OutFile $jar
}
$expected='16B2A1EE938686C8B1D88ABF19EB83DFD0D623CEE9DE6FC6D09980214F816D3F'
if((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -ne $expected){throw 'Formatter checksum mismatch.'}
$files=Get-ChildItem (Join-Path $root 'work/android-player/src'),(Join-Path $root 'work/android-diagnostics/src') -Recurse -Filter *.java | ForEach-Object FullName
$options=@('--replace','--skip-reflowing-long-strings')
if($Check){$options=@('--dry-run','--set-exit-if-changed','--skip-reflowing-long-strings')}
& "$env:JAVA_HOME/bin/java.exe" -jar $jar @options @files
if($LASTEXITCODE){throw 'Java format check failed.'}
