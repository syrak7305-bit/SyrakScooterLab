# PHASE 17.3 — ABSCHLUSSBERICHT: Hardware Validation & Fingerprint Expansion

**Basis:** `main` @ `bf0d936` (Phase 17.2 gemerged).
**Branch:** `feat/phase17.3-hardware-validation`.
**Ergebnis:** Umgesetzt, getestet, gebaut. Keine Erfolgsmeldung ohne echte
Build- und Testergebnisse (siehe §4/§5).

---

## 1. Ausgangslage (verifiziert)

* `main` HEAD = `bf0d936` („Merge pull request #29 … phase17.2-live-gatt-recognition").
* Vorhandene Erkennung: `RecognitionEngine`, `ModelFingerprint` mit drei
  eingebauten Profilen (alle `NOT_TESTED`), `GattFeatures`/`AdvertisementFeatures`,
  `RecognitionSession`, `RecognitionExport`, `HardwareTestReport`.
* Bestehende Tests: 37 Suiten / 573 Tests (Phase 17.2), alle grün.
* Kein Fingerprint war hardware-bestätigt; `Phase171RecognitionTest.noBuiltInProfileIsHardwareConfirmed`
  erzwingt das.

Die vollständige Bestandsaufnahme steht in `PHASE17.3_IST_BERICHT.md`.

---

## 2. Geänderte und neue Dateien

| Datei | Art | Inhalt |
|---|---|---|
| `app/src/main/java/com/syrak/scooterlab/recognition/HardwareTestRecord.kt` | **neu** | Hardware-Testsystem + Fingerprint-Qualitätslogik (reine Logik) |
| `app/src/main/java/com/syrak/scooterlab/recognition/RecognitionExport.kt` | geändert (additiv) | `HardwareValidationExport`, Feld `hardwareValidation` |
| `app/src/main/java/com/syrak/scooterlab/diagnostics/HardwareTestReport.kt` | geändert (additiv) | JSON `hardwareValidation`, CSV `hardware_validation` |
| `app/src/main/java/com/syrak/scooterlab/MainActivity.kt` | geändert | UI-Block „FINGERPRINT-QUALITAET & HARDWARE-TEST", Export-Anbindung |
| `app/src/test/java/com/syrak/scooterlab/Phase173HardwareValidationTest.kt` | **neu** | 29 Tests |
| `PHASE17.3_IST_BERICHT.md` | **neu** | Bestandsaufnahme |
| `PHASE17.3_ERGEBNIS.md` | **neu** | dieser Bericht |
| `todo.md` | geändert | Arbeitsplan Phase 17.3 |

Es wurden **keine** bestehenden Funktionen entfernt und **keine** Tests
gelöscht oder übersprungen.

---

## 3. Neue Funktionen / Typen (im Detail)

### 3.1 Hardware-Testsystem (`HardwareTestRecord.kt`)

* `enum class FingerprintMaturity` — `DOCUMENTED`, `TESTED`, `HARDWARE_CONFIRMED`
  mit `displayName` und `isHardwareConfirmed`. Bewusst **getrennt** von
  `HardwareTestStatus` (deklariert vs. berechnet).
* `enum class DeviationSeverity` — `INFO`, `WARNING`, `CRITICAL`.
* `data class HardwareDeviation(field, expected, observed, severity, detail)`
  mit `label()`.
* `data class HardwareScanSnapshot` + `from(AdvertisementFeatures?)` —
  verlustfreie Advertisement-Momentaufnahme.
* `data class HardwareGattSnapshot` + `from(GattFeatures?)` — Service-/
  Characteristic-Struktur inkl. `discoveryStatus`, `isComplete`.
* `data class HardwareRecognitionSnapshot` + `from(RecognitionResult?)` —
  Ist-Erkennungsergebnis.
* `data class HardwareTestRecord(id, manufacturer, modelName, testDate, tester,
  addressMasked, expectedFingerprintId, scan, gatt, observedRecognition, notes)`:
  * `hasValidTestDate` (strenges ISO-`yyyy-MM-dd`, Monat 1–12, Tag 1–31),
  * `missingFields` (Pflichtangaben),
  * `isComplete`.
* `data class HardwareTestAssessment` — Ergebnis einer Bewertung mit
  `criticalCount`, `warningCount`, `isContradictory`, `isConfirming`, `summary()`.
* `object HardwareTestEvaluator.assess(record, fingerprint)` — vergleicht
  erwartet vs. beobachtet:
  * Name, Advertisement-Service-UUIDs, Manufacturer-IDs,
  * GATT-Services, Characteristics, erforderliche Properties,
  * Discovery-Status,
  * Marke, gematchtes Profil, Konfidenz.
  * Abweichungen werden als `HardwareDeviation` dokumentiert; fehlende
    GATT-Services/Characteristics bei vollständiger Discovery sind `CRITICAL`.
* `class HardwareTestRegistry` — hält Datensätze, startet **leer**
  (`empty()`), `byId`, `byFingerprintId`, `size`, `isEmpty`.

### 3.2 Fingerprint-Qualität

* `data class FingerprintQualityReport(fingerprintId, modelName, declaredStatus,
  maturity, testCount, confirmingTestIds, contradictoryTestIds, missingEvidence,
  deviations)` mit:
  * `statusMatchesDeclared`, `upgradePending`, `hasContradictions`, `summary()`.
* `object FingerprintQualityAudit`:
  * `audit(database, registry)` — Bericht für alle Profile,
  * `auditOne(fingerprint, registry)`,
  * `expectedEvidence(fingerprint)`.
* **Regel:** Der Reifegrad wird ausschließlich aus dokumentierten Testbelegen
  **berechnet**. `ModelFingerprint.hardwareTestStatus` wird **nie** verändert.
  Ein `upgradePending` wird nur gemeldet, nicht vollzogen.

### 3.3 Export

* `data class HardwareValidationExport` + `from(report)` + `none()`.
* `RecognitionExport.hardwareValidation: HardwareValidationExport? = null`
  (additiv, default `null` → rückwärtskompatibel).
* `HardwareTestReport`: JSON-Objekt `"hardwareValidation"` und CSV-Sektion
  `hardware_validation`. `schemaVersion` bleibt **1**.

### 3.4 UI (`MainActivity.renderRecognitionSection`)

Neuer Unterblock **FINGERPRINT-QUALITAET & HARDWARE-TEST** mit:
Fingerprint-ID/Modell, deklarierter Teststatus, berechneter Reifegrad,
Anzahl Hardware-Testbelege, fehlende Testbelege, erkannte Abweichungen,
widersprüchliche Tests, Upgrade-Hinweis und einer klaren Einordnung
(„THEORETISCHE ERKENNUNG" vs. „AUF HARDWARE BESTAETIGT").

---

## 4. Testergebnisse

```
./gradlew testDebugUnitTest
BUILD SUCCESSFUL
Suiten:  38
Tests:   602
Fehler:  0
Errors:  0
Skipped: 0
```

* Vorher: 37 Suiten / 573 Tests. Neu: **+1 Suite, +29 Tests**
  (`Phase173HardwareValidationTest`).
* Abgedeckt: leere Registry, vollständiger passender Test, unvollständige
  Daten (fehlender Scan/GATT/Ist-Erkennung/Fingerprint-ID/Modellname),
  ungültiges Testdatum, widersprüchliche Daten (fehlende Characteristic,
  fehlende Property, Marken-Konflikt, unzulässige CONFIRMED-Konfidenz),
  unvollständige Discovery, fehlende Belege, Mehrfach-Tests, Export/JSON/CSV,
  MAC-Maskierung.
* Bestehende Tests (inkl. `Phase171RecognitionTest.noBuiltInProfileIsHardwareConfirmed`)
  bleiben unverändert grün.

---

## 5. Build-Ergebnis

```
./gradlew assembleDebug
BUILD SUCCESSFUL
APK: app/build/outputs/apk/debug/app-debug.apk
Größe: 18.855.178 Bytes
```

Warnungen: nur der vorbestehende Hinweis
„Unable to strip the following libraries … libandroidx.graphics.path.so,
libdatastore_shared_counter.so" (kein Fehler, keine neue Warnung durch 17.3).

---

## 6. Sicherheitsgrenzen (eingehalten)

* Ausschließlich lesende Diagnose: `HardwareTestRecord`/`FingerprintQualityAudit`
  sind reine Auswertung dokumentierter Beobachtungen.
* Keine neuen BLE-Schreibbefehle, kein Firmware-Flashing, keine
  Tempolimit-Umgehung, keine automatischen Einstellungsänderungen.
* Keine erfundenen UUIDs, Manufacturer-IDs oder Modellmerkmale.
* MAC-Adressen werden maskiert (`AA:BB:**:**:EE:FF`), Seriennummern werden
  nicht aufgenommen/exportiert.

---

## 7. Offene Probleme / bewusste Grenzen

1. **Keine echten Hardware-Tests vorhanden.** Die `HardwareTestRegistry` ist in
   der App leer. Daher bleibt jeder Fingerprint `DOCUMENTED`; es gibt **keine**
   Hardware-Bestätigung. Das ist korrekt und gewollt.
2. **Automatisches Upgrade ausdrücklich nicht implementiert.** Auch wenn
   Belege eine Bestätigung nahelegen, wird nur `upgradePending` gemeldet. Eine
   Hochstufung erfordert eine bewusste, dokumentierte Code-Änderung.
3. **Mehrfach-Widerspruch-Semantik:** Ein bestätigender Test hebt einen
   widersprüchlichen Test nicht auf; beide werden getrennt ausgewiesen. Eine
   Priorisierung (z. B. „neuester Test gewinnt") ist bewusst nicht eingebaut.
4. **Registry nur im Speicher.** Es gibt noch keine Persistenz/Import von
   Testdatensätzen (z. B. aus Datei). Bewusst klein gehalten.

---

## 8. Noch manuell durchzuführende Hardware-Tests

Diese können nur mit realen Geräten erfolgen und sind **nicht** durch Unit-Tests
ersetzbar:

1. **Ninebot** (beliebiges Modell): Advertisement mitschneiden (Name, Services,
   Manufacturer-IDs), verbinden, GATT-Discovery vollständig durchführen,
   Service `e003` und Characteristics `fff1`/`fff2` verifizieren, Ist-Erkennung
   dokumentieren.
2. **NAVEE GT3 Max**: Service `ffe0`, Characteristics `ffe1` (NOTIFY) und
   `ffe2` (WRITE/WRITE_NO_RESPONSE) real prüfen, Discovery-Status `COMPLETE`
   bestätigen.
3. **Xiaomi (Legacy)**: prüfen, ob Service `fe95` tatsächlich im Advertisement/
   GATT erscheint; der Eintrag ist bewusst `LEGACY_UNCONFIRMED`/`UNKNOWN`.
4. Für jeden Test: `HardwareTestRecord` mit Datum, maskierter Adresse, Scan-,
   GATT- und Ist-Erkennungsdaten befüllen und die berechneten Abweichungen
   gegenprüfen.

Erst wenn ein solcher Datensatz vollständig und widerspruchsfrei ist, meldet
das System `HARDWARE_CONFIRMED` — und auch dann wird das Profil nicht
automatisch hochgestuft.

---

## 9. Reproduktion

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
./gradlew testDebugUnitTest
./gradlew assembleDebug
```
