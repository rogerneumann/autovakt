# Chevrolet Bolt EUV — OBD2 PID Reference

Hardware required: OBDLink MX+ or OBDLink CX for GM enhanced PIDs.
Community source: allev.info/boltpids, chevybolt.org forums.

---

## ELM327 Protocol Setup for GM Enhanced PIDs

```
ATH1      -- headers ON (required — GM PIDs need ECU addressing)
ATCAF0    -- CAN auto-formatting off
ATAL      -- allow long messages (some GM frames are >7 bytes)
ATSP6     -- or ATSP0 for auto; GMLAN SWCAN is protocol 6 on some modules
```

---

## State of Charge (SOC)

### Raw vs Displayed SOC

The raw SOC from the BMS is NOT the displayed battery percentage. The relationship is non-linear with an intentional buffer at both ends:

| Displayed % | Raw SOC % (approx) |
|-------------|-------------------|
| 0% | ~9% |
| 10% | ~18% |
| 20% | ~27% |
| 40% | ~44% |
| 60% | ~61% |
| 80% | ~79% |
| 100% | ~96.5% |

Linear approximation: `displayed = 1.086 * raw - 5.857` (inaccurate at 0% and 100% ends).

For production use, interpolate between calibration points. More precise calibration data available in the chevybolt.org forum thread (link below).

### SOC PID

- **Mode/PID**: `22 02BC` (GM enhanced Mode 22, PID 0x02BC) — BMS module
- **Header**: `7E4` (BMS ECU address for Bolt)
- **Response length**: variable — SOC is typically in bytes 3–4 of the response
- **Formula**: `raw_soc = (byte3 * 256 + byte4) / 512.0` → gives raw % 0–100
- **Then apply lookup table** → displayed %

Note: Exact byte offsets vary by model year and BMS firmware. Validate against known SOC from the car's own display on first use.

---

## HV Battery Pack Voltage

- **Mode/PID**: `22 02BD`
- **Header**: `7E4`
- **Formula**: `voltage = (byte3 * 256 + byte4) * 0.0625` → volts
- **Typical range**: 280–420V depending on SOC

---

## HV Battery Pack Current

- **Mode/PID**: `22 02BE`
- **Header**: `7E4`
- **Formula**: `current_raw = (byte3 * 256 + byte4)` as signed 16-bit integer; `current_amps = current_raw * 0.0625`
- **Sign convention**: Positive = discharging (driving), Negative = charging (regen or plugged in)
- **Power**: `power_kw = voltage * current / 1000`

---

## Battery Temperature

- **Mode/PID**: `22 02BF` (or nearby — varies)
- **Header**: `7E4`
- **Response**: Multiple temperature bytes in one frame
  - Byte 3: coolant/module inlet temp
  - Byte 4: max cell temp
  - Byte 5: min cell temp
- **Formula**: `temp_c = byte - 40` (standard GM offset)
- **Useful derived values**: temp spread = max - min (indicates pack balance health)

---

## Vehicle Speed (Standard OBD2)

Available on any ELM327, no OBDLink required:

- **Mode/PID**: `01 0D`
- **Formula**: `speed_kmh = byte3`; `speed_mph = speed_kmh * 0.621371`

---

## Charging Status

- **DC fast charge rate**: GM enhanced PID, varies by year — check chevybolt.org thread
- **Charge mode detection**: Monitor current sign (negative) and magnitude
  - L1 charging: ~1.4 kW
  - L2 charging: ~7.2 kW (or 11.5 kW on Bolt EUV with 11.5 kW onboard charger)
  - DCFC: 50–78 kW depending on SOC/temp

---

## Polling Strategy

Poll these in order each cycle (~1s target, accept 3–5s with 5 commands at 500ms each):

1. `StandardSpeedCommand` (Mode 01) — most frequent, standard PID, fast
2. `GmSocCommand` — primary data point
3. `GmHvVoltageCommand` — needed for power calc
4. `GmHvCurrentCommand` — needed for power calc
5. `GmBatteryTempsCommand` — can poll less frequently (every 5s)

---

## References

- allev.info/boltpids — comprehensive Bolt PID list with formulas
- chevybolt.org/threads/chevrolet-bolt-obd2-pids.26666 — 84+ page community thread
- OBDLink AT command reference — obdlink.com/support/obdlink-at-command-reference
- Sean Graham's Torque Pro PID file — widely shared in Bolt community, good calibration data

---

## Notes on PID Accuracy

- PID addresses listed here are community-sourced and accurate for the 2022–2023 Bolt EUV on common BMS firmware
- Always cross-reference raw values against the car's built-in display on first connection
- GM may change PID assignments between firmware updates (though rare for battery data)
- Some PIDs behave differently during charging vs driving — test both modes
