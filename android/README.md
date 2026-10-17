# AnimationWorkshop

Native Android stop-motion app built with Kotlin, Jetpack Compose, and CameraX.

## Build a debug APK on Windows

Run the following in PowerShell from the project root. It uses Android Studio's bundled JDK and the Android SDK already configured in `local.properties`.

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:TEMP = "C:\Windows\Temp"
$env:TMP = "C:\Windows\Temp"
.\gradlew.bat assembleDebug
```

The `TEMP` and `TMP` overrides are scoped to this PowerShell process and its child Gradle processes. They do not change Windows-wide environment variables or security settings. Gradle needs this temp location on this machine for Java NIO's local IPC during startup.

The Android SDK path is configured locally in `local.properties`, which is not committed.

The generated debug APK is located at:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Development build workflow

After Android source changes, build with the PowerShell procedure above before reporting completion. If Kotlin compilation fails, inspect the compiler diagnostics, fix the source errors, and rerun `assembleDebug`. Repeat until the debug APK builds or a remaining blocker prevents it. Report the build result and APK path. Do not change Windows security settings or Gradle configuration to work around the environment issue.

## Private Google Drive test builds

Use `build-test-apk.ps1` for a debug build that uploads only after Gradle succeeds and a nonempty APK is present. It reads the app's `versionName` and `versionCode` and uploads a uniquely timestamped file that includes the tested source commit, for example `LittleMovieMaker-test-20261017-120014-123-v1.2-test1-code3-a1b2c3d4.apk`, to the private **Little Movie Maker — Test Builds** folder. Previous builds are retained. The script verifies the upload by listing the destination folder. Failed builds and incomplete APKs are never uploaded.

The script uses the `gdrive` remote from [rclone](https://rclone.org/drive/). Install rclone, then run `rclone config`, create a remote named `gdrive` of type `drive`, and complete Google's browser sign-in with the account that owns the test-build folder. Keep rclone's configuration in its default user profile location (`%APPDATA%\rclone\rclone.conf`); do not copy credentials into this repository. The uploader only writes a new file into the existing folder and does not change sharing permissions. The folder is owned by `paulee6868@gmail.com` and has no public/link permission.

From the project root, run:

```powershell
& .\build-test-apk.ps1
```

When building from a source snapshot whose Git `HEAD` does not identify the tested commit, pass it explicitly with `-CommitId <short-commit>` so the uploaded filename still identifies the exact source revision.

The process-scoped Android Studio JDK and `TEMP`/`TMP` overrides are applied by the script. If rclone or its Drive sign-in is not available, the script stops with setup instructions before building. For a build without upload, use the debug-build command above; `.gitignore` excludes APK files so they cannot be committed accidentally.

GitHub Releases remain reserved for approved public APKs.
