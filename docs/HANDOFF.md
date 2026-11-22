# DJI Homie – handoff notes

Native Kotlin/Compose Android app for fully local BLE control of DJI Power stations.
Owner's hardware: DJI Power 1000 (v1) + dongle + Solar Panel Adapter Module (MPPT).
CI: GitHub Actions builds the APK on every push. Owner pushes with `push.bat` (local, untracked).

## Layout (`app/src/main/java/com/djipower/companion/`)
- `ble/DjiPowerDevice.kt` – GATT client, DUML framing, AES transport (v1 only), auto-reconnect (1s→30s backoff), config read/write.
- `ble/DeviceScanner.kt` – scan; model from manufacturer data (0x08AA) or name.
- `protocol/` – `Duml` (frames, CRC), `Models` (model codes), `Energy` (limits, reserve, energy saver 0x18), `Advanced` (rules 0x0E, car chargers 0x0A, price periods 0x16), `DeviceInfo` (accessories), `Report` (telemetry).
- `data/Conn.kt` – process singleton owning the device + StateFlows; `AppSettings` (automation prefs), `CredStore` (MAC, pair key, model), `HistoryStore`.
- `service/` – foreground service (only runs if an automation needs it), `AutomationEngine` (zero-load, SOC cutoff, duty cycle, solar priority, storm prep), `WeatherMonitor` (NWS alerts), `MqttBridge`, `AcTileService`, `CommandReceiver` (`com.djipower.companion.SET_AC`), `BootReceiver`.
- `ui/` – `MainActivity` (dashboard, settings, automations), `AdvancedPanels` (schedule, car chargers), `OnboardingScreen`.
- `widget/StatusWidget.kt` – home-screen widget.

## Protocol notes
- Keyed config: `key:u8, 0x10, len:u16, value`. GET payload `0x00` + (key,0x10) pairs. Status in decoded[0:4]: 0 ok, 2 missing keys, 1/3 too large → read optional keys separately (0x0A, 0x0E, 0x16 are read in their own GET).
- Keys: 0x00 base, 0x01 expansion, 0x02 network, 0x04 accessories (row[16] type: 1 car cable (guess), 2 MPPT (confirmed)), 0x05 charge limits, 0x06 reserve, 0x0A car chargers, 0x0C display/timeouts, 0x0D port switches, 0x0E rules, 0x15 timezone, 0x16 time periods, 0x18 energy saver.
- Models: 0x91 Power 1000 (AES), 0x97 1000 V2, 0x98 1000 Mini, 0x94 2000, 0x9E Auro 2000 Elite (plaintext).
- Device-timeout idle counter does not reset when the timeout changes (1 min = immediate off if already idle).
- More detail: `docs/apk-analysis.md`; reference impl: github.com/zuyan9/ha-dji-power-ble.

## Concurrency / robustness rules
- Every DUML request goes through `DjiPowerDevice.request()`, serialised by a `Mutex` (one GATT op at a time); a busy `writeCharacteristic` is retried, never ignored. RSSI polling skips while the lock is held.
- `Duml.Stream` checks the header CRC8 before waiting for a full frame and is cleared on every (re)connect.
- Storm prep only flips `stormActive` after the station accepts the write; a failed write is retried in ~2 min.
- Tasker `SET_AC` broadcasts need the `token` extra (shown in the Automations tab).
- MQTT: retries the first connect with backoff, re-subscribes on every reconnect, publishes retained `<base>/availability` (online/offline, also the LWT).
- MQTT: publishes HA discovery configs (`homeassistant/<component>/<node>_<id>/config`, retained) for battery/power/runtime sensors, a charging binary_sensor and an AC switch; state JSON carries `ac_on`. The service rebuilds the client in `onStartCommand` when MQTT settings change (Apply button); `MqttBridge.status` drives the status line.
- Unit tests for the pure protocol layer live in `app/src/test`; CI runs `testDebugUnitTest` before `assembleDebug`.

## Owner decisions
- Background service only when an enabled feature needs it, never by default.
- Storm prep: weather-triggered; raises charge limit to 100% for a hurricane/tropical alert, restores the prior limit after.
- No AC-shed outage reserve, no idle power-down (no Power switch on the unit).

## Unverified / open
- Other models (1000 V2, Mini, 2000, Auro) untested on hardware.
- Price periods / Scheduled controls and car-charger controls untested (owner has no car charger).
- Accessory type 1 = car cable is a guess.
