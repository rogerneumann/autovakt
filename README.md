# AutoVakt — Android Auto OBD2 Dashboard

A vehicle telemetry app for Android that puts live OBD2 data into Android Auto — both as a persistent mini-player strip alongside Maps and as a full-screen canvas dashboard. Designed for any ELM327-compatible vehicle via JSON profiles, with the 2023 Chevrolet Bolt EUV as the primary target.

---

## What It Does

### Android Auto — Mini-Player Strip
AutoVakt registers as a `MediaBrowserService` to occupy the persistent 1/3 media slot in Android Auto (Coolwalk). The panel shows live telemetry instead of song info and stays visible while you navigate. Three swappable views, cycled with the skip buttons:

| View | Shows |
|------|-------|
| **EV** | SOC %, power kW, SOC arc gauge |
| **Battery** | Max/min pack temps |
| **Trip** | Distance, energy used |

### Android Auto — Full-Screen Dashboard
A canvas-drawn gauge display (`NavigationTemplate` with `SurfaceCallback`). Renders a SOC arc, bi-directional power bar, and connection status dot. Adapts its action strip to the head unit's display width (Wide / Full / Narrow).

### Phone UI
A local dashboard preview mirroring the AA GaugeRenderer, control buttons for the OBD2 service, Settings, and Trip History.

### AutoVakt Bridge
A local TCP server on port 35000 that emulates a WiFi ELM327 adapter. Apps like **ABRP**, **Torque Pro**, and **Car Scanner** can connect to it and get raw OBD2 data while AutoVakt holds the Bluetooth connection. No massaging — raw command → adapter → raw response.

---

## Hardware

| Adapter | Protocol | Works? |
|---------|----------|--------|
| OBDLink MX+ | Bluetooth Classic (RFCOMM) | ✅ Full support (GM enhanced PIDs) |
| OBDLink CX | Bluetooth LE (GATT) | 🚧 BLE transport in progress |
| Generic ELM327 clone | Bluetooth Classic or BLE | ⚠️ Standard OBD2 PIDs only (no Mode 22) |
| Any WiFi ELM327 | TCP | ❌ Not yet (planned) |

**Minimum Android**: API 26 (Android 8.0)  
**Minimum Car App Library**: 1.4.0

---

## Current Status

> **Alpha — not production ready.** Core architecture is solid but several bugs are being addressed before any real-hardware use.

### ✅ Working
- ELM327 Bluetooth Classic transport with 15s watchdog reconnect
- Sequential command queue with per-command timeouts
- GM-specific protocol handler: VIN discovery (Mode 09), SOC / HV voltage / current (Mode 22)
- JSON vehicle profile system (`assets/profiles/`) with Torque Pro–compatible formula parser
- Android Auto full-screen canvas dashboard (SurfaceCallback)
- Android Auto mini-player (3 swappable bitmap views)
- TCP bridge server (port 35000) for third-party apps
- Room database (trip entities + DTC log schema)
- Demo mode (5-tap easter egg on AutoVakt title — no dongle needed)
- Phone UI: dashboard preview, Settings, Trip History

### 🚧 In Progress (see [Roadmap](#roadmap))
- Crash on launch being diagnosed (Block 1 — permissions + Hilt fixes committed)
- BLE GATT transport for cheap ELM327 adapters (Block 3)
- In-app Bluetooth device scanner (Block 4)
- Profile schema: `initCommands`, `nonLinearMap`, `vinPatterns` (Block 5)
- Corrected Bolt EUV PID formulas + 3 new bundled profiles (Block 6)
- JSON-only polling (retire hardcoded GM path) (Block 7)
- Auto trip start/end + persistence across reconnects (Block 9)

### ❌ Known Bugs
- App crashes immediately on launch (likely Hilt component initialization — Block 1 in progress)
- `bolt_euv.json` SOC formula uses 1 byte instead of 2 (`A*100/255` → should be `(A*256+B)/512`)
- Bolt EUV voltage/current scale factors wrong (`/10` vs `*0.0625`)
- GM ELM327 init commands missing (`ATH1`, `ATCAF0`, `ATAL`, `ATSP6`)
- Bolt EUV SOC non-linear mapping not applied (raw % ≠ dashboard %)
- `startNewTrip()` never called — trip stats not persisted to DB
- 30s DB persist uses unreliable modulo timing

---

## Vehicle Support

Vehicle capabilities are defined by JSON profiles in `app/src/main/assets/profiles/`. The formula syntax is Torque Pro–compatible (`(A*256+B)/4`, `A-40`, etc.).

### Bundled Profiles (planned)
| Profile | Status |
|---------|--------|
| `chevy_bolt_euv_2023` | ⚠️ Formulas need correction (Block 6) |
| `generic_obd2` | 🚧 Planned (Block 6) |
| `nissan_leaf` | 🚧 Planned (Block 6) |
| `tesla_dongle` | 🚧 Planned (Block 6) |

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

> **Profile fields `vinPatterns`, `initCommands`, and `nonLinearMap` are not yet implemented** — coming in Blocks 5–7. The schema above reflects the target format.

You can import community profiles in Settings (planned Block 10): paste a URL or pick a local file. The format is compatible with Torque Pro CSV exports (with a thin wrapper).

---

## Architecture

