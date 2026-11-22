# DJI Home APK analysis (persistent notes)

Source APK: DJI_Home_1.2.1.apk (arm64-v8a, Flutter AOT, built 2024-11-04).
These notes are committed so decompile findings are never lost to a workspace reset again.

## Accessory type codes (0x04 config, row[16] = type byte)
The DJI app maps this byte via a Dart `AccessoryTypeEnum` (confirmed present in libapp.so).
The station's 0x04 list reports only *externally attached* accessories (the row vanishes when unplugged).

| code | accessory (DJI app wording)                     | source            |
|------|--------------------------------------------------|-------------------|
| 2    | DJI Power Solar Panel Adapter Module (MPPT)      | empirical + string |
| ?    | DJI Power Car Power Outlet to SDC Power Cable (12V/24V) | string, code TBD |

Only two external-accessory display strings ship in the app (English):
- "DJI Power Solar Panel Adapter Module (MPPT)"
- "DJI Power Car Power Outlet to SDC Power Cable (12V/24V)"
Related home icons: ic_power_solar.svg, ic_power_sdc_car.svg, ic_power_sdc_normal.svg.

## Still to recover
- Full code->enum mapping (AccessoryTypeEnum switch) via Blutter decompile. Persist the
  decompiled accessory/protocol sources here under docs/decompile/ when regenerated.
