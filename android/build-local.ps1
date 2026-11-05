# Compile l'APK signé en local (même clé que la CI : ~/.android/impression-locale.jks).
$ErrorActionPreference = "Stop"
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr" }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" }
Set-Location $PSScriptRoot
.\gradlew.bat assembleRelease --no-daemon
if ($LASTEXITCODE -ne 0) { Write-Host "ECHEC DU BUILD"; exit 1 }
Write-Host "BUILD OK : app\build\outputs\apk\release\app-release.apk"
