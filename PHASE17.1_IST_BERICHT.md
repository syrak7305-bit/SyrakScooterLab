# PHASE 17.1 – IST-BERICHT (Repository-Analyse vor Implementierung)

Stand: Basis `main` @ `f12e0f9b7118ad55ab253291a742e485a5889801`
("Merge pull request #27 from syrak7305-bit/feat/phase17.0-onboarding-profiles").

Dieser Bericht beschreibt ausschließlich, was im Repository **tatsächlich**
vorhanden ist. Es werden keine Entwicklungsstände behauptet, die nicht belegt
sind. Alle Aussagen sind durch Datei-Reads, `git log`, `gh pr list` und
`gh run list` gedeckt.

---

## 1. Repository- und CI-Zustand

| Prüfpunkt | Ergebnis |
|-----------|----------|
| Branch | `main` |
| HEAD-Commit | `f12e0f9b7118ad55ab253291a742e485a5889801` |
| Working Tree | clean |
| Offene Pull Requests | **keine** |
| Gemergte Pull Requests | 27 (PR #1 – #27, alle `MERGED`) |
| Letzter CI-Lauf auf `main` | Run `41347143655`, Status `completed` / `success`, Dauer 3m57s, 2026-11-01T20:29:56Z |
| CI-Workflow | `.github/workflows` → "Build Android APK", JDK 17 temurin, `./gradlew ... assembleDebug` |
| Branch `feat/phase17.1-*` | existiert noch **nicht** |

`assembleDebug` hat seit Phase 16.2 eine harte Kopplung:

```kotlin
tasks.matching { it.name == "assembleDebug" }.configureEach {
    dependsOn("testDebugUnitTest")
}
```

Ein erfolgreicher Debug-Build setzt also zwingend eine grüne Unit-Test-Suite
voraus. Das ist für Phase 17.1 bindend.

**Baseline-Messung (tatsächlich ausgeführt, nicht behauptet):**

```
./gradlew testDebugUnitTest --no-daemon
BUILD SUCCESSFUL in 2m 25s
suites=35 tests=514 failures=0 errors=0 skipped=0
```

Damit ist der Ausgangspunkt exakt reproduziert (deckt sich mit
`PHASE17.0_ERGEBNIS.md`: 35 Suiten, 514 Tests, 0 Fehler).

---

## 2. Architektur (Ist)

Ein einziges Gradle-Modul `:app`, Namespace/ApplicationId
`com.syrak.scooterlab`, Kotlin 2.0.21, AGP 8.7.2, Gradle 8.9, compileSdk 34,
minSdk 26, targetSdk 34, JVM 17. Die reale UI ist eine programmatische
`LinearLayout`-Oberfläche in `MainActivity` (5.296 Zeilen) mit den Tabs
`INFO`, `TOOLS`, `DASHBOARD`, `FLASH`, `CONFIG`. Compose ist zwar aktiviert,
wird aber vom realen UI-Pfad nicht verwendet.

Zwei BLE-Stacks existieren nebeneinander:

* **AKTIV:** `BleEngine` + `ScooterProtocolAdapter` / `NinebotAdapter` /
  `NaveeGt3MaxAdapter`, genutzt von `MainActivity`.
* **LEGACY:** `core.ble.BleManager` + `BleConstants`, nur vom Compose-Pfad
  (`AppContainer` / `SyrakApp` / `DashboardViewModel`) referenziert – totes Gleis.

`BleUuids.kt` ist die zentrale UUID-Registry mit `UuidProvenance`
(`DOCUMENTED`, `PROJECT_CONSTANT`, `LEGACY_UNCONFIRMED`, `PLACEHOLDER`),
`UuidRole`, `candidateRoles`, `roleAmbiguous`. **`confirmedOnHardware` ist für
alle Einträge `false`.** Es gibt keine einzige hardware-bestätigte UUID.

---

## 3. Vorhandene Erkennungslogik (Ist)

Folgende der im Auftrag gesuchten Komponenten existieren **wirklich**:

| Gesuchte Komponente | Vorhanden | Datei |
|---------------------|-----------|-------|
| `DeviceIdentification.kt` | ja | `ScooterManufacturer`, `ScooterCandidateFilter`, `TuningSafetyGate`, `AutoHandshakePolicy` |
| `BleEngine.kt` | ja | aktiver BLE-Stack inkl. `gattSnapshot()` |
| `BleConstants.kt` | ja | Legacy-Konstanten, delegiert an `BleUuids` |
| `ScanObservation.kt` | ja | rohe Advertisement-Beobachtung + `normalizeUuid` |
| `AdvertisementAnalyzer.kt` | ja | `AdvertisementEvidence`, `EvidenceKind`, `EvidenceStrength`, `ServiceHint`, `CompanyIdRegistry` (bewusst **leer**) |
| `DeviceNameResolver.kt` | ja | Namensauflösung, lehnt Platzhalter ab |
| `ScanAggregator.kt` | ja | Aggregation über mehrere Pakete, monotone Namensgüte |
| `ConnectionStagePolicy.kt` | ja | Verbindungsphasen |
| `OnboardingProfile.kt` | ja | `UserProfile`, `RiderScooterProfile`, `ProfileCodec`, `ScooterBrandModels`, `OnboardingGate`, `ProfileSessionGuard` |
| `ScooterRepository.kt` | ja | SharedPreferences-Persistenz inkl. Profile, PIN (PBKDF2) |
| `ScooterManufacturer` | ja | Enum NAVEE, XIAOMI, NINEBOT, SEGWAY, KUKIRIN, SOFLOW, INMOTION, UNKNOWN |
| `ScooterModel` | ja | Enum mit 13 Modellen + `detectDetailed` |
| `ProtocolAdapters` / `AdapterCapabilities` | ja | `ScooterProtocolAdapter`, `NinebotAdapter`, `NaveeGt3MaxAdapter`, `AdapterCapabilities` |
| `TelemetryDiagnostics` | ja | `TelemetryDiagnostics.kt` |
| `HardwareTestReport` | ja | JSON + CSV + Summary, `SCHEMA_VERSION = 1` |

**Was bereits funktioniert (belegt durch Code + Tests):**

* Advertisement-Rohdaten werden erfasst: Service-UUIDs, Manufacturer-IDs,
  Service-Data-UUIDs, RSSI, Name (Advertisement und GATT getrennt).
* `DeviceNameResolver` unterscheidet echten Namen von "kein Name" (kein
  Platzhalter-String mehr im Datenmodell).
* `AdvertisementAnalyzer` liefert **einen** stärksten Hinweis
  (Name > starke Service-UUID > Company-ID > schwache Service-Data).
* `ScanAggregator` hält über mehrere Pakete den besten Namen und die stärkste
  Evidenz.
* `ScanClassifier` stuft höchstens `PROBABLE_SCOOTER` ein – Advertisement allein
  macht nie einen "bekannten Scooter".
* `ScooterModel.detectDetailed` nutzt Token-Grenzen, Mindest-Keyword-Länge 3,
  Markenfilter und liefert `CONFIRMED` nur bei spezifischem Keyword
  (≥ 5 Zeichen **und** Ziffer), sonst `PROBABLE`/`UNKNOWN`.
* `BleEngine.gattSnapshot()` erfasst Services, Characteristics und alle
  Android-Property-Bits.
* `HardwareTestReport` exportiert Scan-, GATT-, Frame-, Read-, RSSI- und
  Fehlerdaten als JSON und CSV.

---

## 4. Belegte Lücken (Ausgangspunkt für Phase 17.1)

1. **Keine einheitliche Recognition Engine.** Die Schritte "Gerät finden →
   Advertisement erfassen → Marke → Modellfamilie → konkretes Modell →
   Qualität → Begründung" existieren nur verstreut (`ScanClassifier`,
   `ScooterModel.detectDetailed`, `AdvertisementAnalyzer`). Es gibt keine
   einzige, UI-unabhängige Funktion, die alle Schritte deterministisch
   zusammenführt.
