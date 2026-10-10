# Syrak ScooterLab

A professional-grade Android application for multi-brand electric-scooter
diagnostics, RAM tuning (the "German Maneuver") and firmware management —
supporting Ninebot, Segway, Xiaomi and Navee controllers.

Built with Kotlin 2.0, Jetpack Compose, and a dark industrial / cyberpunk design
language. No emojis. No amateur layouts. Just sharp, production code.

---

## What's in this drop (Phase 1 — Foundation)

| Area | Deliverable |
|------|-------------|
| **Design system** | Cyberpunk palette, monospace type scale, 4pt spacing grid, Material 3 dark theme |
| **Iconography** | 14 custom vector drawables (scooter, gauge, battery, shield, chip…) |
| **BLE engine** | `BleManager`: filtered scanning, timeout-bounded GATT bring-up, MTU-aware serialized write pump, notify handling |
| **Protocol** | CRC-16/XMODEM, Ninebot frame codec, register map, AES auth scaffold, request/response correlator |
| **Features** | German Maneuver (volatile RAM patch + revert), live telemetry, emergency killswitch |
| **UI** | Live dashboard: instrument gauge, power grid, pack-health panel, safety + tuning panels, diagnostics console |
| **Tests** | Protocol unit tests (CRC canonical vector, frame round-trip, corruption rejection) |

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the full engineering charter
and roadmap. See [`design/dashboard_preview.html`](design/dashboard_preview.html)
for a live, interactive mockup of the dashboard.

---

## Project layout

```
.
├── app/                        Android application module
│   └── src/main/java/com/syrak/scooterlab/
│       ├── core/ble/           BLE transport
│       ├── core/protocol/      Ninebot serial protocol
│       ├── feature/            tuning · telemetry · safety
│       ├── data/               repository facade
│       └── ui/                 theme · components · dashboard
├── design/                     HTML design preview + render
├── docs/                       Architecture & roadmap
├── gradle/libs.versions.toml   Version catalog
└── settings.gradle.kts
```

---

## Build

Requires **JDK 17** and **Android SDK 35**.

```bash
./gradlew :app:assembleDebug       # build debug APK
./gradlew :app:testDebugUnitTest   # run unit tests
```

Open the folder in Android Studio (Ladybug or newer) and run on a device with
Bluetooth LE. `minSdk 26`, `targetSdk 35`.

---

## ⚠️ Safety & legal notice

This tool performs **safety-critical writes** to motor-controller memory. The
register map ships as a *reference scaffold* and **must be validated against the
target firmware before any write on real hardware**. Raising a scooter's speed
limit above its factory regional cap is illegal on public roads in many
jurisdictions (including Germany) and voids type approval. Use on closed courses
and for diagnostics only. The killswitch is electronic containment — it is **not**
a substitute for the physical brake.

---

## Roadmap

- **Phase 1 — Foundation** ✅ *(this drop)*
- **Phase 2 — Diagnostics & Firmware**: register inspector, resumable DFU flashing, per-model profiles
- **Phase 3 — Depth**: multi-brand protocol adapters, session recording/replay, battery analytics
- **Phase 4 — Hardening**: persistent sessions, CI, accessibility, crash-free instrumentation
