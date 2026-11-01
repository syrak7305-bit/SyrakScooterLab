# Syrak ScooterLab — Phase 16.3.4 Ergebnisbericht

**Ziel:** Verbesserung der BLE-Beobachtbarkeit und Fehlerdiagnose auf Basis des
Phase-16.3.3-Audits (Ninebot F3 Pro).

**Branch:** `feat/phase16.3.4-observability`
**Basis:** `feat/phase16.3.2-scan-and-detection` @ `630ca81`
**Scope:** Read-only-Diagnose. Keine unbekannten BLE-Schreibbefehle, keine
Firmware-/Tuning-Funktionen, keine Decoder-Offset-Änderungen, keine
angenommenen UUID-Rollen.

> **Wichtig zur Basis:** `origin/main` (`fa6efe2`) enthält Phase 16.3.2 noch
> NICHT. Da die Teilziele A4/A5 auf den 16.3.2-Modulen (`ScanObservation`,
> `ScanClassifier.classify(observation)`, `DeviceNameSource`) aufbauen, wurde
> 16.3.4 auf `feat/phase16.3.2-scan-and-detection` aufgesetzt. Der Pull Request
> enthält daher **16.3.2 + 16.3.4**, solange 16.3.2 nicht separat gemerged ist.

---

## 1. Umgesetzte Änderungen (A1–A6)

### A1 — UUID jeder empfangenen Notification protokollieren

**Status:** Im Code umgesetzt, durch automatisierte Tests belegt.

- `TelemetryDiagnostics` führt jetzt `notificationsByUuid: LinkedHashMap<String, Long>`.
  `recordChunk(byteCount, atMs, characteristicUuid: String? = null)` zählt pro
  UUID. Der Parameter ist optional (bestehende Aufrufer/Tests bleiben gültig).
- `TelemetryDiagnosticsSnapshot.notificationsByUuid` transportiert die Karte;
  `summary()` gibt sie als `rx/Char=…` aus.
- `BleEngine.onCharacteristicChanged` liest `ch.uuid` und reicht sie über
  `handleNotification(value, uuid)` an `recordChunk(...)` weiter.
- `Listener.onRawNotification(bytes, characteristicUuid: String? = null)` —
  Default-Parameter, bestehende Implementierungen kompilieren unverändert.
- `FrameRecord.characteristicUuid` (Default `null`) wird in JSON und CSV
  serialisiert.
- `NotifyDiagnosticsRecord.notificationsByUuid` wird in JSON, CSV und Summary
  ausgegeben.

**Beleg:** Es wird ausschließlich die UUID gespeichert (kein Roh-Frame, keine
MAC). Die Rolle einer UUID wird NICHT unterstellt.

### A2 — Disconnect-Statuscodes im Hardwarebericht speichern

**Status:** Im Code umgesetzt, durch automatisierte Tests belegt.

- `ConnectionRecord.disconnectStatus: Int? = null` (0 = GATT_SUCCESS).
- Neuer Default-Listener-Callback `onDisconnectStatus(status: Int)`, wird in
  `onConnectionStateChange` (STATE_DISCONNECTED) **vor** `onDisconnected`
  aufgerufen.
- `MainActivity.htDisconnectStatus` wird bei Verbindungsaufbau zurückgesetzt,
  im Callback gespeichert und in `ConnectionRecord` übernommen.
- JSON (`"disconnectStatus"`), CSV (`connection,disconnectStatus`) und Summary
  (`Status=…`) geben den Code unverändert aus — keine Umdeutung.

### A3 — Diagnosezähler bei Verbindungsabbrüchen nicht unnötig zurücksetzen

**Status:** Im Code umgesetzt, durch automatisierte Tests (Snapshot-Semantik)
belegt; das konkrete Reset-Verhalten ist quellcode-verifiziert (nicht
hardware-getestet).

- `resetConnectionState(resetDiagnostics: Boolean = true)`.
- Bei `STATE_DISCONNECTED` und beim bewussten `disconnect()` wird mit
  `resetDiagnostics = false` aufgerufen: Notifications/Bytes/Frames/Zähler je
  UUID bleiben erhalten. Ein **neuer** Verbindungsaufbau (connect/reconnect/
  CONNECTED) setzt sie weiterhin zurück (Default `true`).
- `buildHardwareReport()` nimmt die Notify-Diagnose der letzten Sitzung auch
  nach einem Disconnect auf. Ob aktive oder beendete Sitzung, ergibt sich aus
  `connection`/`identity`/`notifyActive` — es wird nichts unterstellt.

### A4 — Gerätename und erkanntes Modell getrennt anzeigen

**Status:** Im Code umgesetzt, durch automatisierte Tests belegt.

- Neue reine Logik `DeviceDisplayLabels`:
  - `deviceName(name)` → ehrlicher Fallback `unbekannt (kein Name empfangen)`.
  - `modelLabel(model, confidence)` → `Modell unbekannt […]` bei unbekanntem
    Modell, sonst `Anzeigename [Sicherheit]`.
- Info-Tab zeigt jetzt eine eigene Zeile
  `Gerät: <Name> · Adresse: <MAC>` **getrennt** von `ERKANNTES MODELL`.
- Statuszeile nach `onReady`: `Verbunden: <Gerätename> · <Modell> [Sicherheit] …`.
- `activeIdentityName` fällt **nicht mehr** auf die MAC-Adresse zurück (kein
  MAC-als-Name im Bericht).

### A5 — Scan-Liste mit vollständiger `ScanObservation` klassifizieren

