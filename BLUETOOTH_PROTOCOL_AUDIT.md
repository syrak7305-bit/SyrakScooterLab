# PHASE 16 – Bluetooth- & Protokoll-Audit

Stand: nach Phase 15 (Basis `main` @ `f2c908a`), Branch `feat/phase16-ble-reliability`.

Dieses Dokument ist bewusst **nüchtern und nachvollziehbar**. Es beantwortet die
Frage aus dem Phase-16-Auftrag: *Warum existieren unterschiedliche UUID-Definitionen,
welche sind belegbar, welche sind Platzhalter, und welcher Adapter nutzt welche?*

Grundsatz: **Es werden keine UUIDs erfunden und keine Hardware-Tests behauptet.**
Eine UUID, die im Code steht, ist ein *Projektwert* – kein Beweis, dass ein Roller
sie tatsächlich verwendet.

---

## 1. Befund: Zwei parallele BLE-Stacks

Im Projekt existieren zwei Generationen derselben App nebeneinander:

| Stack | Einstiegspunkt | Genutzt von | Status |
|-------|----------------|-------------|--------|
| **AKTIV** | `com.syrak.scooterlab.BleEngine` + `ScooterProtocolAdapter` / `NinebotAdapter` / `NaveeGt3MaxAdapter` | `MainActivity` (die reale UI) | produktiv |
| **LEGACY** | `com.syrak.scooterlab.core.ble.BleManager` + `BleConstants` + `BleSessionService` + `BleModels` | `AppContainer` / `SyrakApp` / `DashboardViewModel` (Compose-Pfad, **nicht** die reale UI) | totes Gleis |

Daraus entstand der Widerspruch: `BleConstants` (Legacy) definierte Nordic-UART- und
Xiaomi-UUIDs, während `BleEngine` (Aktiv) Navee-/Ninebot-UUIDs verwendete. Beide
wurden im Code als „die“ Transport-UUIDs behandelt.

**Auflösung in Phase 16:** Es wird *kein* Gewinner geraten. Stattdessen gibt es jetzt
**eine** zentrale Registry `BleUuids.kt`, in der jede UUID mit Herkunft, Rolle und
Nutzung dokumentiert ist. `BleConstants` delegiert nur noch dorthin. Die offene
Frage (welche UUIDs ein GT3 Max real nutzt) bleibt sichtbar und ungelöst, bis ein
echter Hardware-Test vorliegt.

---

## 2. Zentrale Registry (`BleUuids.kt`)

Herkunftsklassen (`UuidProvenance`):

- `DOCUMENTED` – durch externe, überprüfbare Spezifikation belegt.
- `PROJECT_CONSTANT` – im Projekt gesetzter Wert; plausibel, aber nicht extern belegt.
- `LEGACY_UNCONFIRMED` – aus dem alten Stack übernommen, unbestätigt.
- `PLACEHOLDER` – ausdrücklich Platzhalter.

`confirmedOnHardware` ist derzeit für **alle** Einträge `false`.

### 2.1 Definierte UUIDs

| Key | UUID | Rolle | Familie | Herkunft | Genutzt von |
|-----|------|-------|---------|----------|-------------|
| `cccd` | `00002902-0000-1000-8000-00805f9b34fb` | Descriptor | generisch | **DOCUMENTED** (Bluetooth-SIG CCCD) | BleEngine |
| `navee.service` | `0000ffe0-0000-1000-8000-00805f9b34fb` | GATT-Service | Navee/Brightway | PROJECT_CONSTANT | BleEngine, NaveeGt3MaxAdapter |
| `navee.char.main` | `0000ffe1-0000-1000-8000-00805f9b34fb` | Notify-Char | Navee/Brightway | PROJECT_CONSTANT | BleEngine, NaveeGt3MaxAdapter |
| `navee.char.alt` | `0000ffe2-0000-1000-8000-00805f9b34fb` | Write-Char | Navee/Brightway | PROJECT_CONSTANT | BleEngine, NaveeGt3MaxAdapter |
| `ninebot.service` | `0000e003-0000-1000-8000-00805f9b34fb` | GATT-Service | Ninebot/Xiaomi | PROJECT_CONSTANT | BleEngine, NinebotAdapter |
| `ninebot.char.write` | `0000fff1-0000-1000-8000-00805f9b34fb` | Write-Char, **Kandidat auch Notify** (Rolle unbelegt) | Ninebot/Xiaomi | PROJECT_CONSTANT | BleEngine, NinebotAdapter |
| `ninebot.char.write.alt` | `0000fff2-0000-1000-8000-00805f9b34fb` | Write-Char (alt), **Kandidat auch Notify** (Rolle unbelegt) | Ninebot/Xiaomi | PROJECT_CONSTANT | BleEngine, NinebotAdapter |
| `legacy.uart.service` | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | GATT-Service | Nordic UART | LEGACY_UNCONFIRMED | BleConstants (Legacy) |
| `legacy.uart.write` | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` | Write-Char | Nordic UART | LEGACY_UNCONFIRMED | BleConstants (Legacy) |
| `legacy.uart.notify` | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` | Notify-Char | Nordic UART | LEGACY_UNCONFIRMED | BleConstants (Legacy) |
| `legacy.xiaomi.service` | `0000fe95-0000-1000-8000-00805f9b34fb` | GATT-Service | Xiaomi | LEGACY_UNCONFIRMED | BleConstants (Legacy) |

