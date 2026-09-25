# Android runtime check for VisualChangeGuard

This is a small offline runner for the installed recovery-candidate APK. It feeds the existing sanitized `miss` and `hit` JSON/PNG pairs to production `VisualChangeGuard.inspect()` and `guardVisualDecision()` inside Android Runtime, so PNG decoding goes through production `Base64.decode()` and `BitmapFactory.decodeByteArray()`.

The runner is compiled against the worktree's `compileDebugJavaWithJavac/classes` only so `javac` and D8 can resolve package-private production types. D8 receives only `VisualGuardRuntimeRunner.class`; production classes are intentionally absent from `classes.dex` and load from the installed `base.apk`. At runtime, the runner checks the installed APK SHA-256, reports both class loaders and the class path, and asserts:

- `miss`: `inspect()` is `UNCHANGED`; event `SUCCESS` becomes `UNKNOWN` with `event_action_success_without_visible_app_change`.
- `hit`: `inspect()` is `CHANGED`; event `SUCCESS` stays `SUCCESS` with its original reason.

The JSON output includes the APK and fixture hashes, pixel comparison summary, Android SDK/release, class-loader information, and source SHA. It does not print PNG bytes or observation contents. There are no model/API calls, no Key reads, and no APK or app data changes. This checks the Android decoding and production guard methods; it does not claim the normal App UI/task path invoked the guard.

## Offline build

Run on the build host with JDK 17 and Android SDK 35 installed:

```sh
/tmp/recovery27-evidence/android-guard-runtime/build.sh
```

`JEV_REPO_ROOT` and `JEV_ANDROID_SDK` can override the fixed defaults. The script requires source `8d3bcc52aa10c86188f8d4b2b2ab09159e55de8e`, APK `/tmp/recovery27-evidence/guard-recovery-candidate.apk` with SHA-256 `9c66e641c1c61e29889703ff423a8a053074767dafe4f4a58dcd2bad1fc84a1a`, the Android 35 SDK jar, Build Tools 35.0.0 D8, and the existing Gradle Java class output. It runs `javac` and D8 only; it does not run Gradle, tests, or ADB.

Outputs are under `build/`: `dex/classes.dex` contains only the runner, and `fixtures/` contains the four existing JSON and PNG fixtures. The build script deletes and recreates only that output directory.

## Later phone invocation

The phone tester should first confirm `pm path com.jev.mobileagent` points at the already-installed APK whose SHA-256 is the pinned value above. No install or app launch is needed. Set `SERIAL` to the reserved device serial and run from the host after the offline build:

```sh
SERIAL='<reserved-device-serial>'
DEVICE_DIR=/data/local/tmp/visual-guard-runtime
APK_PATH=$(adb -s "$SERIAL" shell pm path com.jev.mobileagent | tr -d '\r' | sed -n 's/^package://p' | grep '/base.apk$' | head -n 1)
adb -s "$SERIAL" shell mkdir -p "$DEVICE_DIR/fixtures"
adb -s "$SERIAL" push /tmp/recovery27-evidence/android-guard-runtime/build/dex/classes.dex "$DEVICE_DIR/runner.dex"
adb -s "$SERIAL" push /tmp/recovery27-evidence/android-guard-runtime/build/fixtures/. "$DEVICE_DIR/fixtures/"
adb -s "$SERIAL" shell CLASSPATH="$DEVICE_DIR/runner.dex:$APK_PATH" app_process /system/bin com.jev.mobileagent.VisualGuardRuntimeRunner "$DEVICE_DIR/fixtures" "$APK_PATH"
```

The last command prints one JSON line and exits nonzero on an assertion or APK hash mismatch. The `CLASSPATH` order puts the standalone runner dex first; it contains no app classes. `VisualChangeGuard` and its production dependencies therefore resolve from the same installed APK through that PathClassLoader. The output's `sameClassLoader`, `productionClassLoader`, `javaClassPath`, `productionClassLocation`, and `installedApkSha256` fields are the runtime evidence for this boundary.

All copied fixtures are existing sanitized test resources described in `android-app/app/src/test/resources/com/jev/mobileagent/visual-change-guard/README.md`. This procedure writes only under `/data/local/tmp`; it does not install, modify, launch, or clear the app.
