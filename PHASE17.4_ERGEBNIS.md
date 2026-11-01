# Phase 17.4 — Hardware-Test Capture, Persistence & Management

## 1. Ausgangslage

| Punkt | Wert |
|---|---|
| Repository | `syrak7305-bit/SyrakScooterLab` |
| Basis-Branch | `main` |
| Start-Commit (HEAD bei Bestandsaufnahme) | `faa1b16` — `docs(phase17.3): mark git tasks complete (PR #30 merged)` |
| Phase-17.3-Merge | `8dd4e97` — vorhanden und in `main` enthalten |
| Arbeitsbaum vor Beginn | sauber |
| Feature-Branch | `feat/phase17.4-hardware-test-persistence` |
| Implementierungs-Commit | `e90d56c` |
| Pull Request | https://github.com/syrak7305-bit/SyrakScooterLab/pull/31 (nicht selbst gemergt) |

Die Bestandsaufnahme hat bestaetigt, dass Phase 17.3 tatsaechlich in `main`
liegt. Die Architektur entspricht der erwarteten Struktur
(`com.syrak.scooterlab.recognition`, `HardwareTestRecord`,
`FingerprintQualityAudit`, `ScooterRepository`, `MainActivity`). Es gab keinen
Grund, die Arbeit abzubrechen.

## 2. Geaenderte und neue Dateien

### Geaendert

| Datei | Aenderung |
|---|---|
| `app/src/main/java/com/syrak/scooterlab/MainActivity.kt` | Hardware-Test-Verwaltung, Formular, Detail, Loeschen, Filter-/Qualitaetsanzeige, JSON-Import/-Export, Laden beim Start |
| `app/src/main/java/com/syrak/scooterlab/ScooterRepository.kt` | `loadHardwareTests()` / `saveHardwareTests()` auf dem bestehenden `SyrakPrefs`-Store |
| `app/src/main/java/com/syrak/scooterlab/recognition/HardwareTestRecord.kt` | `hasValidTestDate` nutzt jetzt echte Kalenderpruefung (`IsoDate`) |

### Neu

| Datei | Zweck |
|---|---|
| `app/src/main/java/com/syrak/scooterlab/recognition/IsoDate.kt` | Kalenderdatums-Validierung, Normalisierung, Vergleich |
| `app/src/main/java/com/syrak/scooterlab/recognition/HardwareTestJson.kt` | JSON-Codec Schema v1 inkl. Parser/Writer und MAC-Maskierung |
| `app/src/main/java/com/syrak/scooterlab/recognition/HardwareTestStore.kt` | Persistenz-Fassade, Lade-/Speicherergebnis, Import-Merge, Filter |
| `app/src/main/java/com/syrak/scooterlab/recognition/SharedPreferencesHardwareTestPersistence.kt` | Android-Adapter auf `SyrakPrefs` |
| `app/src/test/java/com/syrak/scooterlab/Phase174HardwareTestPersistenceTest.kt` | 34 neue Unit-Tests |

## 3. Neue Klassen und zentrale Funktionen

- `object IsoDate` — `isValid`, `normalize`, `compare`.
- `interface HardwareTestPersistence` — `read`, `readBackup`, `write`.
- `enum class HardwareTestLoadStatus` — `EMPTY`, `LOADED`, `CORRUPT`, `IO_ERROR`.
- `data class HardwareTestLoadResult` — Status, Datensaetze, Meldung, Backup-Hinweis, Warnungen, `isUsable`, `registry()`.
- `data class HardwareTestSaveResult`.
- `class HardwareTestStore` — `load()` mit Backup-Fallback, `save()` atomar.
- `data class HardwareTestImportOutcome` — `importedCount`, `skippedCount`, `rejectedCount`, `summary()`.
- `object HardwareTestImporter` — `merge()`, `importJson()`.
- `fun HardwareTestRecord.contentKey()` — inhaltliche Duplikaterkennung.
- `data class HardwareTestFilter` — Marke, Modellsuche, Fingerprint, Reifegrad, Datumsbereich, `matches`, `maturityOf`, `apply`.
- `object HardwareTestJson` — `encode`, `decode`, `maskAddress`, `SCHEMA_VERSION = 1`.
- `class SharedPreferencesHardwareTestPersistence`.
- In `MainActivity`: `renderHardwareTestSection`, `hardwareTestRow`, `renderFingerprintQuality`, `showHardwareTestDetail`, `showHardwareTestForm`, `confirmDeleteHardwareTest`, `exportHardwareTests`, `handleHardwareTestImport`, `persistHardwareTests`, `currentScanSnapshot`, `currentGattSnapshot`, `currentRecognitionSnapshot`.

## 4. Verwendete Speicherarchitektur

Es wurde **keine zweite Speicherarchitektur** eingefuehrt. Die Hardware-Tests
liegen im bereits vorhandenen SharedPreferences-Store `SyrakPrefs` (dieselbe
Datei wie Einstellungen, Profile, PIN usw.), vermittelt ueber
`ScooterRepository`:

- Schluessel `hardware_tests_json` (aktueller Stand)
- Schluessel `hardware_tests_json_backup` (letzter bekannter guter Stand)

