# Creates the Android release signing key for Benbow (run once, by the owner).
# Usage: powershell -ExecutionPolicy Bypass -File tools\create-release-key.ps1
#
# Android only installs updates signed with the same key. If the key is lost, the app has to be
# uninstalled to install a new build, and that wipes anything on the phone not yet synced.
# Back up BOTH files this script creates to both disks (and keep the password somewhere safe).

$ErrorActionPreference = 'Stop'
$dir = Join-Path $env:USERPROFILE '.benbow'
$store = Join-Path $dir 'benbow-release.jks'
$props = Join-Path $dir 'release-signing.properties'

if (Test-Path $store) {
    Write-Host "A release key already exists: $store. Not overwriting it."
    exit 1
}

$keytool = @(
    (Get-Command keytool -ErrorAction SilentlyContinue).Source,
    "$env:ProgramFiles\Android\Android Studio\jbr\bin\keytool.exe",
    "$env:JAVA_HOME\bin\keytool.exe"
) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $keytool) { throw 'keytool not found (install a JDK or Android Studio).' }

$first = Read-Host 'Password for the release key (at least 8 characters)' -AsSecureString
$second = Read-Host 'Repeat the password' -AsSecureString
$plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($first))
$again = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($second))
if ($plain -ne $again) { throw 'The passwords differ.' }
if ($plain.Length -lt 8) { throw 'The password is too short.' }

New-Item -ItemType Directory -Force $dir | Out-Null
& $keytool -genkeypair -v -keystore $store -storetype PKCS12 -alias benbow -keyalg RSA -keysize 4096 -validity 36500 `
    -dname 'CN=Benbow, O=Personal' -storepass $plain -keypass $plain
if ($LASTEXITCODE -ne 0) { throw 'keytool failed.' }

@"
# Benbow release signing (read by apps/android/build.gradle.kts). Keep private; back up with the .jks.
storeFile=$($store -replace '\\', '/')
storePassword=$plain
keyAlias=benbow
keyPassword=$plain
"@ | Set-Content -Encoding UTF8 $props

Write-Host ''
Write-Host "Created $store"
Write-Host "and     $props"
Write-Host 'Back up both files to both disks now. Then: gradlew :apps:android:installRelease'
