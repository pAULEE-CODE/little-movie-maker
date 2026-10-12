param(
    [string]$RcloneRemote = "gdrive",
    [string]$DriveFolder = "Little Movie Maker — Test Builds"
)

$ErrorActionPreference = "Stop"
$projectRoot = $PSScriptRoot
$apkPath = Join-Path $projectRoot "app\build\outputs\apk\debug\app-debug.apk"
$drivePath = "${RcloneRemote}:$DriveFolder"

Push-Location $projectRoot
try {
    $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
    $env:TEMP = "C:\Windows\Temp"
    $env:TMP = "C:\Windows\Temp"

    $rclone = Get-Command rclone -ErrorAction SilentlyContinue
    if (-not $rclone) {
        throw "rclone is not installed. Install it and configure the '$RcloneRemote' Google Drive remote as described in README.md."
    }

    $configuredRemotes = & $rclone.Source listremotes
    if ($LASTEXITCODE -ne 0 -or $configuredRemotes -notcontains "${RcloneRemote}:") {
        throw "Google Drive remote '$RcloneRemote' is not configured. Run 'rclone config' and sign in to your Google account first."
    }

    # Confirm sign-in and folder access before spending time on a build.
    & $rclone.Source lsf $drivePath --max-depth 1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Cannot access '$drivePath'. Check the rclone sign-in and confirm the private Drive folder exists."
    }

    & .\gradlew.bat assembleDebug --console plain
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle assembleDebug failed with exit code $LASTEXITCODE. No APK was uploaded."
    }

    if (-not (Test-Path -LiteralPath $apkPath -PathType Leaf)) {
        throw "Gradle succeeded but the expected APK is missing: $apkPath. Nothing was uploaded."
    }
    $apk = Get-Item -LiteralPath $apkPath
    if ($apk.Length -le 0) {
        throw "The APK is empty. Nothing was uploaded."
    }

    $versionLine = Select-String -Path (Join-Path $projectRoot "app\build.gradle.kts") -Pattern 'versionName\s*=\s*"([^"]+)"' | Select-Object -First 1
    if (-not $versionLine -or $versionLine.Line -notmatch 'versionName\s*=\s*"([^"]+)"') {
        throw "Could not read versionName from app/build.gradle.kts. The APK was built but was not uploaded."
    }
    $version = $Matches[1] -replace '[^A-Za-z0-9._-]', '_'
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss-fff"
    $fileName = "LittleMovieMaker-test-$timestamp-v$version.apk"
    $destination = "$drivePath/$fileName"

    & $rclone.Source copyto $apkPath $destination --immutable
    if ($LASTEXITCODE -ne 0) {
        throw "The APK built successfully but Google Drive upload failed. The local APK remains at $apkPath."
    }

    $uploaded = & $rclone.Source lsf $drivePath --files-only
    if ($LASTEXITCODE -ne 0 -or $uploaded -notcontains $fileName) {
        throw "Upload command completed, but Drive readback did not find '$fileName'. Verify the folder in Drive."
    }

    Write-Host "Build succeeded and APK uploaded: $fileName"
    Write-Host "Drive destination: $destination"
    Write-Host "Local APK: $apkPath"
}
finally {
    Pop-Location
}