### 2.2 Adapter-Zuordnung

| Adapter | Protokoll | Service-UUIDs | Write-Char | Notify-Char | Hardware bestätigt |
|---------|-----------|---------------|------------|-------------|--------------------|
| `NinebotAdapter` (`ninebot-generic`) | NINEBOT | `0000e003` | `0000fff1` / `0000fff2` | `0000fff1` / `0000fff2` (Kandidaten, Rolle unbelegt) | **nein** |
| `NaveeGt3MaxAdapter` (`navee-gt3max`) | NAVEE_BRIGHTWAY | `0000ffe0` | `0000ffe2` | `0000ffe1` | **nein** |
| Legacy `BleManager` | – | `6e400001` / `fe95` | `6e400002` | `6e400003` | **nein** |

### 2.3 Phase-16.1-Korrektur: Rollen statt Doppelbehauptung

Vor Phase 16.1 fuehrten `NinebotAdapter` und `NaveeGt3MaxAdapter` **dieselben
UUIDs gleichzeitig als Write- und als Notify-Characteristic**. Beim Navee war
das nachweislich falsch (Brightway: FFE1 = Notify, FFE2 = Write). Beim Ninebot
liess sich die reale Rolle von FFF1/FFF2 ohne GATT-Mitschnitt nicht entscheiden.

Die Korrektur erfindet **keine** Rolle:

- `UuidDefinition` traegt jetzt `candidateRoles`. FFF1/FFF2 sind dort als
  Kandidaten fuer **Write UND Notify** registriert (`roleAmbiguous = true`),
  weil die Rolle unbelegt ist.
- `NaveeGt3MaxAdapter` leitet Write/Notify jetzt aus der Registry ab:
  Write = FFE2, Notify = FFE1 (eindeutig, wie im Brightway-Layout dokumentiert).
- `NinebotAdapter` leitet Write/Notify ebenfalls aus der Registry ab. Beide
  Listen enthalten FFF1/FFF2, aber nur weil die Registry diese Rollen
  ausdruecklich als **mehrdeutig** markiert – nicht als bewiesen.
- `confirmedOnHardware` bleibt fuer alle Scooter-UUIDs `false`.

Damit stimmen Registry und Adapter ueberein, und die offene Hardware-Frage ist
sichtbar statt versteckt.

---

## 3. Warum der Widerspruch (noch) nicht „gelöst“ ist

Es gibt zwei mögliche Erklärungen, und **keine ist durch echte Hardware-Daten belegt**:

1. **GT3 Max nutzt tatsächlich `0000ffe0/ffe1/ffe2`** (Brightway-Familie) – dann ist
   `BleConstants` schlicht veraltet/tot und die aktive Engine korrekt.
2. **GT3 Max nutzt einen anderen Transport** (z. B. Nordic-UART) – dann ist die aktive
   Engine falsch und die Legacy-Konstanten wären näher an der Realität.

Ohne einen realen GATT-Dump eines GT3 Max ist keine der beiden Aussagen beweisbar.
Deshalb:

- `BleUuids.transportContradictions()` liefert diese offene Frage als Text an die UI.
- `BleUuids.confirmedOnHardwareCount()` ist `0`.
- Die UI zeigt die Registry-Zusammenfassung statt einer falschen Gewissheit.

**Nächster notwendiger Schritt:** Ein echter Hardware-Test (siehe Hardware-Testmodus)
muss den GATT-Baum eines GT3 Max erfassen. Erst danach darf `confirmedOnHardware`
für die betroffenen Einträge auf `true` gesetzt werden.

---

## 4. Read-Pfad (Phase 16 §3)