Schreibvorgang: `SharedPreferences.Editor.commit()` (synchron, Android ersetzt
die Datei atomar). Vor dem Ueberschreiben wird der bisherige Stand im selben
Commit als Backup mitgeschrieben. Bei `false`/Exception bleibt der alte Stand
unveraendert; die UI meldet verstaendlich, dass der bisherige Stand erhalten
bleibt.

Ladepfad beim App-Start:

1. nichts gespeichert → `EMPTY` (leere Registry, kein Fehler)
2. Hauptstand lesbar → `LOADED`
3. Hauptstand defekt, Backup lesbar → `LOADED` mit `usedBackup = true`
4. beides defekt → `CORRUPT`; nichts wird geloescht, Meldung in der Uebersicht

Bestehende Nutzer-/Scooter-Profile werden nicht beruehrt.

## 5. JSON-Format (Schema-Version 1)

```json
{
  "schemaVersion": 1,
  "generatedAt": "2026-11-15",
  "recordCount": 1,
  "records": [
    {
      "id": "...",
      "manufacturer": "NAVEE",
      "modelName": "NAVEE GT3 Max",
      "testDate": "2026-11-15",
      "tester": "Tester A",
      "addressMasked": "AA:BB:**:**:EE:FF",
      "expectedFingerprintId": "navee-gt3max-unconfirmed",
      "scan": {
        "deviceName": "NAVEE GT3 Max",
        "serviceUuids": ["..."],
        "manufacturerIds": [],
        "serviceDataUuids": [],
        "rssi": -55
      },
      "gatt": {
        "discoveryStatus": "COMPLETE",
        "services": [
          { "uuid": "...", "label": "", "characteristics": [
            { "uuid": "...", "properties": ["READ","NOTIFY"], "userDescription": null }
          ] }
        ],
        "errors": []
      },
      "observedRecognition": {
        "confidence": "PROBABLE",
        "detectedBrand": "NAVEE",
        "detectedBrandFamily": "NAVEE",
        "detectedModelFamily": "NAVEE",
        "confirmedModel": null,
        "probableModel": "NAVEE GT3 Max (unbestaetigt)",
        "matchedProfileId": "navee-gt3max-unconfirmed"
      },
      "notes": "..."
    }
  ]
}
```

Datenschutz: MAC-Adressen werden beim Export **und** beim Import erneut
maskiert (`AA:BB:**:**:EE:FF`). Seriennummern sind kein Feld dieses Formats und
werden nicht geschrieben.

## 6. UI-Aenderungen

Alles ist in die **bestehende CONFIG-Navigation** eingebettet — keine zweite
Hauptnavigation, keine neuen Tabs. Verwendet werden die vorhandenen Helfer
(`makeCard`, `makeSectionLabel`, `makeText`, `makePrimaryButton`,
`makeOutlineButton`, `cardParams`) und die bestehende Palette.

- Abschnitt **„HARDWARE-TESTS & FINGERPRINT-QUALITAET“** im CONFIG-Tab.
- Uebersicht: Marke · Modell, Datum · Fingerprint, Vollstaendigkeit, Anzahl
  kritischer Abweichungen und Warnungen; Zeile antippbar.
- **Neuen Test anlegen** / **Bearbeiten**: Marke (Spinner), Modell, Testdatum,
  Tester, erwarteter Fingerprint (Spinner), Notizen sowie optionale Uebernahme
  der **aktuellen** Sitzungsdaten (Scan/GATT/Erkennung). Ohne diese Daten
  bleibt der Test unvollstaendig und kann nichts bestaetigen.
- **Detailansicht**: alle Felder, maskierte Adresse, Scan-/GATT-/Erkennungs-
  zusammenfassung, Bewertung, Abweichungen, Notizen; Buttons Bearbeiten /
  Loeschen / Schliessen.
- **Loeschen**: nur ein einzelner Datensatz, immer mit expliziter
  Bestaetigung und Klartext, welcher Datensatz entfernt wird.
- **JSON-Export** (SAF `CreateDocument`) und **JSON-Import** (SAF
  `OpenDocument`) mit Ergebnisdialog (neu / uebersprungen / abgelehnt inkl.
  Gruende).
- **Fingerprint-Qualitaet**: je Profil belegt vs. deklariert, Testanzahl,
  bestaetigende/widerspruechliche Tests, fehlende Belege, Hinweis bei
  ausreichenden Belegen (ohne automatisches Upgrade).

## 7. Validierungsregeln

- **Datum**: Format `yyyy-MM-dd`, echte Kalenderpruefung ueber
  `java.time.LocalDate`. Gueltig: `2026-02-28`, `2024-02-29`. Ungueltig:
  `2026-02-31`, `2026-13-01`, `2026-04-31`, `2026-02-29` (kein Schaltjahr),
  `15.11.2026`, `2026/11/15`, `2026-1-5`, leer, `null`.
- **IDs**: nicht leer; doppelte IDs innerhalb einer Importdatei werden
  abgelehnt; vorhandene IDs werden beim Import uebersprungen, nie
  ueberschrieben.
