# DJI Power Companion

An **unofficial, open-source** Android app to monitor and control a **DJI Power
1000** portable power station over **local Bluetooth** — no DJI cloud dependency
for day-to-day use, no Home Assistant required.

> **Not affiliated with, endorsed by, or connected to DJI or SZ DJI Technology
> Co., Ltd.** "DJI" is a trademark of its owner and is used here only to
> describe compatibility. This project talks to hardware you own, for
> interoperability. Provided **with no warranty** — see the License. A power
> station is a high-energy device; you are responsible for safe use.

## What it does

- **One-time onboarding:** sign in to your DJI account (with DJI's own image
  captcha, shown in-app) to retrieve your station's local BLE **pair key**. The
  account is never touched again after that.
- **Local control:** connects directly over Bluetooth, authenticates with the
  pair key, and shows live telemetry (battery %, runtime, input/output watts,
  temperature) with AC/port controls.
- **Manual mode:** already have your MAC + pair key? Skip the cloud entirely and
  type them in.

Runtime is **local BLE only**. The DJI cloud is used at most once, to read the
pair key.

### Important limitation
A DJI Power station accepts **one Bluetooth connection at a time**. While this
app is connected, the DJI Home app can't connect over BLE, and vice-versa.

## Getting your pair key

Two ways:

1. **In-app** — use the onboarding screen (DJI account + captcha). *(wizard UI
   in progress; the `cloud/DjiCloud.kt` client that powers it is complete.)*
2. **Standalone script** — run `tools/dji_get_pair_key.py` on any computer; it
   does the same login and prints your 32-hex pair key. Paste it into the app's
   manual screen along with your station's MAC.

## Building

### In the cloud (no local toolchain)
Every push to `main` builds a debug APK via **GitHub Actions**
(`.github/workflows/build.yml`). Download it from the run's **Artifacts**, or
cut a GitHub **Release** to have the APK attached automatically. Sideload it
onto your phone (enable "install unknown apps").

### Locally
Android Studio (JDK 17), open the project, Run. Or `./gradlew assembleDebug`.

## Supported hardware
Developed for the **original DJI Power 1000** (AES-encrypted transport). Other
models use the same DUML framing but may differ in transport encryption and
feature keys; contributions welcome.

## How it works
- `protocol/Duml.kt` — DUML v1 framing, CRC8/CRC16, AES-256-CBC transport.
- `protocol/Report.kt` — 0x61 telemetry + keyed-config parsing.
- `ble/DjiPowerDevice.kt` — GATT connect, pair-key auth handshake (`0x6A`),
  request/response matching, live reports.
- `cloud/DjiCloud.kt` — one-time account login + pair-key fetch.

## Credits
Protocol understanding builds directly on the reverse-engineering in the
[`zuyan9/ha-dji-power-ble`](https://github.com/zuyan9/ha-dji-power-ble) and
[`hiromo/ha-dji-power-bt`](https://github.com/hiromo/ha-dji-power-bt) Home
Assistant integrations. This app reimplements that protocol for Android.

## License
MIT. See [LICENSE](LICENSE). No warranty; use at your own risk.
