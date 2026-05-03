# Bolt OBD2 — Android Auto EV Dashboard

Android app for the 2023 Chevrolet Bolt EUV that shows live EV data in the Android Auto mini-player panel (persistent alongside Google Maps) and a full-screen dashboard.

## What It Does

- **Persistent mini-player panel** in Android Auto (visible while Maps is running) showing SOC, power, and efficiency — uses the same strip where your music info normally appears
- **Full-screen dashboard** you can switch to for detailed battery and trip data
- Connects to your car's OBD2 port over Bluetooth via ELM327-compatible adapter

## Hardware Required

**OBDLink MX+ or OBDLink CX** (required — generic ELM327 adapters do not support GM proprietary enhanced protocols)

- OBDLink MX+: ~$100, Bluetooth Classic, widest GM protocol support
- OBDLink CX: ~$60, Bluetooth LE, slightly newer

Generic $10–30 ELM327 clones will connect but cannot access GM-specific PIDs (true HV SOC, cell voltages, pack current). The app will detect this and warn you.

## Data Displayed

| Metric | Source |
|--------|--------|
| State of Charge (SOC) | GM enhanced PID (non-linear mapping applied) |
| HV Pack Power (kW) | Calculated: voltage × current |
| Instant efficiency (mi/kWh) | Speed ÷ power, filtered below 5 mph |
| Average efficiency (trip) | Accumulated distance ÷ accumulated energy |
| Battery temps (max/min) | GM enhanced PID, multiple sensors |
| Charging status | GM enhanced PID, charge rate |
| Trip energy stats | Distance, kWh used, elapsed time |

## How the Mini-Player Panel Works

Android Auto's persistent mini-player strip is driven by a `MediaBrowserService`. This app registers as a media source and updates the "now playing" metadata with OBD2 values every ~2 seconds. The panel has 3 swappable view modes (cycled via the action buttons):

| Mode | Shows |
|------|-------|
| **EV** | `SOC: 87% · 32 kW` / `4.2 mi/kWh · 3.8 avg` + SOC gauge arc |
| **Battery** | Temps, voltage, current + thermometer graphic |
| **Trip** | Distance, kWh used, avg efficiency + bar chart |

Your music continues playing — only the visual mini-player changes to show OBD2 data when you select this app as the active media source in Android Auto.

When a navigation turn-by-turn banner appears and compresses the panel to ~1/6 size, the SOC % stays large and legible as the primary element.

## Android Auto Setup

**WiFi mode (recommended — works with both wired and wireless AA):**
1. On the OBDLink MX+, enable WiFi mode (creates its own hotspot)
2. Connect your phone to the OBDLink's WiFi network
3. Open app → tap Connect → WiFi → leave IP at default (`192.168.0.10`)
4. Connect to car via Android Auto (USB or wireless)
5. In Android Auto, open the media picker and select "Bolt OBD2"
6. Mini-player shows live data alongside Maps — use action buttons to switch view modes

**Bluetooth mode (alternative, wired AA only):**
1. Pair OBDLink adapter in phone Bluetooth settings
2. Open app → tap Connect → Bluetooth → select your OBDLink
3. Use wired (USB) Android Auto — avoids Bluetooth + wireless AA conflict

## Status

This project is in planning/design phase. Source code coming in a new dedicated repo.

See `PLAN.md` for full architecture and implementation plan.
See `bolt-euv-pids.md` for Bolt EUV OBD2 PID reference.
