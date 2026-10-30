# SYRAK SCOOTERLAB – PHASE 16.1
## Abschlussbericht: Audit, BLE-Reparatur und Build-Validierung

**Bearbeitungsbranch:** `feat/phase16-ble-reliability`
**Ausgangsbasis:** `main` @ `f2c908a` (Phase 15), Phase-16-Commit `123c343`
**Ergebnis-Commit:** `d0841cd` (Phase 16.1)
**Pull Request:** #21 (offen, **nicht gemergt**)
**Datum der Validierung:** siehe Commit-Zeitstempel

> **Ehrlichkeitshinweis vorab:** In Phase 16.1 stand **kein reales Fahrzeug** zur
> Verfügung. Alle Aussagen unten sind ausdrücklich getrennt in *im Code
> implementiert*, *per Unit-Test belegt*, *simuliert* und *an realer Hardware
> geprüft*. Es gab **keine** Hardware-Prüfung. Es wird **keine** Funktion am
> realen Scooter als bestätigt behauptet.

---

## 1. Ausgangszustand des Repositories (unabhängig geprüft)

Das Repository `syrak7305-bit/SyrakScooterLab` wurde frisch geklont und der
Zustand **nicht** aus der Aufgabenstellung übernommen, sondern selbst gemessen:

- `main` HEAD = `f2c908a` („Merge pull request #20 … feat/phase15-flash-integration“).
- `origin/feat/phase16-ble-reliability` = `123c343` („Phase 16: BLE reliability,
  real read callback, protocol audit & firmware identity guard“).
- Der Branch war **exakt 1 Commit voraus und 0 Commits hinter** `main`; der
  Merge-Base war `f2c908a`. Die in der Aufgabe genannte Basis war damit korrekt.
- Der Diff `main..phase16` umfasste 13 Dateien mit +1549/−55 Zeilen. Neue
  Dateien: `BLUETOOTH_PROTOCOL_AUDIT.md`, `BleReadSupport.kt`, `BleUuids.kt`,
  `Phase16SupportTest.kt`.
- Projekt: Android/Kotlin, Gradle 8.9 Wrapper, `compileSdk=34`, `minSdk=26`,
  `targetSdk=34`, JVM-Target 17, Version-Catalog, Package
  `com.syrak.scooterlab`.
- Tatsächlicher UI-Einstiegspunkt ist **`MainActivity`** (klassische Android-
  Views via `setContentView`, kein Compose-Root). Die Compose-Dateien
  (`DashboardScreen`, `DashboardViewModel`) werden von `MainActivity` **nicht**
  aufgerufen.

**Bestandsaufnahme-Ergebnis:** Das Projekt enthält zwei BLE-Generationen, einen
aktiven Pfad und einen Legacy-Pfad (siehe Punkt 3). Phase 16 war real vorhanden.

---

## 2. Gefundene Probleme

1. **Belegter UUID-Rollen-Widerspruch:** `NinebotAdapter` und
   `NaveeGt3MaxAdapter` führten **dieselben UUIDs gleichzeitig als Write- und
   als Notify-Characteristic**. Beim Navee widersprach das dem dokumentierten
   Brightway-Layout (FFE1 = Notify, FFE2 = Write). Beim Ninebot war die reale
   Rolle von FFF1/FFF2 ohne GATT-Mitschnitt nicht entscheidbar.
2. **Registry vs. Adapter inkonsistent:** `BleUuids` deklarierte FFF1/FFF2 nur
   als `WRITE_CHARACTERISTIC`, während die Adapter sie zusätzlich als Notify
   behaupteten. Es gab **keinen Test**, der Registry und Adapter gegeneinander
   absicherte.
3. **Zwei parallele BLE-Stacks** ohne klare Laufzeit-Abgrenzung im Code.
4. **Read-Pfad** war in Phase 16 bereits grundsätzlich gehärtet, aber die
   geforderten Randfälle (Timeout, Disconnect, Late-Callback, Parallelität)
   waren nicht durch eigene Tests abgedeckt.
5. **Compatibility-Ladder** war 7-stufig kumulativ, aber die geforderte
   Einzelbeweisführung je Stufe fehlte als Test.
6. **Firmware-Import-Schutz** existierte, war aber nicht mit Tests für alle
   Ablehnungsgründe (Paket/Hash/Modell) abgesichert.

---

## 3. Die zwei BLE-Stacks – Architektur-Audit

**Aktiver Pfad (Laufzeit):** `MainActivity` → `BleEngine` →
`ScooterProtocolAdapter` (`NinebotAdapter`, `NaveeGt3MaxAdapter`). `BleEngine`
besitzt den `BluetoothGatt`, die Service-Discovery, die Characteristic-Auswahl,
den Write-Queue und den Read-Tracker. `BleSessionService` wird von
`MainActivity` nur als Vordergrunddienst zur Prozess-Priorisierung gestartet und
besitzt **keinen** eigenen GATT-Client.

**Legacy-Pfad (nicht im Produktionsfluss):** `SyrakApp.container` →
`AppContainer` → `core.ble.BleManager`, außerdem `BleConstants`, `BleModels`,
`DashboardViewModel`/`DashboardScreen`. `AppContainer.container` ist `by lazy`
und wird **von niemandem** angesprochen; `DashboardRoute` wird von
`MainActivity` nicht aufgerufen. Der Legacy-Stack ist damit zur Laufzeit
**inaktiv**.

**Können die Stacks widersprüchliche Zustände erzeugen?** Nein, solange der
Legacy-Container nicht instanziiert wird. Er wird aktuell nicht instanziiert.
Eine echte Doppelverbindung entstünde nur, wenn jemand `container.bleManager`
aktiv nutzt. **Legacy-Funktionen wurden nicht entfernt.** Empfohlene sichere
Migration (nur dokumentiert, nicht umgesetzt): `BleManager` zunächst als
Adapter hinter `BleEngine` legen, dann `DashboardViewModel` auf den aktiven
Engine-Listener umstellen, zuletzt `BleConstants` auf `BleUuids` reduzieren.
Das ist bewusst **nicht** in Phase 16.1 erfolgt, um keine Risiko-Migration
einzuführen.

---

## 4. UUID-Widerspruch und tatsächliche Code-Korrektur

**Untersuchte Dateien:** `BleUuids.kt`, `NinebotAdapter.kt`,
`NaveeGt3MaxAdapter.kt`, `BleEngine.kt`, `FrameBuilders.kt`, `BleConstants.kt`,
`BLUETOOTH_PROTOCOL_AUDIT.md`. Geprüfte UUIDs: `0000e003`, `0000fff1`,
`0000fff2`, `0000ffe0`, `0000ffe1`, `0000ffe2`.

**Befund:** Die Registry war intern widerspruchsfrei (`conflicts()` leer), aber
die Adapter behaupteten Rollen, die die Registry nicht kannte. Das ist ein
**nachweisbarer Codefehler**, weil zwei Codepfade dieselbe UUID unterschiedlich
klassifizierten.

**Korrektur (ohne erfundene Rollen):**

- `UuidDefinition` erhielt das Feld `candidateRoles`. Eine UUID kann damit
  mehrere **Kandidatenrollen** tragen.
- FFF1/FFF2 sind als Kandidaten für **Write UND Notify** registriert
  (`roleAmbiguous = true`), weil ihre reale Rolle ohne GATT-Mitschnitt nicht
  entscheidbar ist. `confirmedOnHardware` bleibt `false`.
- `NinebotAdapter` und `NaveeGt3MaxAdapter` leiten Service-/Write-/Notify-Listen
  jetzt aus `BleUuids.candidateUuids(family, role)` ab. Damit ist eine
  Abweichung zwischen Registry und Adapter strukturell ausgeschlossen.
- Beim Navee sind die Rollen jetzt eindeutig: **FFE1 = Notify, FFE2 = Write**.
- **Keine UUID wurde erfunden, keine UUID geändert, keine Rolle als bewiesen
  markiert.**

Die Runtime-Auswahl in `BleEngine` nutzt ohnehin die vom Gerät gemeldeten
GATT-Properties (`GattCharacteristicSelector.pickNotify`/`pickWrite`) und nicht
die Adapterlisten; die Adapterlisten sind Deklarations-Metadaten. Die Korrektur
ändert daher **kein** Laufzeitverhalten, sondern beseitigt den Widerspruch in
der Deklaration.

---

## 5. Read-Pfad-Audit (`BleEngine`)

- **Single-in-flight:** `ReadTracker.begin(...)` weist einen zweiten parallelen
  Read hart ab (`REJECTED`). Das entspricht der Android-Einschränkung, dass pro
  GATT-Verbindung nur eine Operation ausstehen darf.
- **Korrelation:** Ein Callback zählt nur, wenn `isCurrentGatt(g)` gilt **und**
  `readTracker.matches(uuid, connectionGeneration)` wahr ist. UUID **und**
  Verbindungsgeneration müssen passen.
- **Erfolg:** `ReadClassifier.fromCallback(status, GATT_SUCCESS, byteCount)`
  liefert nur bei `status == GATT_SUCCESS` **und** `byteCount > 0` den Status
  `SUCCESS`. Ein leerer Wert ist `EMPTY_VALUE`, ein Fehlerstatus `GATT_ERROR`.
- **Timeout:** `READ_TIMEOUT_MS = 3_000`. Der Timeout-Runnable entfernt den
  Pending-Read und emittiert `ReadStatus.TIMEOUT` – nie `SUCCESS`.
- **Disconnect:** `STATE_DISCONNECTED` ruft `resetConnectionState()`, das
  `abortPendingRead(...)` ausführt und `ReadStatus.ABORTED`/`DISCONNECTED`
  meldet. Ein Read kann nach Disconnect nicht als Erfolg erscheinen.
- **Late/Duplicate Callbacks:** Durch Generation + UUID-Match werden Callbacks
  alter Verbindungen oder alter Requests ignoriert (Test belegt).
- **API 33+:** Es wird der 3-arg `onCharacteristicRead` überschrieben. Unter
  API 33+ ruft das Framework den 4-arg-Callback auf, dessen Basis-Implementierung
  den Wert in die Characteristic schreibt und an den 3-arg-Callback
  weiterleitet; `ch.value` wird danach gelesen. Damit funktioniert der Pfad auf
  API 26–34+. Die Einschränkung „nur ein Read gleichzeitig“ ist bewusst und
  dokumentiert.
- **Read-Policy:** Auto-Read nur bei bekanntem Modell **und** verifizierter
  Hardware. Unbekannte Geräte erfordern einen manuellen Read.

**Bewertung:** Der Read-Pfad erfüllt die geforderte Regel „Erfolg nur bei
passendem Callback + `GATT_SUCCESS` + Bytes + korrekter Request-Zuordnung“.
Belegt durch Unit-Tests, **nicht** an realer Hardware.

---

## 6. Compatibility-Ladder-Audit (7 Stufen)

`CompatibilityStage`: `DETECTED`, `GATT_DISCOVERED`, `READ_PROPERTY_FOUND`,
`READ_ONLY_TESTED`, `TELEMETRY_VALIDATED`, `WRITE_PROTOCOL_VALIDATED`,
`HARDWARE_VERIFIED`. `CompatibilityLadder.evaluate()` ist **kumulativ**: Eine
Stufe gilt nur, wenn sie selbst und alle Vorgänger belegt sind. Eine
widersprüchliche Eingabe (z. B. `hardwareVerified` ohne `detected`) wird
automatisch entschärft.

`MainActivity` trennt korrekt `readPropertyFound` (lesbare Characteristic
vorhanden) von `readOnlyTested` (`readSuccessCount > 0`, also echter Callback).
`telemetryValidated`, `writeProtocolValidated` und `hardwareVerified` werden
separat geführt. **Keine Stufe wird durch einen Vorgänger automatisch
freigeschaltet.** Neue Tests prüfen jede der 7 Stufen einzeln sowie das Stoppen
bei einer übersprungenen Stufe.

---

## 7. Firmware- und Sicherheits-Audit

- **`FirmwareImportGuard.evaluate(...)`** lehnt ab bei: ungültiger Prüfung
  (`NO_VALIDATION`), abweichendem/fehlendem Paket (`PACKAGE_MISMATCH`), nicht
  verifiziertem Hash (`HASH_NOT_VERIFIED`) und nicht belegtem Modellbezug
  (`MODEL_MISMATCH`). Nur bei vollständiger Übereinstimmung `ALLOWED`.
- **`FlashHonesty.hardFlashAllowed(...)`** ist nur wahr, wenn Validierung
  gültig, Hash verifiziert, Modell kompatibel, Modell bekannt **und** verbunden.
- **`FirmwareStorage`** begrenzt auf 512 KB; `FirmwareValidator` prüft Struktur
  und SHA-256. Eine fremde Datei kann nicht unter einem fremden Paketnamen
  gespeichert werden, weil der Import die Paket-ID bindet.
- **`WriteGuard`**: unbekanntes Modell → `BLOCKED_UNKNOWN_MODEL`; nicht
  verifizierte Hardware → `BLOCKED_HARDWARE_NOT_VERIFIED`; fehlender Adapter →
  `BLOCKED_NO_MODEL_ADAPTER`; read-only-Adapter → `BLOCKED_READ_ONLY_ADAPTER`.
  Lesen bleibt immer erlaubt.
- **`TuningSafetyGate`** und **`AutoHandshakePolicy`** sind unverändert: Tuning
  und Eager-Handshake nur bei bekanntem Modell **und** verifizierter Hardware.
- **Emergency Stop / Police Mode / Reconnect / Timeout** bleiben erhalten; es
  wurde keine Schutzfunktion entfernt oder abgeschwächt.
- **Unbekannte Geräte bleiben read-only.** Ein per UUID/Name erkannter Scooter
  schaltet **keine** Schreibfreigabe frei.

---

## 8. Feature-Erhaltung

Alle geforderten Bereiche sind weiterhin vorhanden: INFO, TOOLS, DASHBOARD,
FLASH, CONFIG, Splash, Haftungstext, BT-Scan, BT-Verbindung, Geräteerkennung,
GATT-Diagnose, Read-Funktion, Telemetrie, Hardware-Testbericht, JSON-/CSV-
Export, RSSI-Logging, Tuning, Servo-Start, Max-Speed, Panic-Button, Key-
Combos, Profile, PIN-System, Firmware-Vorschläge, Firmware-Import, Emergency
Stop, Police Mode, Reconnect, Berechtigungen und Statusanzeigen. Es wurde
**keine** Funktion entfernt. Nicht verifizierbare Funktionen bleiben bestehen
und sind als unverifiziert gekennzeichnet.

---

## 9. Geänderte Dateien (Phase 16.1)

| Datei | Änderung |
|-------|----------|
| `app/src/main/java/com/syrak/scooterlab/BleUuids.kt` | `candidateRoles`, `roleAmbiguous`, `candidateUuids()`, `rolesOf()`, `isRoleAmbiguous()`; FFF1/FFF2 als mehrdeutige Kandidaten |
| `app/src/main/java/com/syrak/scooterlab/NinebotAdapter.kt` | Rollenlisten aus Registry abgeleitet |
| `app/src/main/java/com/syrak/scooterlab/NaveeGt3MaxAdapter.kt` | Rollenlisten aus Registry abgeleitet; FFE1=Notify, FFE2=Write |
| `app/src/test/java/com/syrak/scooterlab/Phase161BleRoleAuditTest.kt` | **neu**, 21 Regressionstests |
| `BLUETOOTH_PROTOCOL_AUDIT.md` | Korrektur dokumentiert |
| `PHASE16.1_ABSCHLUSSBERICHT.md` | dieser Bericht |

---

## 10. Regression und Testergebnisse

**Baseline vor der Änderung:** 28 Testklassen, 354 Tests, 0 Fehler, 0 skipped.

**Nach der Änderung** (`./gradlew --no-build-cache --no-configuration-cache
testDebugUnitTest --stacktrace`):

- Testklassen: **29**
- Tests: **375** (davon 21 neu)
- Fehlgeschlagen: **0**
- Fehler: **0**
- Übersprungen: **0**
- Ergebnis: **BUILD SUCCESSFUL**

Es wurden **keine** Tests gelöscht, übersprungen oder abgeschwächt.

---

## 11. APK-Build und SHA-256

Befehl: `./gradlew --no-build-cache --no-configuration-cache assembleDebug
--stacktrace`

- Ergebnis: **BUILD SUCCESSFUL**
- Datei: `app-debug.apk`
- Größe: **18.691.338 Bytes** (≈ 17,8 MiB)
- SHA-256: **`470b449884cf0a13f0aa9bea4dbbe44ffd07169c19ccdeffcd295cd20822534f`**

Es handelt sich um ein **Debug-APK**, nicht um ein release-signiertes APK. Eine
Release-Signierung wird ausdrücklich **nicht** behauptet. Die CI
(`.github/workflows/build.yml`) baut `assembleDebug` und lädt das Artefakt
`SyrakScooterLab-debug-apk` hoch; der lokale Build ist identisch konfiguriert.

---

## 12. GitHub: Branch, Commit, PR

- Branch: `feat/phase16-ble-reliability`
- Neuer Commit: **`d0841cd`** („Phase 16.1: make BLE UUID roles honest and
  registry-derived“)
- Push: erfolgreich (`123c343..d0841cd`)
- Pull Request: **#21**, Status **OPEN**, `mergeable = MERGEABLE`
- **Kein Merge, kein Auto-Merge, kein Squash-Merge.** Der PR wartet auf
  ausdrückliche Nutzerfreigabe.

---

## 13. Verbleibende Risiken

1. **Keine Hardware-Verifikation:** Weder Ninebot- noch Navee-Transport ist an
   einem realen Fahrzeug bestätigt (`confirmedOnHardwareCount = 0`).
2. **Ninebot-Rollen FFF1/FFF2** bleiben ohne GATT-Mitschnitt unbelegt; sie sind
   bewusst als mehrdeutige Kandidaten geführt.
3. **Legacy-Stack** ist inaktiv, aber weiterhin vorhanden. Eine spätere
   Migration ist dokumentiert, aber nicht umgesetzt.
4. **API-33+-Read** nutzt den 3-arg-Callback über die Framework-
   Rückwärtskompatibilität; das ist korrekt, aber nicht auf einem realen
   API-33-Gerät getestet.
5. **Firmware-Dateien** sind nur so vertrauenswürdig wie die hinterlegten
   SHA-256-Werte; es werden keine Herstellerfreigaben erfunden.
6. **Speicher:** Der Gradle-Daemon stürzte bei einem Build-Lauf einmal ab
   (OOM/`DaemonDisappearedException`); ein erneuter Lauf mit denselben Flags war
   erfolgreich. Das ist eine Umgebungs-, keine Codeursache.

---

## 14. Hardware-Status – klare Trennung

| Kategorie | Status |
|-----------|--------|
| Im Code implementiert | Ja (UUID-Rollenmodell, Read-Tracker, Ladder, Firmware-Guard) |
| Durch Unit-Tests belegt | Ja (29 Klassen / 375 Tests, 0 Fehler) |
| Simuliert | Nur Logik-Tests auf der JVM, keine GATT-Simulation |
| An realer Hardware getestet | **Nein** |
| Durch reales Scooter-Protokoll bestätigt | **Nein** |

**Fazit:** Phase 16.1 behebt den belegten UUID-Rollen-Widerspruch, härtert die
Deklarationen gegen erfundene Rollen ab, ergänzt die geforderten
Regressionstests und liefert ein erfolgreich gebautes Debug-APK. Die offene
Hardware-Frage bleibt offen und ist im Code und in der UI sichtbar – nicht
versteckt.
