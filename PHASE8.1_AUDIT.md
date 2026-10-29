# PHASE 8.1 — Finaler Sicherheitsaudit vor Merge

**Projekt:** Syrak ScooterLab · `com.syrak.scooterlab`
**Geprüfter PR:** #10 (bereits nach `main` gemerged, Merge-Commit `442e9bf`)
**Audit-Branch:** `fix/phase8.1-security-audit`
**Basis:** `main` @ `442e9bf`

> **Wichtig:** PR #10 war zum Zeitpunkt des Audits bereits **gemerged**. Der Audit wurde daher auf dem gemergten `main`-Stand durchgeführt; die Korrekturen liegen auf einem neuen Branch und werden als eigener PR eingereicht.

---

## 1. Gefundene Probleme

### Befund A — Umgehung des Tuning-Gates über den Polizei-Modus (Punkt 3) — **HOCH**
`confirmPoliceMode()` sendete den Speed-Ceiling-Befehl (CMD 0x1F) **direkt über `sendCommand(..., true)`** und lief damit komplett am `TuningSafetyGate` vorbei. Das ist exakt die im Auftrag genannte Umgehung „über alternative Funktionen oder direkte sendCommand-Aufrufe“. Der Befehl war auch bei **unbekanntem Modell** und **ohne Hardware-Freigabe** ausführbar.

### Befund B — Automatischer Unlock-Handshake an unbekannte Geräte (Punkt 1) — **HOCH**
In `BleEngine.afterMtu()` wurde der Unlock-Handshake (`AA 55 01 01 01`, CMD 0x01 / Payload 0x01) **automatisch beim Verbindungsaufbau** gesendet, sobald der Adapter `requiresUnlock` meldet. Der Adapter wird allein aus dem GATT-Layout (Service `FFE0`) gewählt. Folge: **Jedes** unbekannte Gerät, das zufällig `FFE0` exponiert, erhielt ungefragt einen Schreibbefehl — ohne Modell- und ohne Hardware-Verifikation. Das verstößt gegen Regel 6 („keine unbekannten BLE-Schreibbefehle“) und gegen die Auftragsfrage „Kann er bei unbekanntem Modell ausgeführt werden?“ → **Ja**.

### Befund C — Not-Aus-Rampe überlebt Verbindungsabbruch (Punkt 2) — **MITTEL**
`onDisconnected()` beendete eine laufende Not-Aus-Rampe **nicht**. Die Rampe tickte weiter (Schreibversuche wurden still verworfen), und `rampActive` blieb bis zu 10 s `true`. In diesem Fenster hätte ein **erneuter Not-Aus wirkungslos abgebrochen** (`beginSafeStopRamp()` bricht bei `rampActive == true` sofort ab). Das ist eine sicherheitsrelevante Fehlerbehandlungslücke bei Verbindungsabbruch.

### Befund D — Falsche Erfolgsmeldung im Log (Punkt 3, Nebenwirkung von A) — **NIEDRIG**
`confirmPoliceMode()` protokollierte „Polizei-Modus gesetzt“ und setzte `maxSpeedKmh`, **unabhängig davon**, ob der Befehl tatsächlich gesendet wurde.

### Geprüft und **unauffällig**
- **Not-Aus-Pfad:** Emergency-Cut + Rampe sind eine **kontrollierte** Schleife (`RAMP_MAX_TICKS`, 400 ms Takt), keine unkontrollierte Schreibschleife. Not-Aus ist ausschließlich über den expliziten Panik-Button (mit Bestätigungsdialog) auslösbar, nicht automatisch. Verhalten bleibt erhalten.
- **Firmware-Flash:** `startHardFlashIfVerified()` prüft `validation.valid` **und** `validation.hashVerified` **und** `detectedModel.isKnown` **und** `engineReady`. `FirmwareValidator` verlangt Struktur, Modellbezug (Modell-Token im Datei-/Katalognamen) und – falls hinterlegt – passenden SHA-256; ohne hinterlegten Hash bleibt `hashVerified = false` → Flash gesperrt. Chunk-Timeout (3 s) und `failPendingWrites` bei Disconnect brechen sauber ab. **Kein Problem gefunden.**
- **Weitere Schreibpfade:** `enqueueCommand`/`startFlash` werden ausschließlich aus `sendCommand` (→ `sendTuning`/Not-Aus/Read) bzw. `startHardFlashIfVerified` aufgerufen. `enqueueWrite` ist `private`. `KillSwitch` liegt im **toten** Compose-Pfad und ist von `MainActivity` nicht erreichbar.
- **Tuning-Gate-Grundlogik:** Standard gesperrt; unbekanntes Modell bleibt auch bei manueller Bestätigung gesperrt (`canSendTuning` = `hardwareVerified && modelKnown`). Manuelle Bestätigung ist bewusst **keine** Hardware-Verifikation — sie wird im UI-Text ausdrücklich als Sitzungs-Freigabe bezeichnet.

---

## 2. Geänderte Dateien

