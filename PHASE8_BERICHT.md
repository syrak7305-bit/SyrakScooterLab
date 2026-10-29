# Phase 8 — Abschlussbericht
## BLE-Stabilität, Geräteerkennung und Multi-Brand-Vorbereitung

**Projekt:** Syrak ScooterLab · `com.syrak.scooterlab`
**Zielgerät:** NAVEE GT3 Max
**Branch:** `feat/phase8-device-identification-and-tuning-safety`
**Code-Commit:** `bde80d966217e5ff36f0f174fad710427f8159c0`
**Branch-HEAD (inkl. Bericht):** `50c1d317f8ad3d634c383f051ee0e3232e832c1a`
**Pull Request:** https://github.com/syrak7305-bit/SyrakScooterLab/pull/10

> **Grundregel eingehalten:** Es wurden **keine** bestehenden Funktionen, Tabs, Buttons oder Einstellungen entfernt oder umbenannt. Ladebildschirm, Haftungstext, PIN, Profil, Dashboard, Diagnose, Flash und Tuning bleiben vollständig erhalten. Alle Änderungen sind backward-kompatibel.

> **Hardware-Hinweis:** Es wurde **keine** echte Hardware getestet. Es wird **keine** Hardware-Kompatibilität behauptet. Es wurden **keine** erfundenen BLE-UUIDs, Protokoll-Offsets oder Firmware-Daten eingeführt.

---

## 1. Geänderte Dateien

| Datei | Art | Inhalt |
|---|---|---|
| `app/src/main/java/com/syrak/scooterlab/DeviceIdentification.kt` | **NEU** | Reine, Android-freie Logik: `ScooterManufacturer`, `ScooterCandidateFilter`, `TuningSafetyGate` |
| `app/src/test/java/com/syrak/scooterlab/DeviceIdentificationTest.kt` | **NEU** | 19 Unit-Tests (Hersteller, Kandidatenfilter, Gate, KuKirin-Regression) |
| `app/src/main/java/com/syrak/scooterlab/Models.kt` | geändert | `ScooterModel.manufacturer`, `DetectionResult.manufacturer`, Hersteller-Konsistenz-Guard in `detectDetailed` |
| `app/src/main/java/com/syrak/scooterlab/MainActivity.kt` | geändert | Hersteller/Modell/Sicherheit getrennt angezeigt, Erkennungs-Logging, `sendTuning()`-Gate, CONFIG-Freigabe-Toggle, TOOLS-Sperrbanner |
| `app/src/main/java/com/syrak/scooterlab/BleEngine.kt` | geändert | `onServicesDiscovered`-Fehlerstatus bricht sofort ab (statt 15-s-Hänger) |

---

## 2. Neu implementierte Verbesserungen

**Geräteerkennung (Punkt 2 & 5):** Die Erkennung hatte bisher nur eine Ebene (Modell-Keywords), Hersteller und Modell waren vermischt, **KuKirin fehlte komplett**, und der Kandidatenfilter lag als private Inline-Liste im `ScanCallback` (nicht testbar). Neu werden fünf Schichten sauber getrennt: `ScooterManufacturer` (Hersteller), `ScooterModel` (Modell), `DetectionConfidence` (Sicherheit), `ScooterCandidateFilter` (Scan-Kandidaten) und `TuningSafetyGate` (Sicherheitsfreigabe). Ein Bluetooth-Name allein ist eine **Heuristik, kein Beweis** — unbekannte Modelle bleiben `UNKNOWN`, und Hersteller/Modell/Sicherheit werden in der UI und im Log getrennt geführt.

**Tuning-Sicherheits-Gate (Punkt 6):** Tuning-Schreibbefehle sind standardmäßig **gesperrt** und nur nach bewusster Bestätigung der Hardware-Kompatibilität in CONFIG freigegeben (nicht persistiert, gilt pro Sitzung). `sendTuning()` gated 12 Tuning-Callsites (maxSpeed, zeroStart, kers, tempGuard, cruise, lock, overspeedMute, ride-Modi, germanManeuver, softPatch). **Not-Aus (`emergencyCut`), Read-Poll, Unlock-Handshake und der Flash-Pfad bleiben bewusst NICHT gegated**, damit Sicherheits- und Diagnosefunktionen weiter funktionieren.

**Multi-Brand-Architektur (Punkt 5):** Die Trennung von Hersteller-Profil (`ScooterManufacturer`) und Modell-Profil (`ScooterModel` mit `manufacturer`-Property) ist jetzt explizit und durch Tests abgesichert. Die BLE-Kommunikation (`BleEngine`) und die Diagnose bleiben unverändert getrennt. Es wurden keine neuen Herstellerprotokolle erfunden.

