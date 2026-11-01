# Phase 16.3.2 — Ergebnisbericht

**Projekt:** Syrak ScooterLab (`syrak7305-bit/SyrakScooterLab`)
**Branch:** `feat/phase16.3.2-scan-and-detection` (abgezweigt von `main` @ `fa6efe2`)
**Auftrag:** „Allgemeine BLE-Verbindung und Geräteerkennung verbessern" (Teilaufträge A–F)
**Scope:** ausschließlich lesende Diagnose. Keine Tuning-, Firmware- oder Schreibbefehle
an unbekannte Geräte. Keine Aussage über Protokoll-Kompatibilität ohne Hardwaretest.

---

## 1. Kurzfassung

Die Ursachenkette der gemeldeten Symptome („16 Geräte gefunden, 0 Kandidaten",
„Discovery OK, 0 Frames", „10 Fehler", „verbunden, aber keine Telemetrie") ist im
Ist-Zustand-Bericht belegt und in dieser Phase an der Wurzel adressiert:

1. **Advertisement-Daten wurden nie ausgewertet.** Reale Scooter senden im
   Advertisement oft keinen Namen. Neu: `ScanObservation` + `AdvertisementAnalyzer`
   werten Service-UUIDs, Company-IDs und Service-Data aus — konservativ, ohne
   erfundene Werte.
2. **Der Platzhalter „Unknown Device" wurde als echter Name gespeichert** und
   überschrieb später gute Namen. Neu: `DeviceNameResolver` + `ScanAggregator`
   trennen „kein Name" strukturell von „Name vorhanden" und verhindern Regression.
3. **Ein erfolgreicher GATT-Connect wurde als Erfolg dargestellt.** Neu:
   `ConnectionStagePolicy` trennt drei Ebenen (Verbindung / Notify-Kette /
   tatsächliche Telemetrie). „Diagnose bereit" erscheint nur noch bei dekodierten
   Frames.
4. **Fehlerzählung war textgetrieben und Disconnects wurden pauschal gezählt.**
   Neu: Disconnect zählt nur noch als Fehler, wenn er unerwartet war; die
   Log-Text-Heuristik für Fehler wurde entfernt.

**Wichtig:** Die App ist durch diese Änderungen NICHT automatisch mit Ninebot,
Xiaomi oder NAVEE kompatibel. Eine verbesserte Erkennung ist ein Hinweis, kein
Kompatibilitätsnachweis. Das bleibt eine offene Hardwarefrage (siehe Abschnitt 6).

---

## 2. Geänderte und neue Dateien

### Neue Dateien (reine Kotlin-Logik, ohne Android-Abhängigkeit, unit-testbar)
| Datei | Zweck |
|---|---|
| `app/src/main/java/com/syrak/scooterlab/ScanObservation.kt` | Rohe Advertisement-Beobachtung + UUID-Normalisierung + `DeviceNameSource` |
| `app/src/main/java/com/syrak/scooterlab/DeviceNameResolver.kt` | Namensauflösung inkl. Herkunft, Platzhalter-Verwerfung, Regressionsschutz |
| `app/src/main/java/com/syrak/scooterlab/AdvertisementAnalyzer.kt` | Konservative Scooter-Hinweise aus Service-UUID/Company-ID/Service-Data |
| `app/src/main/java/com/syrak/scooterlab/ScanAggregator.kt` | Aggregation pro Adresse (seenCount, firstSeen, RSSI, Name, Evidenz) |
| `app/src/main/java/com/syrak/scooterlab/ConnectionStagePolicy.kt` | Ehrliche Trennung GATT-Verbindung vs. Telemetrie |
| `app/src/test/java/com/syrak/scooterlab/Phase1632ScanDetectionTest.kt` | 44 neue Tests |

### Geänderte Dateien
| Datei | Änderung |
|---|---|
| `MainActivity.kt` | Scan-Callback wertet Advertisement aus; Aggregation; ehrliche Namens-/Herkunfts-/Hinweiszeilen; `connectionStage()` delegiert an `ConnectionStagePolicy`; Disconnect-Fehler nur bei unexpected; textbasierte Fehlerzählung entfernt; `lastDiagSnapshot`; `onReady`-Text neutral |
| `Models.kt` | `ScooterDevice.name` nullable + `nameSource` + `displayName()`/`hasRealName`; `ScooterModel.detect`/`detectDetailed` akzeptieren `String?` |
| `Phase12Support.kt` | `ScanClassification` um `evidenceLabel` + `nameSource` erweitert (Defaults, rückwärtskompatibel); neuer Overload `classify(ScanObservation)` |
| `BleConstants.kt` | `NAME_PREFIXES` als `LEGACY_UNCONFIRMED` dokumentiert (nicht gelöscht) |

**Keine Funktion wurde entfernt.** Der Diff enthält keine gelöschten `fun`-Deklarationen
(geprüft mit `git diff | grep '^-' | grep 'fun '`).

---

## 3. Was die neuen Module fachlich tun (und was ausdrücklich nicht)

### AdvertisementAnalyzer
- Reihenfolge: Name → starke Service-UUID → Company-ID → schwache Service-Data.
- **Starke Hinweise** (nur `PROJECT_CONSTANT`-UUIDs aus `BleUuids`):
  `e003` → Ninebot-Familie, `ffe0` → Navee-Familie. Beide sind ausdrücklich als
  „Hinweis, KEIN Protokollnachweis" begründet.
- **Schwacher Hinweis:** `fe95` (MiBeacon) wird von sehr vielen Xiaomi-Geräten
  benutzt → allein KEIN Scooter-Hinweis, rechtfertigt keinen Kandidaten.
- **CompanyIdRegistry ist bewusst leer.** Im Projekt liegt kein belegbarer
  Bluetooth-SIG-Company-ID für einen Scooter-Hersteller vor. Es wird kein Wert
  geraten. Der Mechanismus ist vollständig implementiert und getestet (per
  Override-Map), sodass ein belegter Wert später ohne Codeänderung genügt.
- Aus der MAC-Adresse wird nichts abgeleitet.

### ScanClassifier.classify(ScanObservation)
- Ein bekanntes Modell kommt weiterhin NUR aus dem Namen.
- Ein Advertisement-Hinweis macht ein Gerät höchstens `PROBABLE_SCOOTER`
  (Konfidenz „Hinweis (Advertisement)", Modell „unbekannt") — niemals `KNOWN_SCOOTER`.

### DeviceNameResolver / ScanAggregator
- „Unknown Device" und andere Platzhalter gelten nie als echter Name.
- Ein Callback ohne Namen kann einen bereits bekannten Namen nicht überschreiben.
- `firstSeenMs` bleibt konstant, `rssi` ist immer der neueste Wert, `seenCount`
  zählt hoch, Service-UUIDs werden monoton vereinigt, der stärkste Hinweis bleibt.

### ConnectionStagePolicy
- `engineReady` ohne dekodierte Frames → `CONNECTED_NO_TELEMETRY` (Warnung),
  niemals Erfolg.
- Nur `decodeSuccess > 0` → `TELEMETRY_ACTIVE` (Erfolg).

---

## 4. Test- und Build-Ergebnisse (exakt)

| Kennzahl | Vorher (Baseline) | Nachher |
|---|---|---|
| Test-Suites | 32 | 33 |
| Testfälle | 441 | **485** (+44) |
| Fehler | 0 | 0 |
| Errors | 0 | 0 |
| Skipped | 0 | 0 |
| `assembleDebug` | erfolgreich | erfolgreich |
| APK-Größe | 18,7 MB | 18.740.490 Bytes (~17,9 MB) |

Kommando: `./gradlew testDebugUnitTest --no-daemon` → `BUILD SUCCESSFUL`.
Kommando: `./gradlew assembleDebug --no-daemon` → `BUILD SUCCESSFUL`.
Rohauswertung der JUnit-XML: 485 `<testcase>`, 0 `failures`, 0 `errors`, 0 `skipped`.

Die 44 neuen Tests decken ab: UUID-Normalisierung (4), Namensauflösung (6),
Advertisement-Analyse (7), ScanClassifier (4), Scan-Aggregation (7),
ConnectionStagePolicy (9), PollGate (2), Hardware-Testbericht (3),
ScooterDevice-Anzeige (2).

---

## 5. Belegstatus (Auftrag Punkt 11)

### Im Code implementiert
- Advertisement-Auswertung (Service-UUID, Company-ID, Service-Data), UUID-Normalisierung.
- Namensauflösung mit Herkunft, Platzhalter-Verwerfung, Regressionsschutz.
- Scan-Aggregation pro Adresse.
- Ehrliche Verbindungsstufen (3 Ebenen).
- Disconnect-Fehler nur bei unexpected; Entfernen der textbasierten Fehlerzählung.
- `NAME_PREFIXES` als Legacy markiert.

### Durch automatisierte Tests belegt
- Alle 44 neuen Tests (siehe Abschnitt 4) — inkl. der Kernforderung, dass
  `engineReady` mit 0 Frames NICHT als Erfolg gilt und dass `e003`/`ffe0` nur
  Kandidatenstatus (nicht Modell/Kompatibilität) erzeugen.

### Auf echter Hardware bestätigt
- **Nichts.** Es fand kein Hardwaretest statt. Alle Aussagen zu echtem
  Notify-Verhalten, Frames und Dekodierung bleiben UNVERIFIED.

### Nicht verifiziert / offen
- Ob reale Ninebot-/Xiaomi-/NAVEE-Scooter tatsächlich `e003`/`ffe0` im
  Advertisement bewerben (viele senden Service-UUIDs nur im GATT-Baum, nicht im
  Advertisement). Das ist eine reine Hardwarefrage.
- Ob die Notify-Kette auf realen Geräten vollständig durchläuft.
- Ob die Frame-Dekodierung (Byte-Offsets) korrekt ist.

---

## 6. Ungeklärte Hardwarefragen

1. **Advertisement-Inhalt realer Scooter:** Bewerben Ninebot/Xiaomi/NAVEE im
   Advertisement überhaupt `e003`/`ffe0`/`fe95`, oder erscheinen diese UUIDs erst
   nach der Service-Discovery? Ohne diese Information bleibt die
   Advertisement-Erkennung für viele Geräte wirkungslos (dann greift erst der
   GATT-Name über `0x2A00`).
2. **Notify-Kette:** Läuft `setCharacteristicNotification` → CCCD-Write →
   `onDescriptorWrite` auf den Zielgeräten erfolgreich durch?
3. **R11 — `onCharacteristicChanged` (3-Argument-Variante):** Diese Variante
   wurde auf ausdrückliche Anweisung NICHT geändert. Risiko: Auf API 33+ kann je
   nach Stack die 3-Arg-Variante (mit `BluetoothGattCharacteristic`) statt der
   2-Arg-Variante geliefert werden; wird nur eine überschrieben, können
   Notifications unbemerkt verloren gehen. Das muss in einem gezielten
   Hardwaretest geprüft werden, bevor daran etwas geändert wird.
4. **Fehlerzuordnung:** Ohne das Jayno-Export-JSON lassen sich einzelne der
   gemeldeten „10 Fehler" nicht abschließend einer Ursache zuordnen. Die
   verbleibende, belegbare Ursache ist die pauschale Disconnect-Zählung
   (jetzt behoben).

---

## 7. Sicherheits- und Ehrlichkeitsgrenzen

- Es wurden keine BLE-UUIDs, Company-IDs, Kommandos, Checksummen oder
  Decoder-Offsets erfunden.
- Unbekannte Geräte/Protokolle bleiben ausdrücklich `UNKNOWN`/unbestätigt.
- Ein Advertisement-Treffer ist niemals eine Kompatibilitätsaussage.
- Es wurden keine Schreib-/Tuning-/Firmware-Funktionen ergänzt.
- Der Legacy-Stack (`BleManager`) wurde nur dokumentiert, nicht gelöscht und
  nicht umgebaut.

---

## 8. Git

- Branch: `feat/phase16.3.2-scan-and-detection`
- Kein Merge nach `main` ohne ausdrückliche Freigabe.
- Push auf den Feature-Branch erfolgt (siehe Abschlussmeldung).