**Status:** Im Code umgesetzt, durch automatisierte Tests belegt.

- Neue Map `scanObservations: Map<Address, ScanObservation>`, gefüllt im
  Scan-Callback, geleert beim Scan-Reset.
- `classifyScan(device)` nutzt die vollständige Beobachtung
  (`ScanClassifier.classify(observation)`), Fallback auf die Namens-Heuristik
  nur, wenn keine Beobachtung vorliegt (z. B. Demo-Gerät).
- `updateDeviceList()` und der Herstellerfilter verwenden `classifyScan(...)`
  statt `ScanClassifier.classify(name, address)`.

### A6 — Ninebot-Notify-Präferenz: untersucht, NICHT geändert

**Status:** Untersucht; **keine funktionale Änderung** (bewusste Entscheidung).

Die Ninebot-Verzweigung übergibt `FFF1/FFF2` als *bevorzugte Notify-UUIDs*.
Laut `BleUuids`-Provenienz sind FFF1/FFF2 ausdrücklich **Dual-Role-Kandidaten**
(Write UND möglicherweise Notify); ob sie tatsächlich Notify tragen, ist ohne
GATT-Mitschnitt **nicht entschieden**.

- `GattCharacteristicSelector.pickNotify` filtert zuerst auf
  `notifiable == true`. Eine nicht-notify-fähige UUID kann daher **nie** als
  Notify gewählt werden — auch nicht, wenn sie in der Präferenzliste steht.
- Eine „Korrektur“ würde entweder eine Notify-UUID erfinden/annehmen (verboten)
  oder die Auswahl-Semantik ohne Hardwarebeleg ändern (nicht eindeutig).
- Daher: **keine Änderung**, stattdessen Klarstellung im Code + Regressionstest
  (`write-only UUID wird nie als Notify gewählt`).

---

## 2. Geänderte / neue Dateien

**Geändert:**
- `app/src/main/java/com/syrak/scooterlab/TelemetryDiagnostics.kt`
- `app/src/main/java/com/syrak/scooterlab/BleEngine.kt`
- `app/src/main/java/com/syrak/scooterlab/MainActivity.kt`
- `app/src/main/java/com/syrak/scooterlab/diagnostics/HardwareTestReport.kt`

**Neu:**
- `app/src/main/java/com/syrak/scooterlab/DeviceDisplayLabels.kt`
- `app/src/test/java/com/syrak/scooterlab/Phase1634ObservabilityTest.kt`

---

## 3. Testergebnisse

| Zeitpunkt | Suites | Tests | Failures | Errors | Skipped |
|-----------|--------|-------|----------|--------|---------|
| Baseline (16.3.2) | 33 | 485 | 0 | 0 | 0 |
| **Nach 16.3.4** | **34** | **500** | **0** | **0** | **0** |

- Neue Testklasse `Phase1634ObservabilityTest`: **15 Tests**, decken A1–A6 ab.
- Voller Lauf mit `./gradlew testDebugUnitTest --rerun-tasks` (erzwungene
  Ausführung, keine UP-TO-DATE-Wiederholung).

## 4. Build-Ergebnis

- `./gradlew assembleDebug` → **BUILD SUCCESSFUL**.
- APK: `app/build/outputs/apk/debug/app-debug.apk`, **19.122.186 Bytes (~18,2 MiB)**.
- Einzige Compiler-Warnung: bekannte `writeDescriptor`-Deprecation (unverändert).

---

## 5. Evidenzstatus (ehrlich)

| Aussage | Status |
|---------|--------|
| UUID je Notification wird erfasst/gespeichert | Im Code + automatisierte Tests |
| Disconnect-Statuscode wird gespeichert/exportiert | Im Code + automatisierte Tests |
| Zähler überleben Disconnect (Reset-Trennung) | Im Code; Snapshot-Semantik getestet; **nicht** hardware-getestet |
| Gerätename/Modell getrennt | Im Code + automatisierte Tests |
| Scan-Liste mit vollständiger Beobachtung | Im Code + automatisierte Tests |
| Ninebot-Notify-Präferenz | Untersucht, **nicht** geändert (unbeweisbar ohne GATT-Mitschnitt) |
| Reale Hardware-Kompatibilität (Ninebot F3 Pro etc.) | **NICHT** behauptet — kein Hardwaretest in dieser Phase |

## 6. Offene Fragen / nächste Schritte (nur mit Hardware)

1. **Realer F3-Pro-Mitschnitt:** Welche Characteristic liefert tatsächlich
   Notifications? Der neue `notificationsByUuid`-Zähler beantwortet das beim
   nächsten Test — ohne eine Rolle zu unterstellen.
2. **Disconnect-Status:** Welche Codes treten real auf (z. B. 8 = Timeout,
   19 = Remote-Terminate, 133 = GATT_ERROR)? Der Bericht hält sie jetzt fest.
3. **Notify-Aktivierung:** Ob der CCCD-Write auf der gewählten Char erfolgreich
   ist, zeigt `notifyDiagnostics` (Kette + `notificationsByUuid`).

## 7. Sicherheitsgrenzen (eingehalten)

- Keine unbekannten BLE-Schreibbefehle hinzugefügt.
- Keine Firmware-/Tuning-Funktionen.
- Keine Decoder-Offset-Änderungen.
- Keine erfundenen UUIDs/Rollen; FFF1/FFF2 bleiben Dual-Role-Kandidaten.
- Bestehende Funktionen und andere Adapter nicht entfernt/umgebaut.
