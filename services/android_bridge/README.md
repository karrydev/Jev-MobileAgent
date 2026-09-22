# Android observation bridge

Run the bridge with an explicit token and the device identity that the Android
app will use:

```bash
JEV_ANDROID_AUTH_TOKEN=local-dev-token \
python3 -m services.android_bridge --host 0.0.0.0 --port 8765 \
  --device-id android-emulator-01
```

The emulator reaches a host process through `http://10.0.2.2:8765`; a USB
connected phone can use an isolated host port with `adb reverse` and
`http://127.0.0.1:<port>`. The app first `POST`s `/v1/android/pair`, then posts real observations to
`/v1/android/observations`. The service reads the current observation from
`GET /v1/android/observations/latest?device_id=android-emulator-01` and the
connection metadata from `/v1/android/status?device_id=android-emulator-01`.

Every request uses the Task 01 authentication and protocol guards:

```text
Authorization: Bearer local-dev-token
X-JEV-Protocol-Version: 1
X-JEV-Device-Id: android-emulator-01
```

`observation_version` is monotonic per device. A stale version is rejected,
and the latest endpoint returns `409 observation_stale` once its freshness
window expires. This keeps an old layout from being presented as the current
phone state. Pairing starts a new observation session and clears the previous
latest tree until a fresh observation is accepted. `PERMISSION_UNAVAILABLE` or
`DISCONNECTED` observations are retained as explicit empty-tree state and
return `connection_status: DISCONNECTED`; clients must not render the earlier
`AVAILABLE` tree.
