# Syrak ScooterLab — Build Roadmap (todo.md)

## Sprint 0 — Foundation & Design System
- [x] Scaffold Gradle project (Kotlin DSL, version catalog, AGP/Kotlin/Compose)
- [x] AndroidManifest with full BLE permission matrix (API 26–35)
- [x] Cyberpunk design tokens (colors, dimens, type, shapes) — Compose + XML
- [x] Custom vector drawables (scooter, battery, bolt, speed, shield, chip, etc.)
- [x] Material3 dark theme wiring (SyrakTheme)
- [x] Adaptive launcher icon

## Sprint 1 — BLE Core Engine
- [x] BleConstants (service/characteristic UUIDs, MTU, timeouts)
- [x] BleModels (DiscoveredScooter, ConnectionState, GattEvent)
- [x] BlePermissions runtime gate (31+ vs legacy)
- [x] BleManager (scan filters, connect, GATT queue, notify flow, MTU)
- [x] BleSessionService (foreground service for long operations)

## Sprint 2 — Protocol & Packet Crafting
- [x] Crc16 (CCITT/XMODEM, verified vs 0x31C3)
- [x] NinebotFrame (encode/decode, length, addressing, CRC validation)
- [x] NinebotProtocol (read/write register, LE decoders)
- [x] RegisterMap (documented, firmware-verifiable constants + write allow-list)
- [x] NinebotAuth (AES-128-ECB challenge/response scaffold)
- [x] NinebotSession (request/response correlator)

## Sprint 3 — Features
- [x] German Maneuver (RAM patch sequence + commit + verify + revert)
- [x] Telemetry poller (speed, voltage, cells, temp, odometer) + simulator
- [x] Emergency safety killswitch
- [ ] Firmware manager (DFU/OTA pipeline) — Phase 2

## Sprint 4 — UI
- [x] Dashboard (live telemetry gauge + panels)
- [x] Component library (NeonCard, GaugeRing, StatTile, StatusChip, SyrakButton)
- [ ] Scan/Connect dedicated screen — Phase 2
- [ ] Register inspector / hex console — Phase 2

## Deliverables This Turn
- [x] Full project scaffold (57 files)
- [x] Design system + 14 vector drawables
- [x] BLE + protocol core
- [x] Dashboard Compose UI
- [x] High-fidelity HTML design preview + rendered PNG
- [x] Architecture & roadmap document
- [x] Protocol unit tests
- [x] Packaged project archive