**BLE-Stabilität (Punkt 3):** Der Audit bestätigte, dass Scan-Timeout, Connect-Timeout, Stale-GATT-Filter, Switch-Teardown, Reconnect-Guard und `failPendingWrites` bereits aus Phase 4–7 vorhanden sind. Eine belegte Lücke wurde geschlossen (siehe Punkt 3).

---

## 3. Behobene Bugs

**Bug A — Falsche Modellzuordnung (Punkt 2):** `"KuKirin G2 Max"` wurde über das generische Token `"g2"` fälschlich als **Ninebot Max G2** eingestuft. Ein neuer Hersteller-Konsistenz-Guard sorgt dafür, dass nur Modelle des im Namen erkannten Herstellers gewinnen können. Regressionstest `kukirin g2 max is not misidentified as ninebot max g2` hinzugefügt.

**Bug B — GATT-Service-Discovery hängt 15 s (Punkt 3):** Bei `onServicesDiscovered` mit Fehlerstatus wurde bisher nur geloggt und zurückgekehrt; die UI blieb bis zum 15-s-Connect-Timeout im Zustand „Verbinde…“. Jetzt wird die Verbindung sofort sauber abgebrochen (analog zum `discoverServices()==false`-Pfad). Der Connect-Timeout bleibt als Sicherheitsnetz scharf.

---

## 4. Erfolgreiche Tests und Testanzahl

**Lokal ausgeführt:** `./gradlew testDebugUnitTest` → **BUILD SUCCESSFUL**

| Kennzahl | Wert |
|---|---|
| Testklassen | 17 |
| Tests gesamt | **150** |
| Fehler (Failures) | **0** |
| Errors | **0** |
| Skipped | **0** |

Neue Tests: **19** in `DeviceIdentificationTest` (Hersteller-Klassifikation inkl. KuKirin/SoFlow/Segway-Präzedenz, Hersteller/Modell/Sicherheit-Trennung, KuKirin-Regression, Kandidatenfilter, Tuning-Gate). Es wurden **keine** bestehenden Tests entfernt oder abgeschwächt.

---

## 5. Build-Ergebnis und APK-Größe

Befehl: `./gradlew --no-build-cache --no-configuration-cache --refresh-dependencies assembleDebug --stacktrace` → **BUILD SUCCESSFUL**

- APK: `app/build/outputs/apk/debug/app-debug.apk`
- Größe: **18.584.028 Bytes** (~17,72 MB)

**CI-Realität (Punkt 8):** Der aktive GitHub-Actions-Workflow (`.github/workflows/build.yml`) enthält **keinen** `testDebugUnitTest`-Schritt (0 Treffer). Die Unit-Tests wurden daher **ausschließlich lokal** ausgeführt. Es wird **nicht** behauptet, dass GitHub Actions Unit-Tests ausgeführt hat. (Der GitHub-App-Token besitzt keine `workflows`-Berechtigung, daher kann der Workflow nicht um den Test-Schritt erweitert werden; ein vorbereiteter Vorschlag liegt als `ci/build.yml.proposed` vor.)

---

## 6. Noch offene Probleme

- **CI führt keine Unit-Tests aus** (siehe Punkt 5). Bis der Workflow um `testDebugUnitTest` erweitert ist, sind Tests rein lokal.
- **GATT-Service-Discovery-Fehlerpfad** ist Android-Callback-gebunden und nicht per Unit-Test abgedeckt; die Änderung stützt sich auf den unveränderten Connect-Timeout als Backstop und wurde durch Code-Review, nicht durch einen Gerätetest, verifiziert.
- **Keine echte Hardware-Validierung** — siehe Punkt 7.

---

## 7. Auf echter Hardware noch nicht verifizierte Funktionen

Es wurde **keine** Hardware getestet. Unverifiziert bleiben insbesondere:

- BLE-Verbindungsaufbau und Service-/Characteristic-Discovery am NAVEE GT3 Max
- MTU-Verhandlung und tatsächliche Notify-/Write-Pfade
- Telemetrie-Decodierung und Dashboard-Anzeige mit echten Frames
- Alle Tuning-Schreibbefehle (jetzt zusätzlich durch das Sicherheits-Gate gesperrt)
- Not-Aus / Unlock-Handshake
- Firmware-Flash-Pfad (bleibt durch Hash-Verifikation und Modell-Prüfung gesperrt)
- Reconnect-Verhalten nach echten Abbrüchen (GATT 133, Reichweite)

**Es wird keine Hardware-Kompatibilität behauptet.**

---

## 8. PR-Link und Commit-Hash

- **Pull Request:** https://github.com/syrak7305-bit/SyrakScooterLab/pull/10
- **Commit:** `bde80d966217e5ff36f0f174fad710427f8159c0` (kurz: `bde80d9`)
- **Branch:** `feat/phase8-device-identification-and-tuning-safety`
- **Basis:** `main` (nach Merge von PR #9, `771380b`)
- Der PR wird **nicht** automatisch gemerged.
