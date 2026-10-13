param(
 [string]$JdkPath=$env:JAVA_HOME,
 [string]$SdkPath=$env:ANDROID_SDK_ROOT,
 [string]$BuildToolsVersion='36.0.0',
 [string]$PlatformVersion='android-34',
 [string]$SigningKeyPath,
 [string]$SigningPassword=$env:LIVING_PLAYER_SIGNING_PASSWORD
)
$ErrorActionPreference='Stop'
$base=$PSScriptRoot
$outputFile="$base\..\..\outputs\系统故障记录-v0.1.0.apk"
if(!$JdkPath -or !$SdkPath){throw 'Set JAVA_HOME and ANDROID_SDK_ROOT, or pass -JdkPath and -SdkPath.'}
$jdk=$JdkPath
$tools=Join-Path (Join-Path $SdkPath 'build-tools') $BuildToolsVersion
$android=Join-Path (Join-Path (Join-Path $SdkPath 'platforms') $PlatformVersion) 'android.jar'
if(!(Test-Path -LiteralPath $tools) -or !(Test-Path -LiteralPath $android)){throw 'The specified Android build-tools or platform is not installed.'}
if(!$SigningKeyPath){$SigningKeyPath="$base\..\player-signing.jks"}
$signingConfig="$base\..\..\local\signing.local.json"
New-Item -ItemType Directory -Force "$base\..\..\outputs","$base\..\..\local" | Out-Null
if(!$SigningPassword -and (Test-Path -LiteralPath $signingConfig)){$SigningPassword=(Get-Content -Raw -LiteralPath $signingConfig | ConvertFrom-Json).password}
if(!$SigningPassword){
 if(Test-Path -LiteralPath $SigningKeyPath){throw 'Existing signing key: provide LIVING_PLAYER_SIGNING_PASSWORD or local/signing.local.json.'}
 $SigningPassword=[Guid]::NewGuid().ToString('N')
 @{password=$SigningPassword} | ConvertTo-Json | Set-Content -LiteralPath $signingConfig -Encoding utf8
}
$env:LIVING_PLAYER_SIGNING_PASSWORD=$SigningPassword
$env:JAVA_HOME=$jdk
$env:PATH="$jdk\bin;$env:PATH"
Remove-Item -LiteralPath "$base\build\classes","$base\build\dex" -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$base\build\classes","$base\build\dex" | Out-Null
# Android sh treats CR as part of a command/path. Normalize packaged scripts,
# including files edited by Windows Python/PowerShell before this build.
Get-ChildItem "$base\assets" -Filter *.sh | ForEach-Object {
 $scriptText=[IO.File]::ReadAllText($_.FullName).Replace("`r`n","`n")
 [IO.File]::WriteAllText($_.FullName,$scriptText,[Text.UTF8Encoding]::new($false))
}
$sources=Get-ChildItem "$base\src" -Filter *.java -Recurse | ForEach-Object FullName
& "$jdk\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -classpath $android -d "$base\build\classes" @sources
if($LASTEXITCODE){throw 'Java compilation failed'}
& "$jdk\bin\jar.exe" cf "$base\build\classes.jar" -C "$base\build\classes" .
& "$tools\d8.bat" --lib $android --min-api 26 --output "$base\build\dex" "$base\build\classes.jar"
if($LASTEXITCODE){throw 'DEX failed'}
& "$tools\aapt2.exe" link --manifest "$base\AndroidManifest.xml" -I $android -A "$base\assets" -o "$base\build\unsigned.apk" --min-sdk-version 26 --target-sdk-version 32
if($LASTEXITCODE){throw 'APK packaging failed'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[IO.Compression.ZipFile]::Open("$base\build\unsigned.apk",[IO.Compression.ZipArchiveMode]::Update)
try{[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,"$base\build\dex\classes.dex",'classes.dex') | Out-Null}finally{$archive.Dispose()}
& "$tools\zipalign.exe" -f 4 "$base\build\unsigned.apk" "$base\build\aligned.apk"
if($LASTEXITCODE){throw 'Alignment failed'}
if(!(Test-Path -LiteralPath $SigningKeyPath)){
 & "$jdk\bin\keytool.exe" -genkeypair -keystore $SigningKeyPath -storepass:env LIVING_PLAYER_SIGNING_PASSWORD -keypass:env LIVING_PLAYER_SIGNING_PASSWORD -alias player -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=Living Player Local Build'
 if($LASTEXITCODE){throw 'Signing key failed'}
}
& "$tools\apksigner.bat" sign --ks $SigningKeyPath --ks-pass env:LIVING_PLAYER_SIGNING_PASSWORD --key-pass env:LIVING_PLAYER_SIGNING_PASSWORD --out $outputFile "$base\build\aligned.apk"
if($LASTEXITCODE){throw 'Signing failed'}
& "$tools\apksigner.bat" verify $outputFile
if($LASTEXITCODE){throw 'APK verification failed'}
Get-Item $outputFile | Select-Object Name,Length