```
AutoVakt
├── OBD2 Layer
│   ├── ElmBluetoothTransport   — RFCOMM socket + 15s watchdog reconnect
│   ├── ElmCommandQueue         — serial dispatcher; raw traffic → bridge SharedFlow
│   ├── GmProtocolHandler       — VIN discovery, GM Mode 22 ECU headers
│   └── VaktBridgeServer        — TCP port 35000, emulates WiFi ELM327
│
├── Data Layer
│   ├── OBD2Repository          — polling loop, StateFlow<VaktLiveData>
│   ├── VehicleProfileHub       — loads JSON profiles from assets/ + filesDir/
│   ├── VehicleProfileManager   — SharedPreferences: active profile + unit system
│   ├── TripRepository          — Room CRUD for trip sessions
│   └── VaktLiveData            — telemetry snapshot (SOC, power, speed, temps…)
│
├── Android Auto
│   ├── VaktCarAppService       — CarAppService entry point (IOT category)
│   ├── DashboardScreen         — SurfaceCallback canvas dashboard
│   ├── MultiPaneLayoutManager  — adapts action strip to Wide/Full/Narrow display
│   ├── GaugeRenderer           — Canvas: SOC arc, power bar, status dot
│   └── VaktMediaBrowserService — MediaBrowserService mini-player (1/3 slot)
│
├── Phone UI
│   ├── MainActivity            — dashboard preview + service controls
│   ├── SettingsActivity        — vehicle profile + unit system picker
│   └── HistoryActivity         — trip history cards (RecyclerView)
│
└── Service
    └── OBD2ForegroundService   — sticky foreground service, connectedDevice type
```

**Data flow**: `OBD2ForegroundService` starts → `OBD2Repository.start()` → polls OBD2 every ~2s → emits `VaktLiveData` via `StateFlow` → consumed by `DashboardScreen` (AA canvas), `VaktMediaBrowserService` (mini-player bitmaps), `DashboardView` (phone preview), and `TripRepository` (DB writes every 30s).

**Bridge flow**: Third-party app → TCP port 35000 → `VaktBridgeServer` → `ElmCommandQueue` (same queue as AutoVakt) → raw response back to client.

---

## Setup

### 1. Build
```
./gradlew assembleDebug
```
Requires JDK 17+. The Gradle wrapper is included.

### 2. Install
```
adb install app/build/outputs/apk/debug/app-debug.apk
```
Or sideload via Android's "Install from unknown sources".

### 3. Grant Permissions
On first launch, grant:
- **Bluetooth Connect** — required to connect to the OBD2 adapter
- **Bluetooth Scan** — required to discover nearby adapters
- **Notifications** — required for the foreground service indicator

### 4. Connect an Adapter
*(In-app device scanner is in progress — Block 4. For now, pair your adapter in Android system Bluetooth settings.)*

Tap **Start OBD2** to start the foreground service. The service currently hard-codes demo mode — real Bluetooth connection requires completing Block 4 (device picker).

### 5. Android Auto
1. Enable **Unknown Sources** in Android Auto developer settings
2. The app registers two AA entry points:
   - **Full-screen dashboard** — find "AutoVakt" in the AA launcher (IOT category)
   - **Mini-player** — select AutoVakt as the active media source in the AA media picker

---

## Roadmap

Work is broken into small independent blocks. Blocks 2, 5, and 9 can run in parallel. Blocks 3 and 4 depend on Block 2; Blocks 6, 7, and 8 depend on Block 5.

| Block | Description | Status |
|-------|-------------|--------|
| **1** | Crash logging, runtime permissions, Room migration fallback | ✅ Done |
| **2** | `OBD2Transport` interface + rename Classic transport | 🔲 Ready |
| **3** | BLE GATT transport (NUS UUID + fallbacks) | 🔲 Ready after Block 2 |
| **4** | In-app device scanner (Classic + BLE combined list) | 🔲 Ready after Block 2 |
| **5** | Profile schema: `initCommands`, `nonLinearMap`, `vinPatterns` | 🔲 Ready |
| **6** | Fix `bolt_euv.json` formulas + Generic OBD2 / Leaf / Tesla profiles | 🔲 Ready after Block 5 |
| **7** | `OBD2Repository` refactor: JSON-only polling, auto trip start, fix 30s persist | 🔲 Ready after Block 5 |
| **8** | VIN → profile auto-match (unambiguous only, else prompt) | 🔲 Ready after Blocks 5+7 |
| **9** | Trip persistence across reconnects + wire "New Trip"/"Stop Trip" buttons | 🔲 Ready |
| **10** | Profile import UI (URL or local file) | 🔲 Ready after Block 5 |

Full block specifications (files, exact changes, verification steps) are in the project plan.

---

## Performance Targets

| Metric | Target |
|--------|--------|
| OBD2 round-trip (request → UI update) | < 350ms |
| Mini-player refresh rate | ≥ 2 Hz |
| Watchdog reconnect time | ≤ 15s |
| Bridge passthrough latency | < 50ms |
| AA canvas frame rate | 60 fps |
| AA launch to functional | < 10s |

---

## Contributing / Adding Profiles

Profile files live in `app/src/main/assets/profiles/`. To add support for a new vehicle:

1. Create `<vehicle_id>.json` following the format above
2. Required fields: `id`, `powertrain` (`EV`/`PHEV`/`ICE_GAS`/`ICE_DIESEL`), `pids`
3. PID shortnames that map to core UI fields: `SOC`, `PWR`, `SPEED`, `RPM`, `HV_V`, `HV_I`
4. Equations use Torque Pro syntax: `A`, `B`, `C`… are data bytes; supports `+`, `-`, `*`, `/`, `()`
5. Add `vinPatterns` for auto-detection and `initCommands` for adapter setup (e.g. GM enhanced PIDs need `["ATH1","ATCAF0","ATAL","ATSP6"]`)

Community PID references:
- [allev.info/boltpids](https://allev.info/boltpids) — Bolt EUV PID list
- [chevybolt.org forums](https://chevybolt.org) — community thread with calibration data
- Sean Graham's Torque Pro PID file — widely shared in Bolt community

---

## License

Not yet licensed. All rights reserved pending first stable release.
