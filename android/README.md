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

