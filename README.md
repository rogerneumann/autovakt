# AutoVakt — Android Auto OBD2 Dashboard

Live vehicle telemetry from any Bluetooth OBD2 adapter, displayed on your Android Auto head unit and phone. Supports 80+ vehicle profiles (EV, PHEV, ICE) with Torque Pro–compatible PID formulas. Primary target: Chevrolet Bolt EUV.

**[Privacy Policy](https://rogerneumann.github.io/autovakt/privacy-policy.html)**

---

## Features

### Android Auto — Full-Screen Dashboard (3 tabs)
AutoVakt runs as a native CarApp on your head unit with three tabs:

| Tab | Content |
|-----|---------|
| **Gauges** | SOC %, speed, power (kW), HV voltage/current, battery temp, efficiency, fuel level |
| **Media** | Quick-launch button to return to your active music app |
| **Trips** | Last 5 completed trips — date, distance, SOC range, duration |

### Android Auto — Mini-Player Strip
Registers as a `MediaBrowserService` to occupy the persistent 1/3 Coolwalk slot alongside Maps. Shows live telemetry via rendered bitmaps. Cycle views with the skip buttons:

| View | Shows |
|------|-------|
| **EV** | SOC %, power kW, SOC arc |
| **Battery** | Pack temperatures |
| **Trip** | Distance driven, energy used |

### Phone UI
- Live gauge dashboard mirroring the AA display
- Start/Stop OBD2 connection controls
- Trip history (full list with distance, energy, SOC delta, duration)
- Quick-access FAB to trip history from the main screen
- Settings: vehicle profile, units, ABRP token, screen wake lock, live diagnostics

### ABRP Integration (optional)
Enter your [ABRP](https://abetterrouteplanner.com) user token in Settings and AutoVakt streams live telemetry (SOC, speed, power, voltage, current, battery temp) to ABRP for real-time range estimation. The Settings Diagnostics panel shows last send time, send count, and any errors.

### OBD2 Bridge (optional)
A local TCP server on port 35000 that emulates a WiFi ELM327 adapter. Enable it in Settings to let **Car Scanner ELV/OBD2**, **Torque Pro**, or any other OBD2 app share your adapter connection while AutoVakt holds the Bluetooth link. Diagnostics show active client count.

---

## Hardware

| Adapter | Protocol | Status |
|---------|----------|--------|
| OBDLink MX+ | Bluetooth Classic (RFCOMM) | ✅ Fully supported |
| OBDLink CX | Bluetooth LE (GATT) | ✅ Supported |
| Generic ELM327 clone | Bluetooth Classic or BLE | ✅ Standard OBD2 PIDs |
| Any WiFi ELM327 | TCP | ❌ Not planned |

**Minimum Android**: API 26 (Android 8.0)  
**Target Android**: API 36  
**Car App Library**: 1.4.0

---

## Vehicle Support

80+ bundled JSON profiles across EV, PHEV, and ICE powertrains. Profile files live in `app/src/main/assets/profiles/`. The formula syntax is Torque Pro–compatible.

### Sample Supported Vehicles
Chevrolet Bolt EUV / EV · Tesla Model 3 / Y / S / X · Nissan LEAF / Ariya · Hyundai IONIQ 5 / 6 · Kia EV6 / EV9 · Ford Mustang Mach-E · Rivian R1T / R1S · Toyota bZ4X · VW ID.4 · BMW i3 / iX · and many more — see `assets/profiles/`.

### Profile JSON Format

```json
{
  "id": "chevy_bolt_euv_2023",
  "make": "Chevrolet",
  "model": "Bolt EUV",
  "year": 2023,
  "powertrain": "EV",
  "vinPatterns": ["1G1FZ", "1G1FY"],
  "initCommands": ["ATH1", "ATCAF0", "ATAL", "ATSP6"],
  "pids": [
    {
      "name": "State of Charge",
      "shortName": "SOC",
      "modeAndPid": "2202BC",
      "equation": "(A*256+B)/512",
      "header": "7E4",
      "units": "%",
      "nonLinearMap": [[9,0],[18,10],[27,20],[44,40],[61,60],[79,80],[96.5,100]]
    }
  ]
}
```

PID shortnames that map to core UI slots: `SOC`, `PWR`, `SPEED`, `RPM`, `HV_V`, `HV_I`, `BATT_TEMP_MAX`, `FUEL_LEVEL`.  
Equations use Torque Pro byte syntax: `A`, `B`, `C`… with `+`, `-`, `*`, `/`, `()`.

---

## Architecture

```
AutoVakt
├── OBD2 Layer
│   ├── ElmBluetoothTransport      — RFCOMM socket, 15s watchdog reconnect
│   ├── ElmBleTransport            — BLE GATT transport
│   ├── ElmCommandQueue            — serial dispatcher; raw traffic → bridge SharedFlow
│   ├── GmProtocolHandler          — VIN discovery, GM Mode 22 ECU headers
│   └── VaktBridgeServer           — TCP port 35000, emulates WiFi ELM327
│
├── Data Layer
│   ├── OBD2Repository             — polling loop, StateFlow<AutoVaktLiveData>
│   ├── AbrpReporter               — ABRP telemetry sender, StateFlow<AbrpSendStatus>
│   ├── VehicleProfileHub          — JSON profile loader (assets/ + filesDir/)
│   ├── VehicleProfileManager      — active profile + unit system preferences
│   ├── TripRepository             — Room DB CRUD (TripEntity, TripDao)
│   └── AutoVaktLiveData           — telemetry snapshot (SOC, power, speed, temps…)
│
├── Android Auto
│   ├── AutoVaktCarAppService      — CarAppService entry point (IOT category)
│   ├── AutoVaktSession            — session lifecycle
│   ├── DashboardScreen            — TabTemplate (3 tabs) or fallback ListTemplate
│   ├── TripScreen                 — trip history screen (ScreenManager navigation)
│   ├── MediaRemoteManager         — launches active media app from AA
│   └── AutoVaktMediaBrowserService — MediaBrowserService mini-player (Coolwalk 1/3 slot)
│
├── Phone UI
│   ├── MainActivity               — dashboard preview + service controls + trip FAB
│   ├── SettingsActivity           — profile picker, ABRP, bridge, diagnostics, wake lock
│   └── HistoryActivity            — full trip history (RecyclerView)
│
└── Service
    └── OBD2ForegroundService      — sticky foreground service, holds BT connection
```

**Data flow**: `OBD2ForegroundService` → `OBD2Repository.start()` → polls every ~2s → `StateFlow<AutoVaktLiveData>` → consumed by `DashboardScreen` (AA tabs), `AutoVaktMediaBrowserService` (mini-player bitmaps), `MainActivity` (phone preview), `TripRepository` (DB writes), and `AbrpReporter` (ABRP every 5s).

---

## Setup

### Install from GitHub Releases
Download the latest `autovakt-*.apk` from the [Releases page](https://github.com/rogerneumann/autovakt/releases) and sideload it, or get it from the Play Store once available.

### Build from Source
```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/autovakt-*-debug.apk
```
Requires JDK 17+. The Gradle wrapper is included.

### First Run
1. Grant **Bluetooth Connect**, **Bluetooth Scan**, and **Notifications** permissions
2. Open Settings → select your vehicle profile
3. Tap **Connect** → pick your OBD2 adapter from the scanner
4. (Optional) Enter ABRP user token in Settings for range estimation
5. For Android Auto: enable **Unknown Sources** in Android Auto developer settings, then find AutoVakt in the AA launcher

---

## Gradle Tasks

| Task | Description |
|------|-------------|
| `assembleDebug` | Build debug APK (auto-publishes to GitHub Releases) |
| `bundlePlayStore` | Build signed release AAB for Play Store upload |
| `runMockAbrpServer` | Start a local ABRP mock server for integration testing |
| `test` | Run unit tests (JVM, no device needed) |

---

## Contributing / Adding Profiles

1. Create `app/src/main/assets/profiles/<vehicle_id>.json`
2. Required fields: `id`, `powertrain` (`EV`/`PHEV`/`ICE_GAS`/`ICE_DIESEL`), `pids`
3. Test with `./gradlew test` — profile loading is unit-tested
4. Submit a PR

Community PID references:
- [allev.info/boltpids](https://allev.info/boltpids) — Bolt EUV PID list
- [chevybolt.org forums](https://chevybolt.org) — community calibration data
- Sean Graham's Torque Pro PID file — widely shared in Bolt community

---

## License

MIT License — see [LICENSE](LICENSE) for details.

---

## Privacy

AutoVakt collects no personal data. Vehicle telemetry stays on your device unless you enable the optional ABRP integration. See the full [Privacy Policy](https://rogerneumann.github.io/autovakt/privacy-policy.html).
