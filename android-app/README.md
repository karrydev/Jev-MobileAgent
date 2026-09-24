# Jev Android observation app

> 2026-09-25：以下说明当前桥接式实现或其历史验收。按 [ADR-0003](../docs/adr/0003-standalone-android-runtime.md)，最终产品改为手机内编排；这些操作不作为独立 App 用户的使用前提，迁移由任务 26/27 验收。

This is the minimal real device bridge for task 06. It is a plain Java
Android app with no external runtime dependency. The app has two screens:

* **Jev Android observation** stores the bridge URL, token, device identity and
  task identity, shows connection and Accessibility permission status, pairs
  with the bridge, and displays the latest current observation returned by the
  server.
* **Controlled observation page** provides visible text, state and a button so
  the device evidence comes from a real accessibility tree rather than a
  hand-written fixture.

## Build and install

The checked-in Gradle wrapper pins Gradle 9.4.1. `bootstrap.sh` locates the
Android SDK from `JEV_ANDROID_SDK`, `ANDROID_SDK_ROOT`, or the standard macOS
and Linux SDK locations, then runs that wrapper. From this directory:

```bash
export JEV_ANDROID_SDK=/path/to/android-sdk
./bootstrap.sh --offline --no-daemon --console plain :app:assembleDebug
"$JEV_ANDROID_SDK/platform-tools/adb" -s <device-serial> \
  install -r app/build/outputs/apk/debug/app-debug.apk
```

`--offline` is reproducible after the pinned Gradle distribution and Android
Gradle Plugin dependencies are present in the local Gradle cache. Omit it only
when the first wrapper/dependency download is intentionally allowed. The app
build is self-contained under `android-app/`; no system `gradle` or global
`ANDROID_HOME` setting is required.

## Emulator run

Start the Python bridge on the host first:

```bash
JEV_ANDROID_AUTH_TOKEN=local-dev-token \
python3 -m services.android_bridge --host 0.0.0.0 --port 8765 \
  --device-id android-emulator-01
```

On the Android emulator, `http://10.0.2.2:8765` routes to the host. Launch
the app, keep the matching local token and identity, open Android's
Accessibility settings, enable **Jev observation service**, and return to the
app. Press **Connect and capture observation**, then open the controlled page.
The service captures on the page/window events and posts the real windows and
node tree. A fresh tree appears in the app and can also be read from:

```bash
curl -sS \
  -H 'Authorization: Bearer local-dev-token' \
  -H 'X-JEV-Protocol-Version: 1' \
  -H 'X-JEV-Device-Id: android-emulator-01' \
  'http://127.0.0.1:8765/v1/android/observations/latest?device_id=android-emulator-01'
```

The app posts an explicit `PERMISSION_UNAVAILABLE` observation when the
service is disabled. An empty active root is reported as `EMPTY_TREE`. If a
bridge request fails, the visible screen clears its latest observation and
shows a disconnected reason; it does not continue presenting the previous
tree as current. The bridge also refuses stale observation versions and stale
latest reads.

## USB-connected phone run

Use an isolated bridge port and scope every ADB command to the intended serial.
`adb reverse` is only a debugging tunnel; it is not the deployment model.

```bash
export JEV_ANDROID_SDK=/path/to/android-sdk
JEV_ANDROID_AUTH_TOKEN=local-dev-token \
python3 -m services.android_bridge --host 127.0.0.1 --port 18765 \
  --device-id android-phone-01

"$JEV_ANDROID_SDK/platform-tools/adb" -s <device-serial> \
  reverse tcp:18765 tcp:18765
```

Set the app endpoint to `http://127.0.0.1:18765`, use the matching token and
`android-phone-01` identity, and perform the same pairing and controlled-page
steps. Remove only this serial's reverse rule after the run with
`adb -s <device-serial> reverse --remove tcp:18765`.

When Accessibility is revoked, the service posts a fresh
`PERMISSION_UNAVAILABLE` observation with an empty tree before stopping. The
app also checks permission and bridge freshness when it returns to the
foreground, so it clears the old tree on revocation, a failed bridge request,
or a stale latest response.

## Reproducible deterministic node task

On the connection screen, set the endpoint, token, device identity and a new
task identity for the run, then press **Connect and capture observation**.
This saves the pairing and task configuration and uploads the first fresh
tree. Use the deterministic goal buttons to fill either the click goal or the
Chinese input goal, then press **Open controlled observation page** and press
**Start configured node task** there. The app captures and
uploads a fresh before observation, submits the goal, polls the bridge, and
performs the returned `tap` or `set_text` through the Accessibility service.
It captures the after observation and sends the execution receipt before
showing the task result. The product action must come from this app flow;
`adb` is only for installation, transport setup, or observing the device.

Use the same bridge headers shown above to inspect a run, replacing the task
identity with the value entered in the app:

```bash
curl -sS \
  -H 'Authorization: Bearer local-dev-token' \
  -H 'X-JEV-Protocol-Version: 1' \
  -H 'X-JEV-Device-Id: android-emulator-01' \
  'http://127.0.0.1:8765/v1/android/tasks/<task-id>'
```

Task identities are single-use while retained by the bridge, so a rerun needs
a new identity and a new **Connect and capture observation** pairing. Pausing
leaves the existing task in `PAUSED`; the app keeps **Cancel task** enabled
and disables **Start node task** so a pause cannot be mistaken for a new
submission. Cancel the paused task from the controlled page before beginning
another run.

## Reproducible visual and gesture task

The controlled page includes a small self-drawn gesture surface whose semantic
description is intentionally empty. Its separate `Visual gesture state` text
node records the observed result. The page also keeps the Chinese `EditText`,
the node-addressable controlled-state button, and the system-back state on the
same screen, so IME, semantic-missing, rotation, and window-stack cases can be
checked without operating a private app.

Use a new task identity, pair and capture first, then choose one of these
goals on the connection screen before opening the controlled page:

| Goal | Accessibility action | Expected independent page state |
| --- | --- | --- |
| `长按“切换受控状态”按钮` | retained-node long press | `long press completed` |
| `滑动视觉目标` | coordinate swipe through the visual surface | `swipe completed` |
| `点击坐标(540,1200)` | coordinate tap | `coordinate tap completed` |
| `系统返回` | Accessibility global Back | `system back completed` |

For visual goals the App first uploads a fresh tree and a real API 30+
Accessibility screenshot tagged `BEFORE` with that observation id/version.
The bridge refuses the action if that image is absent or belongs to another
observation. After Accessibility reports the gesture/global action complete,
the App uploads a fresh `AFTER` observation and screenshot before sending the
receipt. The receipt records acceptance separately from the postcondition;
missing screenshot reasons leave a visual task paused rather than reporting
success. Screenshot capture and successful upload counters are separate fields
in the screenshot payload. A failed capture or upload is sent as an unavailable
visual event with its reason and counters; the bridge exposes that metadata on
the latest observation and task status while keeping the failed event out of
the usable BEFORE/AFTER image index.

The action frame carries display size, model size, rotation, system-bar
insets, window offset, coordinate space, and the bound active window identity
and bounds. Screen coordinates are scaled in the current display orientation;
rotation is a freshness guard rather than a second coordinate transform. The
service rejects a changed display or bound window, checks the active/focused
Accessibility window and every higher-layer intersection, then maps model
coordinates before `dispatchGesture`. A rotation, multi-window change, or
overlay therefore requires a new observation. A screenshot may also be
rejected by a secure window or revoked permission; inspect the task failure
reason and pair a new identity after the page is stable.