- **Pflichtfelder**: `id`, `manufacturer` (bekannter Enum-Wert), `modelName`
  (nicht leer), `testDate` (gueltig).
- **Fingerprint**: falls gesetzt, muss er in `BuiltInModelFingerprints`
  existieren.
- **Enums**: `GattDiscoveryStatus`, `GattProperty`, `RecognitionConfidence`,
  `ScooterManufacturer`, `BrandFamily` werden gegen die echten Enum-Werte
  geprueft.
- **Struktur**: Wurzel muss Objekt sein, `schemaVersion` muss 1 sein,
  `records` muss ein Array sein; defektes JSON fuehrt zu keiner
  Datensaatzaufnahme und beschaedigt vorhandene Daten nicht.
- **Unvollstaendige Daten**: Ein Datensatz ohne Scan/GATT/Erkennung gilt als
  unvollstaendig und kann keinen Fingerprint bestaetigen.

## 8. Testergebnisse

Ausgefuehrt mit OpenJDK 17.0.21 und Android SDK (`platforms;android-34`,
`build-tools;34.0.0`).

| Kommando | Ergebnis |
|---|---|
| `./gradlew testDebugUnitTest` | **BUILD SUCCESSFUL** |
| Test-Suites | **39** |
| Tests gesamt | **636** |
| Fehlgeschlagen | **0** |
| Fehler | **0** |
| Uebersprungen | **0** |
| Neue Suite `Phase174HardwareTestPersistenceTest` | **34 Tests, 0 Fehler** |
| Bestehende Suite `Phase173HardwareValidationTest` | **29 Tests, 0 Fehler** (unveraendert) |
| `./gradlew assembleDebug` | **BUILD SUCCESSFUL** |
| APK | `app/build/outputs/apk/debug/app-debug.apk`, **18.904.330 Bytes (ca. 18,9 MB)** |

Es wurden keine bestehenden Tests geloescht, deaktiviert oder uebersprungen.
`assembleDebug` haengt weiterhin (Phase-16.2-Schutz) an
`testDebugUnitTest`.

Abgedeckte neue Testbereiche: Speichern/Laden, Neustart-Simulation, leerer
Store, mehrere Datensaetze, defekter Hauptstand, Backup-Fallback,
fehlgeschlagener Schreibvorgang, Schreib-Exception, Export/Import-Roundtrip,
ungueltiges JSON, unbekannte Schema-Version, fehlende Pflichtfelder, doppelte
IDs, unbekannter Fingerprint, MAC-Maskierung, keine Seriennummern,
Import-Duplikate ohne Ueberschreiben, ungueltiger Import ohne Datenverlust,
Filter, Audit ohne/mit Belegen, Widersprueche, mehrere Tests je Fingerprint,
fehlende Belege, kein Auto-Upgrade, alle Built-ins bleiben `NOT_TESTED`,
Kalender-/Schaltjahr-/Formatvalidierung.

## 9. Bekannte Warnungen

- `BleEngine.kt:932` — `writeDescriptor` ist in der Android-API als
  deprecated markiert. Bestehender Code, nicht Teil dieser Phase, nicht
  veraendert.
- Gradle meldet beim ersten Lauf fehlende Offline-Plugins, wenn `--offline`
  verwendet wird. Mit Netzwerk baut das Projekt normal.

## 10. Offene Punkte / manuell zu pruefen

- **Echte Hardwareerfassung**: Das Formular uebernimmt nur Daten, die
  tatsaechlich in der laufenden Sitzung vorliegen. Es wurden keine
  Hardwaretests, UUIDs, Hersteller-IDs oder Kompatibilitaeten erfunden. Ein
  realer Test auf einem Geraet steht weiterhin aus.
- **UI auf Geraet**: Anlegen/Bearbeiten/Loeschen, SAF-Import/-Export und die
  Qualitaetsanzeige sind kompiliert und unit-getestet, aber nicht auf einem
  physischen Android-Geraet durchgeklickt.
- **Backup-Verhalten**: Der Backup-Fallback ist per Fake-Persistence getestet;
  ein realer SharedPreferences-Dateischaden wurde nicht simuliert.
- **Filter-UI**: Die Filterlogik (`HardwareTestFilter`) ist implementiert und
  getestet; in der aktuellen Uebersicht wird sie noch nicht als interaktive
  Filterleiste angeboten. Die Liste ist nach Datum absteigend sortiert.
- **Kein automatisches Upgrade**: Bewusst offen — eine Bestaetigung wird nur
  gemeldet. Das Setzen von `CONFIRMED_ON_HARDWARE` bleibt ein manueller,
  dokumentierter Schritt.

## 11. Sicherheitsgrenzen (eingehalten)

Keine BLE-Schreibbefehle, kein Firmware-Flashen oder -Aendern, keine
Geschwindigkeitsbegrenzungs-Umgehung, keine automatischen
Scooter-Einstellungsaenderungen, keine unbekannten Steuerbefehle, keine
unbestaetigten Protokollfunktionen. Es wurden keine UUIDs, Hersteller-IDs oder
Features erfunden und keine ungetestete Kompatibilitaet behauptet.