2. **Kein Modell-Fingerprint-Datensatz mit Evidenz-Herkunft.** `ScooterModel`
   ist eine Keyword-Liste ohne Service-/GATT-Bezug und ohne
   Evidenzgraduierung. `CompanyIdRegistry` ist leer.
3. **Widersprüche werden nicht sichtbar gemacht.** `AdvertisementEvidence`
   liefert nur den stärksten Hinweis; widersprüchliche Marken-/Service-Hinweise
   werden verworfen statt gemeldet.
4. **GATT-Fingerprint unvollständig.** `gattSnapshot()` liefert Services und
   Properties, aber keinen Discovery-Status (vollständig/unvollständig/
   fehlgeschlagen) und keine Deskriptoren.
5. **Keine Konfidenz `FAMILY_ONLY`.** Vorhanden sind nur `CONFIRMED`,
   `PROBABLE`, `UNKNOWN`. Die geforderte Stufe "nur Familienhinweis" fehlt.
6. **Keine maschinenlesbaren Begründungen.** Es gibt keine strukturierten
   Reason-Codes; Begründungen sind Freitext.
7. **Keine getrennte Erkennungsansicht.** Die UI zeigt Marke/Modell, aber
   nicht Nutzerprofil vs. Hardware-Erkennung vs. technische Daten vs.
   Begründung in einer professionellen Sektion.
8. **Export ohne Erkennungsbezug.** `HardwareTestReport` enthält kein
   Nutzerprofil, keine automatisch erkannte Marke/Modellfamilie, keine
   Konfidenz, keine Begründung und keine unbekannten Merkmale.

---

## 5. Wiederverwendung statt Parallelarchitektur

Phase 17.1 erweitert ausschließlich vorhandene Strukturen:

* `ScanObservation` bleibt die Rohdatenquelle (keine Änderung der
  Scan-Aggregation).
* `ScooterManufacturer` bleibt die Markenquelle.
* `BleUuids` bleibt die einzige UUID-Wahrheit.
* `GattServiceRecord` / `GattCharacteristicRecord` bleiben die GATT-Quelle.
* `UserProfile` / `RiderScooterProfile` bleiben das Nutzerprofil.
* `HardwareTestReport` wird additiv erweitert (kein Bruch von
  `schemaVersion = 1`, damit bestehende Export-Tests gültig bleiben).

Neu entsteht **ein** Paket `com.syrak.scooterlab.recognition` mit reiner
Kotlin-Logik (keine Android-Abhängigkeit), das die oben belegten Lücken
schließt und vollständig unit-testbar ist.

---

## 6. Fazit

Der Ist-Zustand ist eine solide, ehrliche Basis: Advertisement-Rohdaten und
GATT-Properties werden bereits erfasst, Marken- und Modell-Heuristiken
existieren, und alle Hardware-Behauptungen sind bewusst unbestätigt
(`confirmedOnHardware = false`). Was fehlt, ist die **eine** deterministische,
UI-unabhängige Erkennungspipeline mit Evidenzgraduierung, Widerspruchserkennung,
maschinenlesbaren Begründungen und einer getrennten Erkennungsansicht samt
Export. Genau diese Lücken adressiert Phase 17.1 – ohne erfundene Profile und
ohne erfundene Hardware-Bestätigung.