Vor Phase 16 gab es **keinen** `onCharacteristicRead`-Pfad. `readOnlyTested` war in
der UI hart auf `false` gesetzt – ehrlich, aber Stufe 3/4 der Leiter waren unerreichbar.

Neu in `BleEngine`:

- `readCharacteristic(uuid, label, manual)` – startet genau einen Read.
- `onCharacteristicRead(...)` – wertet Status + Bytes aus.
- `ReadTracker` – genau **ein** Read gleichzeitig, Generation-gebunden.
- `READ_TIMEOUT_MS = 3000` – ohne Callback → `TIMEOUT` (kein Erfolg).
- `abortPendingRead(...)` – bei Disconnect/Reset → `DISCONNECTED`/`ABORTED`.
- `ReadPolicy.mayAutoRead(...)` – Auto-Read nur bei **bekanntem Modell UND
  verifizierter Hardware**. Unbekannte Geräte bleiben manuell.

Ein Read gilt **nur** bei `GATT_SUCCESS` und mindestens einem Byte als Erfolg.
Eine Read-Property im GATT-Baum ist ausdrücklich **kein** Read-Erfolg.

---

## 5. CompatibilityLadder (Phase 16 §4) – jetzt 7 Stufen

| # | Stufe | Eigener Nachweis |
|---|-------|------------------|
| 1 | `DETECTED` | Gerät erkannt |
| 2 | `GATT_DISCOVERED` | GATT-Struktur ermittelt |
| 3 | `READ_PROPERTY_FOUND` | mindestens eine lesbare Characteristic existiert |
| 4 | `READ_ONLY_TESTED` | **echter** Read mit Callback + Bytes |
| 5 | `TELEMETRY_VALIDATED` | plausible, frische Telemetrie |
| 6 | `WRITE_PROTOCOL_VALIDATED` | Schreibprotokoll bestätigt |
| 7 | `HARDWARE_VERIFIED` | Hardware verifiziert |

Jede Stufe braucht ihren eigenen Beleg; keine Stufe wird durch Erreichen der
Vorgänger automatisch freigeschaltet. Ein empfangener BLE-Frame bestätigt weder
Telemetrie noch ein funktionierendes Protokoll.

---

## 6. Firmware-Identität (Phase 16 §5)

Gefundener Fehler: `importSuggestion()` speicherte die *lokal ausgewählte* Datei
unter dem Dateinamen eines **fremden** Katalogpakets (`suggestion.packageId`). Damit
war die Zuordnung „Paketname → Datei“ nicht mehr nachvollziehbar.

Behoben durch `FirmwareImportGuard`: Übernahme nur, wenn

1. die Datei gültig geprüft ist,
2. das ausgewählte Katalogpaket **genau** dem Vorschlag entspricht,
3. der SHA-256 gegen einen hinterlegten Wert verifiziert ist,
4. der Modellbezug belegt ist.

Andernfalls wird die Übernahme mit ehrlicher Begründung abgelehnt.

---

## 7. Sicherheits-Audit (Phase 16 §7) – Kurzfassung

- **WriteGuard**: unbekannte Modelle → `BLOCKED_UNKNOWN_MODEL`, nicht verifizierte
  Hardware → `BLOCKED_HARDWARE_NOT_VERIFIED`. Read-only bleibt read-only.
- **Auto-Handshake**: `setAutoHandshakeAllowed` nur bei Modell erkannt **und**
  Hardware verifiziert (`AutoHandshakePolicy`). Kein Schreibbefehl nur wegen
  erkannter UUIDs.
- **Disconnect/Timeout**: `failPendingWrites(...)` verwirft wartende Writes,
  `abortPendingRead(...)` bricht Reads ab. Kein hängender Zustand.
- **Flash**: `FlashHonesty.hardFlashAllowed` verlangt gültige, hash-verifizierte,
  modellkompatible Firmware + bekannte Hardware + Verbindung.
- **E-Stop / Police Mode**: bestehende Pfade unverändert; sie nutzen weiterhin die
  vorhandenen Sicherheitsgates.

---

## 8. Ehrliche Hardware-Support-Kennzeichnung (Phase 16 §9)

Die UI unterscheidet jetzt sichtbar:

- Code implementiert: **ja**
- Unit-getestet: **ja** (JVM, ohne echtes Gerät)
- BLE-Verhalten simuliert: **ja** (Tests, kein Hardware-Beweis)
- Mit echtem Gerät getestet: **nein** (Stand jetzt)
- Mit echtem Scooter-Protokoll bestätigt: **nein** (Stand jetzt)

Solange `hardwareVerified == false`, wird kein Gerät als „vollständig unterstützt“
bezeichnet.
