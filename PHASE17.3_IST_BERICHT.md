# PHASE 17.3 — IST-BERICHT (Bestandsaufnahme vor der Implementierung)

**Stand:** Basis ist `main` @ `bf0d936` (Merge von PR #29, Phase 17.2).
**Branch:** `feat/phase17.3-hardware-validation`.
**Datum der Bestandsaufnahme:** siehe Commit-Zeitstempel.

Dieser Bericht beschreibt ausschließlich, was im Repository **tatsächlich
vorhanden** ist. Es werden keine Funktionen erfunden und keine UUIDs,
Hersteller-IDs oder Modellmerkmale angenommen.

---

## 1. Vorhandene Erkennungsarchitektur

### 1.1 `RecognitionModel.kt`
Enthält die reinen Aufzählungs- und Werttypen der Erkennung:

* `RecognitionConfidence` — `CONFIRMED`, `PROBABLE`, `FAMILY_ONLY`, `UNKNOWN`.
  `rank` ist seit Phase 17.2 korrekt (`entries.size - 1 - ordinal`), d. h.
  `UNKNOWN = 0 … CONFIRMED = 3`.
* `EvidenceGrade` — `CONFIRMED`, `PROBABLE`, `UNKNOWN`; `countsTowardModel`
  ist nur für `CONFIRMED`/`PROBABLE` wahr.
* `HardwareTestStatus` — `NOT_TESTED`, `PARTIALLY_TESTED`,
  `CONFIRMED_ON_HARDWARE` mit `displayName`.
* `BrandFamily` — `SEGWAY_NINEBOT`, `NAVEE`, `XIAOMI`, `SOFLOW`, `INMOTION`,
  `KUKIRIN`, `UNKNOWN` mit `of(manufacturer)`.
* `RecognitionReason` / `RecognitionReasonCode` (u. a. `GATT_EVIDENCE_ADDED`,
  `GATT_NO_EVIDENCE` aus Phase 17.2).
* `RecognitionFeatureMatch(kind, value, grade, source)` mit `label()`.

**Bewertung:** solide, unveränderte Basis. Für Phase 17.3 wird nur additiv
erweitert (kein Umbau).

### 1.2 `ModelFingerprint.kt`
* `FingerprintEvidence(grade, source)`.
* `AdvertisementFingerprint(namePatterns, serviceUuids: Map<String, FingerprintEvidence>, manufacturerIds: Map<Int, FingerprintEvidence>, serviceDataUuids)`.
* `GattFingerprint(serviceUuids, characteristicUuids, requiredProperties)`.
* `ModelFingerprint(...)` mit `hardwareTestStatus = NOT_TESTED`,
  `confirmationDate: String? = null`, `version = 1` sowie
  `isHardwareConfirmed` (nur wahr bei `CONFIRMED_ON_HARDWARE` **und**
  gesetztem `confirmationDate`) und `evidenceSummary()`.
* `ModelFingerprintDatabase` + `BuiltInModelFingerprints` mit **genau drei**
  Einträgen:
  1. `ninebot-generic-unconfirmed` (NINEBOT/SEGWAY_NINEBOT) — Service `e003`,
     Chars `fff1`/`fff2`, alle `PROBABLE`.
  2. `navee-gt3max-unconfirmed` (NAVEE) — Service `ffe0`, Chars `ffe1`
     (NOTIFY) / `ffe2` (WRITE|WRITE_NO_RESPONSE), alle `PROBABLE`.
  3. `xiaomi-legacy-unconfirmed` (XIAOMI) — Service `fe95`, alle `UNKNOWN`,
     Quelle mit Suffix `LEGACY_UNCONFIRMED`.
* **Alle drei** stehen auf `hardwareTestStatus = NOT_TESTED`,
  `confirmationDate = null`.

**Bewertung:** Es gibt aktuell **keine** hardware-bestätigte Fingerprint.
Das ist korrekt und wird durch `Phase171RecognitionTest.noBuiltInProfileIsHardwareConfirmed`
erzwungen. Phase 17.3 darf diese Zusicherung **nicht** aufweichen; Testbelege
werden deshalb getrennt von den Fingerprints geführt.

### 1.3 `RecognitionEngine.kt`
* `RecognitionInput(advertisement, gatt)`.
* `RecognitionResult(...)` mit `detectedModel`, `reasonCodes()`,
  `hasReason()`, `summarySentence()`.
* GATT-Gating aus Phase 17.2: GATT-Merkmale zählen **nur** bei
  `discoveryStatus == COMPLETE` als positive Evidenz.
* `scoreProfile(...)` bewertet `NAME`, `ADV_SERVICE`, `ADV_SERVICE_DATA`,
  `ADV_COMPANY`, `GATT_SERVICE`, `GATT_CHARACTERISTIC`, `GATT_PROPERTY`.
* `classify(...)`: Widersprüche → `UNKNOWN`/`FAMILY_ONLY`; `CONFIRMED` nur bei
  hardware-bestätigtem Fingerprint **und** ausschließlich `CONFIRMED`-Evidenz
  **und** ≥ 3 verschiedenen Merkmalsarten.

**Bewertung:** Die Konfidenzlogik ist ehrlich und bereits gehärtet. Phase 17.3
muss keine Erkennungsregel ändern — nur den **Nachweisstand** sichtbar machen.

### 1.4 `AdvertisementFeatures.kt` / `GattFeatures.kt`
* `AdvertisementFeatures` trägt Name, RSSI, Service-UUIDs, Manufacturer-IDs,
  Service-Data-UUIDs; `GenericUuids.isGeneric` filtert SIG-Standard-UUIDs aus.
* `GattFeatures` trägt Services/Characteristics mit echten Properties,
  `discoveryStatus`, `errors`; `GattDiscoveryStatus` mit `displayName`.
* Beide sind **verlustfreie** Sichten auf reale Messdaten.

**Bewertung:** Diese Typen sind die natürliche Datenquelle für ein
Hardware-Testprotokoll. Phase 17.3 baut darauf auf, statt neue Rohdatenformate
zu erfinden.

### 1.5 `RecognitionSession.kt` (Phase-17.2-Neufassung)
* Pro-Adresse `scanResults`, getrennter `activeResult`/`activeAddress`,
  Historie `lastActiveResult`/`lastActiveAddress`.
* `bindActive()` prüft die Adresse, `archiveActive()`, `displayResult()`.

**Bewertung:** Für Hardware-Tests ausreichend; keine Änderung nötig.

---

## 2. Vorhandene Diagnose- und Exportfläche

### 2.1 `RecognitionExport.kt`
Sektionen: Nutzerprofil, Hardware-Erkennung, Advertisement, GATT, Begründung.
Phase 17.2 ergänzte additiv `scanConfidence`, `finalConfidence`,
`gattEvidenceAdded`, `stillMissing`, `scanObservedAtMs`, `gattObservedAtMs`.
`maskAddress()` maskiert MAC-Adressen (`AA:BB:**:**:EE:FF`).

### 2.2 `HardwareTestReport.kt`
* `recognition: RecognitionExport?`.
* JSON-Objekt `"recognition"` mit `userProfile`, `hardwareDetection`,
  `advertisement`, `gatt`, `reasoning`, `comparison`.
* CSV-Sektionen `recognition_user`, `recognition_hw`, `recognition_adv`,
  `recognition_gatt`, `recognition_reason`, `recognition_compare`.
* `schemaVersion = 1`.

**Bewertung:** Export ist bereits strukturiert und rückwärtskompatibel
erweiterbar (defaultete Felder). Phase 17.3 ergänzt eine Sektion
`hardware_validation` bzw. ein JSON-Objekt `"hardwareValidation"`.

### 2.3 `MainActivity.renderRecognitionSection()`
Vier Unterblöcke: `NUTZERPROFIL`, `SCAN-ERKENNUNG`,
`LIVE-HARDWARE-ERKENNUNG`, `ERGEBNISVERGLEICH`. Helfer: `recognitionSubLabel`,
`recognitionRow`, `recognitionConfidenceColor`, `makeSectionLabel`, `makeCard`,
`cardParams`, `makeText`.

**Bewertung:** Es fehlt ein Block, der **Fingerprint-Qualität** und
**Hardware-Teststatus** (dokumentiert / getestet / hardware-bestätigt) sowie
fehlende Testbelege und Abweichungen anzeigt. Genau das ergänzt Phase 17.3.

---

## 3. Vorhandene Tests (Basis: 37 Suiten, 573 Tests, 0 Fehler, 0 skipped)

Relevant für 17.3:
* `Phase171RecognitionTest` — enthält
  `noBuiltInProfileIsHardwareConfirmed` (alle Built-ins `NOT_TESTED`),
  `confirmedRequiresHardwareConfirmedProfileAndConfirmedEvidence`,
  `contradictionsCapConfidenceAtFamilyOnly`.
* `Phase172LiveGattRecognitionTest` — 27 Tests zu Live-GATT, Session-Trennung,
  Export-Vergleich.

**Konsequenz:** Neue Tests kommen in einer eigenen Datei hinzu. Bestehende
Tests werden **nicht** gelöscht oder übersprungen. Die Zusicherung „kein
Built-in ist hardware-bestätigt" bleibt bestehen, weil Testbelege getrennt
geführt werden.

---

## 4. Dokumentation
Vorhanden: `BLUETOOTH_PROTOCOL_AUDIT.md`, `PHASE17.1_*`, `PHASE17.2_ERGEBNIS.md`,
`PHASE16.*`, `README.md`. Die UUID-Herkunft ist in `BleUuids.kt`
(`UuidProvenance`, `confirmedOnHardware = false` für alle) sauber belegt.

---

## 5. Abgeleitete Lücken für Phase 17.3

| Lücke | Auswirkung | Geplante Maßnahme |
|---|---|---|
| Kein strukturiertes Hardware-Testprotokoll | Reale Tests nicht nachvollziehbar dokumentierbar | Neues `HardwareTestRecord` + `HardwareTestRegistry` |
| Keine Trennung dokumentiert / getestet / hardware-bestätigt | Fingerprint-Qualität nicht ablesbar | Neues `FingerprintMaturity` + `FingerprintQualityAudit` |
| Keine Abweichungsberechnung erwartet vs. beobachtet | Widersprüche unsichtbar | `HardwareDeviation` mit Schweregrad |
| Kein UI-Block für Teststatus | Nutzer sieht Theorie vs. Realität nicht | Neuer Unterblock in `renderRecognitionSection()` |
| Kein Export der Testqualität | Bericht unvollständig | `RecognitionExport.hardwareTest` + `HardwareTestReport` |
| Keine Tests für unvollständige/widersprüchliche Hardware-Daten | Regressionsrisiko | Neue Testsuite `Phase173HardwareValidationTest` |

---

## 6. Sicherheitsgrenzen (bestätigt)
Die bestehende Architektur führt **ausschließlich lesende** Diagnose durch:
`gattSnapshot()`, `liveGattSnapshot()`, Property-Auswertung. Es gibt keine
Schreibbefehle, kein Flashen, keine Tempolimit-Umgehung und keine
automatischen Einstellungsänderungen. Phase 17.3 hält diese Grenze ein: Das
Hardware-Testsystem ist reine Dokumentation/Auswertung.
