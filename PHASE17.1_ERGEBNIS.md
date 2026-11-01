# PHASE 17.1 — ADVANCED SCOOTER RECOGNITION ENGINE — ERGEBNISBERICHT

**Repository:** `syrak7305-bit/SyrakScooterLab`
**Basis-Branch:** `main` @ `f12e0f9b7118ad55ab253291a742e485a5889801`
**Feature-Branch:** `feat/phase17.1-advanced-scooter-recognition`
**Feature-Commit:** `fe9b6ca`
**Pull Request:** [#28](https://github.com/syrak7305-bit/SyrakScooterLab/pull/28) (OPEN, Basis `main`)

---

## 1. Ausgangszustand

Vor Beginn wurde der reale Repository-Zustand geprüft (nicht angenommen):

| Punkt | Befund |
|---|---|
| Branch | `main` |
| HEAD-Commit | `f12e0f9b7118ad55ab253291a742e485a5889801` |
| Offene PRs | 0 |
| Gemergte PRs | 27 (#1–#27) |
| GitHub Actions | Workflow „Build Android APK“, letzter Lauf `41347143655` → `completed` / `success` (3m57s) |
| Baseline-Tests | 35 Suites, **514 Tests**, 0 Fehler, 0 übersprungen |
| Baseline-Build | `assembleDebug` erfolgreich |
| Bekannte Warnung | `BleEngine.kt:897` — deprecated `writeDescriptor` |

`assembleDebug` hat seit Phase 16.2 ein hartes `dependsOn("testDebugUnitTest")`; ein Build führt also immer die Unit-Tests aus.

## 2. Analysierte Architektur

- **Zwei BLE-Pfade:** aktiver `BleEngine` + Adapter (produktiv) und ein legacy `core.ble.BleManager`/`BleConstants` (nur Compose-Pfad, toter Track).
- **Zentrale UUID-Registry** `BleUuids` mit `UuidProvenance` (DOCUMENTED / PROJECT_CONSTANT / LEGACY_UNCONFIRMED / PLACEHOLDER), `UuidRole`, `candidateRoles`, `roleAmbiguous`, `confirmedOnHardware` (aktuell überall `false`).
- **Bekannte Projekt-UUIDs:** Ninebot-Service `0000e003`, Write-Chars `0000fff1`/`0000fff2`; Navee/Brightway `0000ffe0` (Service), `0000ffe1` (Notify), `0000ffe2` (Write); Legacy-Nordic-UART `6e400001/2/3`; Legacy-Xiaomi `0000fe95`; CCCD `00002902`.
- **Reine Logik-Bausteine:** `ScooterManufacturer.classify`, `ScooterCandidateFilter`, `ScooterModel.detectDetailed`, `DetectionConfidence`, `ScanObservation`, `DeviceNameResolver`, `AdvertisementAnalyzer`, `ScanAggregator`, `ScanClassifier`, `GattCharacteristicSelector`, `SupportMatrix`/`AdapterCapabilities`, `WriteGuard`, `TuningSafetyGate`, `HardwareTestReport`.
- **Phase-17.0-Profilschicht:** `RiderScooterProfile`, `UserProfile`, `ProfileCodec`, `ScooterBrandModels`, `OnboardingGate`, `ProfileSessionGuard`, `ScooterFeatureRegistry`, `ProtocolRegistryView`.
- **MainActivity** ist ein 5.296-Zeilen-Monolith; `leScanCallback` baut `ScanObservation`, analysiert per `AdvertisementAnalyzer`, aggregiert per `ScanAggregator`, klassifiziert per `ScanClassifier`, protokolliert in `hardwareTest`; `buildHardwareReport()` komponiert den Export.

Alle in §1 des Auftrags gesuchten Klassen existieren tatsächlich. Details: `PHASE17.1_IST_BERICHT.md`.

## 3. Implementierte Recognition Engine

Neues Paket `app/src/main/java/com/syrak/scooterlab/recognition/` — **reines Kotlin, UI-unabhängig, ohne Android-Abhängigkeit**:

- **`RecognitionEngine`** — deterministische 7-Schritt-Pipeline: Gerät gefunden → Advertisement strukturell → Marke → Modellfamilie → konkretes Modell → Qualitätsbewertung → Ergebnis + Begründung.
- **`RecognitionModel`** — Vokabular: `RecognitionConfidence`, `EvidenceGrade`, `HardwareTestStatus`, `BrandFamily`, `RecognitionReason` + 35 stabile `RecognitionReasonCode`.
- **`AdvertisementFeatures`** — strukturelle Advertisement-Sicht (Name, Service-UUIDs, Manufacturer-IDs, Service-Data, RSSI) inkl. `GenericUuids`-Filter.
- **`GattFeatures`** — strukturierte GATT-Sicht (Services, Charakteristiken, Properties, Discovery-Status, Fehler).
- **`ModelFingerprint`** — Fingerprint-DB mit Evidenzgrad + Quelle.
- **`RecognitionSession`** — hält das letzte Ergebnis, verwirft es bei Adresswechsel.
- **`RecognitionExport`** — datenschutzgerechter Export (MAC maskiert, Seriennummer nie).

Die Engine hängt nicht an `MainActivity`; sie ist über `RecognitionInput` (bewusst **ohne** Nutzerprofil) vollständig separat testbar.

## 4. Geänderte / neue Dateien

**Neu:**
- `recognition/RecognitionModel.kt`
- `recognition/AdvertisementFeatures.kt`
- `recognition/GattFeatures.kt`
- `recognition/ModelFingerprint.kt`
- `recognition/RecognitionEngine.kt`
- `recognition/RecognitionSession.kt`
- `recognition/RecognitionExport.kt`
- `app/src/test/java/com/syrak/scooterlab/Phase171RecognitionTest.kt` (32 Tests)
- `PHASE17.1_IST_BERICHT.md`, `PHASE17.1_ERGEBNIS.md`

**Geändert (additiv):**
- `diagnostics/HardwareTestReport.kt` — Feld `recognition`, JSON-Block, CSV-Zeilen (schemaVersion bleibt 1).
- `MainActivity.kt` — Engine-/Session-Felder, Aufruf im Scan-Callback, neue Erkennungsansicht, Export-Verdrahtung.

Keine bestehende Funktion entfernt, keine Signatur gebrochen.

## 5. Tatsächlich unterstützte Erkennungsmerkmale

- Advertisement: Gerätename, Service-UUIDs (complete/incomplete), Manufacturer Specific Data (Company-IDs), Service Data, RSSI (nur angezeigt, **nie** modellbestimmend).
- GATT: Service-UUIDs, Charakteristik-UUIDs, Properties (READ/WRITE/WRITE_NO_RESPONSE/NOTIFY/INDICATE), User-Description, Discovery-Status (COMPLETE/INCOMPLETE/FAILED/NOT_ATTEMPTED), Fehler.
- Bewertung: Evidenzgrade je Merkmal, Widersprüche, fehlende und unbekannte Merkmale.

## 6. Tatsächlich vorhandene bestätigte Modellprofile

**Keine.** Die Built-in-DB enthält genau **3 dokumentierte, aber NICHT hardware-bestätigte** Profile:

| ID | Marke | Familie | Quelle | Evidenz | Hardware-Status |
|---|---|---|---|---|---|
| `ninebot-generic-unconfirmed` | NINEBOT | SEGWAY_NINEBOT | `BleUuids.kt` / `BLUETOOTH_PROTOCOL_AUDIT.md` (PROJECT_CONSTANT) | Service `e003` PROBABLE, Chars `fff1`/`fff2` PROBABLE | NOT_TESTED |
| `navee-gt3max-unconfirmed` | NAVEE | NAVEE | dito | Service `ffe0` PROBABLE, `ffe1` NOTIFY, `ffe2` WRITE (PROBABLE) | NOT_TESTED |
| `xiaomi-legacy-unconfirmed` | XIAOMI | XIAOMI | dito | Service `fe95` **UNKNOWN** | NOT_TESTED |

`isHardwareConfirmed` erfordert `CONFIRMED_ON_HARDWARE` **und** ein Bestätigungsdatum — beides ist nirgends gesetzt. Ein Test erzwingt, dass kein Built-in-Profil hardware-bestätigt ist. Dadurch ist `CONFIRMED` mit den echten Profilen **nicht erreichbar**.

## 7. Konfidenzregeln

`CONFIRMED` / `PROBABLE` / `FAMILY_ONLY` / `UNKNOWN` — deterministisch, in dieser Reihenfolge:

1. Widersprüche vorhanden → `UNKNOWN` (ohne sinnvolle Treffer) bzw. `FAMILY_ONLY`.
2. Profil hardware-bestätigt **und** alle sinnvollen Treffer `CONFIRMED` **und** ≥3 verschiedene Merkmalsarten → `CONFIRMED`.
3. Nur Name → `FAMILY_ONLY`.
4. ≥2 sinnvolle Treffer **und** ≥2 verschiedene Merkmalsarten → `PROBABLE`.
5. Sonst sinnvolle Treffer → `FAMILY_ONLY`.
6. Sonst → `UNKNOWN`.

Zusätzlich: `EvidenceGrade.UNKNOWN` zählt nie zum Modell; fehlende Daten zählen nie positiv; generische UUIDs werden ignoriert; RSSI wird nur als `RSSI_IGNORED`-Reason dokumentiert. Jede Entscheidung trägt maschinenlesbare `RecognitionReason(code, detail)`.

## 8. Neue UI-Funktionen

Im INFO-Tab, im bestehenden dunklen Syrak-Design, neue Sektion **ERKENNUNG** mit vier Blöcken:

- **NUTZERPROFIL** — gewählte Marke / gewähltes Modell (vom Nutzer deklariert).
- **HARDWARE-ERKENNUNG** — BT-Gerätename, erkannte Marke, erkannte Modellfamilie, erkanntes Modell, Konfidenz-Status (farbcodiert).
- **TECHNISCHE DATEN** — erkannte Services, Charakteristik-Anzahl, Advertisement-Merkmale, GATT-Ergebnis.
- **ERKENNUNGSBEGRÜNDUNG** — Kurzbegründung (`summarySentence()`), passende / fehlende / widersprüchliche / unbekannte Merkmale, Grund für UNKNOWN.

Kein Redesign bestehender Ansichten.

## 9. Diagnose-Export

`HardwareTestReport` um `recognition: RecognitionExport?` erweitert:

- **JSON:** eigener `"recognition"`-Block mit `userProfile`, `hardwareDetection`, `advertisement`, `gatt`, `reasoning`.
- **CSV:** Sektionen `recognition_user`, `recognition_hw`, `recognition_adv`, `recognition_gatt`, `recognition_reason`.
- **Datenschutz:** Seriennummer wird nie exportiert; MAC als `AA:BB:**:**:EE:FF` maskiert.

## 10. Testergebnisse

Tatsächlich ausgeführt (`./gradlew testDebugUnitTest --offline`):

| Kennzahl | Wert |
|---|---|
| Suites | **36** |
| Tests | **546** |
| Fehlgeschlagen | **0** |
| Fehler | **0** |
| Übersprungen | **0** |

Davon 32 neue Tests in `Phase171RecognitionTest` (alle §12-Szenarien). Keine Tests entfernt oder übersprungen.

## 11. Build-Ergebnis

Tatsächlich ausgeführt (`./gradlew assembleDebug --offline`):

- **BUILD SUCCESSFUL** (1m 52s)
- APK: `app/build/outputs/apk/debug/app-debug.apk`, **18.822.410 Bytes (~17,95 MB)**
- Compiler-Warnungen: nur die bereits vorher vorhandene `BleEngine.kt:897` (deprecated `writeDescriptor`).

## 12. Branch und Commit

- Branch: `feat/phase17.1-advanced-scooter-recognition`
- Feature-Commit: `fe9b6ca` (Basis `main` @ `f12e0f9b7118ad55ab253291a742e485a5889801`)
- Kein direkter Commit auf `main`.

## 13. PR-Link

**https://github.com/syrak7305-bit/SyrakScooterLab/pull/28** — OPEN, Basis `main`, Head `feat/phase17.1-advanced-scooter-recognition`.

## 14. Offene Probleme

- Kein Built-in-Profil ist am realen Gerät bestätigt → `CONFIRMED` bleibt bis zu echten Hardware-Tests unerreichbar (bewusst).
- `CompanyIdRegistry` ist absichtlich leer → Company-IDs werden als `UNKNOWN_MANUFACTURER_ID` gemeldet, nicht interpretiert.
- GATT-Fingerprinting ist im Scan-Pfad vorbereitet, aber die Engine erhält GATT-Daten derzeit nur, wenn sie übergeben werden; die Live-Verdrahtung nutzt aktuell Advertisement-Daten (GATT-Anbindung an `bleEngine.gattSnapshot()` ist als nächster Schritt offen, ohne die bestehende Diagnose zu verändern).
- Legacy-BLE-Track (`core.ble`) bleibt unangetastet.

## 15. Notwendige reale Hardware-Tests

1. Ninebot/Segway: `e003`/`fff1`/`fff2` am realen Gerät bestätigen → Profil auf `CONFIRMED_ON_HARDWARE` heben.
2. NAVEE GT3 Max: `ffe0`/`ffe1`(NOTIFY)/`ffe2`(WRITE) inkl. Property-Set bestätigen.
3. Xiaomi Legacy: `fe95`-Rolle und tatsächliche Charakteristiken verifizieren.
4. Weitere Marken (SOFLOW, INMOTION, KuKirin): Advertisement-/GATT-Fingerprints erst nach Messung ergänzen.
5. Reconnect mit anderem Scooter: Session-Reset und Export-Trennung am Gerät prüfen.

---

## Evidenz-Kategorien (verbindlich getrennt)

### A. Im Code implementiert
Recognition-Paket (Engine, Features, Fingerprint-DB, Session, Export), additive `HardwareTestReport`-Erweiterung, MainActivity-Erkennungsansicht und Export-Verdrahtung.

### B. Automatisiert getestet
32 neue Tests + 514 Bestandstests = 546 grün. Abgedeckt: vollständige/unvollständige Advertisements, fehlende Manufacturer-Data, unbekannte Company-IDs/Services, Widersprüche, generische UUIDs, Name-only, RSSI-only, Mehrfachmerkmale, Segway/Ninebot-Trennung, NAVEE≠Ninebot, Xiaomi, unbekannte Modelle, Profil-/Hardware-Trennung, Konfidenzregeln, GATT ohne Properties, Discovery-Fehler, Session-Reset, JSON/CSV-Export, ScanObservation-Regression.

### C. Auf realer Hardware beobachtet
**Nichts.** In dieser Phase wurde kein reales Gerät getestet.

### D. Reproduzierbar auf realer Hardware bestätigt
**Nichts.** Es existiert kein hardware-bestätigtes Profil; `confirmedOnHardware` ist überall `false`.

---

**Wichtigste Regel, eingehalten:** Ein gefundenes Bluetooth-Gerät ist nicht automatisch ein erkannter Scooter; eine erkannte Marke ist nicht automatisch ein bestätigtes Modell; ein bestätigtes Modell ist nicht automatisch ein Funktionsnachweis. Die Engine behauptet nie mehr, als die Evidenz trägt.