| Datei | Art | Änderung |
|---|---|---|
| `app/src/main/java/com/syrak/scooterlab/DeviceIdentification.kt` | geändert | Neues reines Objekt `AutoHandshakePolicy` (Befund B) |
| `app/src/main/java/com/syrak/scooterlab/BleEngine.kt` | geändert | Eager-Handshake an `AutoHandshakePolicy` gekoppelt; `setAutoHandshakeAllowed(...)` |
| `app/src/main/java/com/syrak/scooterlab/MainActivity.kt` | geändert | Polizei-Modus über `sendTuning` (A/D), Freigabe-Setzen bei Connect + CONFIG-Toggle, `endSafeStopRamp()` bei Disconnect (C), `sendTuning` liefert `Boolean` |
| `app/src/test/java/com/syrak/scooterlab/DeviceIdentificationTest.kt` | geändert | +8 Tests (`AutoHandshakePolicyTest`, `TuningGateBypassTest`) |
| `PHASE8.1_AUDIT.md` | **neu** | Dieser Bericht |

---

## 3. Behobene Sicherheitslücken

1. **Gate-Umgehung geschlossen (A/D):** Polizei-Modus läuft jetzt über `sendTuning()` und ist bei unbekanntem Modell bzw. fehlender Freigabe gesperrt. `maxSpeedKmh` und die Erfolgsmeldung werden nur noch bei tatsächlich freigegebenem Versand gesetzt; sonst ERROR-Log „NICHT gesetzt“.
2. **Kein Eager-Schreibbefehl an unbekannte Geräte (B):** Der Unlock-Handshake beim Verbindungsaufbau wird nur gesendet, wenn Modell erkannt **und** Hardware bestätigt sind (`AutoHandshakePolicy.shouldSendEagerHandshake`). Sonst wird die Unterdrückung nachvollziehbar geloggt. Der **Lazy**-Handshake in `enqueueCommand()` bleibt unverändert — Tuning nach Freigabe und **Not-Aus** funktionieren weiterhin vollständig.
3. **Not-Aus nach Disconnect wieder sofort scharf (C):** `onDisconnected()` ruft `endSafeStopRamp()` auf; `rampActive` wird zurückgesetzt, sodass ein erneuter Not-Aus nach Reconnect nicht mehr blockiert wird.

**Bewusst NICHT verändert:** Not-Aus-Verhalten (Emergency-Cut + Rampe), Flash-Pfad, Tabs, Buttons, PIN, Profil, Dashboard, Diagnose, Ladebildschirm, Haftungstext.

---

## 4. Testergebnisse

`./gradlew testDebugUnitTest` → **BUILD SUCCESSFUL**

| Kennzahl | Wert |
|---|---|
| Testklassen | **19** |
| Tests gesamt | **158** |
| Failures | **0** |
| Errors | **0** |
| Skipped | **0** |

Neu hinzugefügt (8): `AutoHandshakePolicyTest` (6) — unbekanntes Modell, nicht verifizierte Hardware, beides unbekannt, Freigabe nur bei bekannt+verifiziert, Adapter ohne Unlock, Begründungstexte; `TuningGateBypassTest` (2) — manuelle Bestätigung schaltet unbekanntes Modell nicht frei, Polizei-Modus-Frame ist wie jeder Tuning-Write gegated.
**Keine** bestehenden Tests entfernt oder abgeschwächt (vorher 150, jetzt 158).

---

## 5. Build-Ergebnis

`./gradlew --no-build-cache --no-configuration-cache --refresh-dependencies assembleDebug --stacktrace` → **BUILD SUCCESSFUL**

- APK: `app/build/outputs/apk/debug/app-debug.apk`
- Größe: **18.584.838 Bytes** (~17,72 MB)

**CI-Hinweis:** Der aktive GitHub-Actions-Workflow führt weiterhin **keine** Unit-Tests aus (`testDebugUnitTest` kommt darin nicht vor). Die Tests wurden **ausschließlich lokal** ausgeführt. Es wird nicht behauptet, dass GitHub Actions Tests ausgeführt hat.

---

## 6. Tatsächlicher aktueller Commit-Hash

- **Basis (`main`):** `442e9bfaca03499e31640dae773834cb477d06d4`
- **Audit-Commit:** `6c3e83981dd75899beca0c36f977b8d6a5695730`
- **Pull Request:** https://github.com/syrak7305-bit/SyrakScooterLab/pull/11 (OPEN, MERGEABLE, nicht auto-gemerged)

---

## 7. Bestätigung: keine bestehenden Funktionen entfernt

Der Diff umfasst **4 Dateien, +239 / −10 Zeilen**. Alle Entfernungen sind reine Ersetzungen innerhalb derselben Funktion:
- der alte Eager-Handshake-Block (ersetzt durch gegateten Block),
- der direkte `sendCommand`-Aufruf im Polizei-Modus (ersetzt durch `sendTuning`),
- die Signatur von `sendTuning` (um `Boolean`-Rückgabe erweitert).

Es wurden **keine** Tabs, Buttons, Slider, Toggles, Einstellungen oder Funktionen gelöscht oder umbenannt. Slider (5), Toggle-Reihen (7) und Tab-Referenzen (22) sind unverändert vorhanden.

---

## 8. Hardware-Hinweis

Es wurde **keine** echte Hardware getestet. Es wird **keine** Hardware-Kompatibilität behauptet. Die Korrekturen sind reine Logik-/Ablaufsicherungen und durch Unit-Tests bzw. Code-Review belegt, nicht durch einen Gerätetest. Es wurden **keine** Protokollwerte erfunden; der Handshake-Frame `AA 55 01 01 01` ist der bereits im Code dokumentierte Brightway-Wert.
