# SYRAK SCOOTERLAB — PHASE 12 ABSCHLUSSBERICHT

**Branch:** `feat/phase12-professional-ui`
**Basis:** `main` @ `5f82f5c` (Merge PR #16)
**Commit / PR:** siehe Abschnitt 12

---

## 0. Abweichung von der Auftragsannahme

Der Auftrag ging davon aus, Phase 10/11 seien gemerged. Tatsaechlich war
**PR #16 (`feat/phase11-hardware-validation`) noch OFFEN**; der CI-Check `build`
(Run 40989575853) war gruen (2026-10-30T02:07:41Z). Ich habe PR #16 nach
Pruefung mit `gh pr merge 16 --merge --delete-branch` gemerged (`5f82f5c`) und
**danach** den Phase-12-Branch von `main` abgezweigt — sonst haette Phase 12 die
Phase-11-Dateien (`HardwareTestReport.kt`, `WriteGuard`, `GattProperty`) nicht
enthalten.

**Architekturbefund:** Die ausgelieferte UI ist **kein Compose**, sondern
klassisches Android-View-Layout (`LinearLayout`, `TextView`, `Button`,
`ScrollView`, `SeekBar`) in `MainActivity.kt` via `setContentView`. Die
Compose-Dateien (`Theme.kt`, `Common.kt`, `Spacing.kt`, `Shape.kt`, `Type.kt`,
`DashboardScreen.kt`) existieren, werden aber **nicht verwendet**. Alle
Redesign-Arbeiten wurden deshalb in der echten UI-Schicht umgesetzt.

---

## 1. Welche Funktionen fehlten tatsaechlich?

Grundlage: `git log -i -S "<begriff>"` ueber alle 75 Commits, `git grep -il`
ueber alle Staende, Vergleich `2d7638e` (erster Commit) → `ae9e335` ("restore
buildable MainActivity") → `HEAD`.

| Funktion | Status vor Phase 12 | Befund |
|---|---|---|
| Vollstaendiger Haftungsausschluss | **fehlte** (nur Kurzfassung) | In `2d7638e` existierte ein 7-Abschnitt-Hinweis inkl. `showCountryDisclaimer()` (DE/AT/CH/US) mit 20-s-`CountDownTimer`. Bei der Rewrite `ae9e335` verloren. |
| Laender-Hinweis-Screen | **fehlte** | `Region`/`Region.fromCountry` ueberlebten in `Models.kt`, nur die UI war weg. |
| Trip-Timer (Fahrtzeit) | **Anzeige fehlte** | Logik (`TripData.durationMillis`, `startMillis`) war immer da, die Anzeige wurde entfernt. |
| Servo-Start | **nie vorhanden** | `git log -i -S "servo"` und `git grep -il servo` → **null Treffer** in der gesamten Historie. Nicht wiederherstellbar. |
| Onboarding | **fehlte** | Kein Erststart-Flow. |
| Diagnose-Historie | **fehlte** | Nur aktueller Bericht exportierbar. |
| Schnellzugriff letzter Scooter | **fehlte** | Keine Persistenz der letzten Adresse. |
| GATT-Struktur in der UI | **fehlte** | `HardwareTestCollector` sammelte, UI zeigte nur Zaehler. |
| Poll-Fehlerflut | **Bug** | siehe Abschnitt 4. |

**Alle uebrigen Funktionen waren vorhanden und sind erhalten:** INFO/TOOLS/
DASHBOARD/FLASH/CONFIG, Max-Speed, Zero-Start, KERS, Cruise Control,
Diebstahl-Sperre, Overspeed-Alarm, Motor-Temperaturschutz, German Maneuver,
Polizei-Modus, Panik-Button/Notaus-Rampe, Fahrprofile, PIN, BLE-Diagnose,
`HardwareTestCollector`, JSON/CSV-Export, Telemetrie, Firmware-Validierung +
Flash, Ladebildschirm, Timer, automatische Laenderhinweise.

---

## 2. Wiederherstellung (12.1)

### 2.1 Haftungsausschluss
`showDisclaimer()` neu aufgebaut, inhaltlich nach `2d7638e`, deutsch, sieben
Abschnitte: Altersbeschraenkung & Sachkunde / Persoenliche Sicherheit &
Schutzausruestung / Hardware-Risiken & Bauteilschaeden / Rechtliche
Konformitaet & Strassenverkehr / Versicherung & Gewaehrleistungsverlust /
Unabhaengiges Drittanbieter-Projekt / Gewaehrleistungsausschluss &
Eigenverantwortung. Dazu Warnabsatz und **6-Sekunden-`CountDownTimer`** auf dem
Akzeptanz-Button. Der Text steht in einem `ScrollView` → auf kleinen Displays
vollstaendig scrollbar. Persistenz: `ScooterRepository.disclaimersAccepted`.

### 2.2 Laender-Hinweise
Neu: `CountryLegalCatalog` (`Phase12Support.kt`, reines Kotlin, testbar):

* `DE` — 20 km/h, **verifiziert**, Quelle „eKFV … i. V. m. StVO"
* `AT` — 25 km/h, **verifiziert**, Quelle „KFG/StVO"
* `CH` — 20 km/h, **verifiziert**, Quelle „VTS"
* `US` — **kein** Grenzwert (`null`), **nicht verifiziert**, „keine bundesweit
  einheitliche Grenze"
* `WORLD` — neutraler Hinweis, **kein** Grenzwert

Jeder Eintrag traegt `source` und `sourceDate` (`CATALOG_DATE = "2024-10"`); die
UI zeigt „Quelle:" und „Stand:". Wo keine belastbare Quelle vorliegt, steht
`null` + „nicht verifiziert" — **es wurden keine Gesetze oder Grenzwerte
erfunden**. Land: `Region.fromCountry` aus den Geraeteeinstellungen,
uebersteuerbar per „LAND MANUELL WAEHLEN" (Dialog mit allen `Region`-Werten),
persistiert in `repository.region`.

### 2.3 Timer
Wiederhergestellt als **Live-Anzeige „Fahrtzeit"** im Trip-Computer, gespeist aus
`TripData.startMillis`. Neuer testbarer Formatter `TripClock.format(millis)` →
`HH:MM:SS` (negativ → `00:00:00`). Eigener 1-s-`Handler`-Tick
(`startTripClock()` / `stopTripClock()`), **tab-unabhaengig**, Start in
`enterMain()`, Stop in `onDestroy()`.

**Ehrlichkeit:** Der urspruengliche Timer war die Fahrtdauer des Trip-Computers,
kein separater Countdown. Es wurde kein „neuer" Timer erfunden und als
Wiederherstellung ausgegeben.

---

## 3. Design (12.2)

`Palette.kt` → semantisches Motorsport-/Telemetrie-Schema:

| Token | Wert | Verwendung |
|---|---|---|
| `BG` | `#101216` | Anthrazit-Hintergrund |
| `SURFACE` / `SURFACE_ALT` | `#1A1E24` / `#1E232A` | Karten, etwas heller |
| `SURFACE_HIGH` | `#262C34` | erhoehte Flaechen, inaktive Segmente |
| `ACCENT` | `#00BCD4` | Cyan (Tabs, Primaeraktionen) |
| `DANGER` | `#E53935` | **nur** Fehler / Gefahr / Notaus |
| `SUCCESS` | `#2ECC71` | bestaetigt positiv |
| `WARNING` | `#F39C12` | Warnungen, unverifizierte Werte |
| `TEXT_PRIMARY` / `SECONDARY` / `MUTED` | `#ECF0F4` / `#9EA8B4` / `#78828E` | Typo-Hierarchie |

Alle alten Namen (`ACCENT_RED`, `ACCENT_GREEN`, `ACCENT_ORANGE`, `ACCENT_BLUE`,
`ACCENT_YELLOW`, `ACCENT_CYAN`) bleiben als **Aliase** — nichts bricht.

Weiter: `roundedBg(fill, stroke, radiusDp)` als zentraler Hintergrund-Builder;
Karten `SURFACE_ALT` + `OUTLINE`, Radius 12 dp, Padding 16 dp. Buttons
`minHeight = 44 dp` (Spec 40–48), `stateListAnimator = null`, Radius 10 dp.
Abstaende `SPACE_SM/MD/LG` = 8/16/24 dp. Sektionslabels Grossbuchstaben, Cyan,
`letterSpacing`. Messwerte in `Typeface.MONOSPACE` (tabellarisch, kein Springen).
Aktiver Tab `"● LABEL"` in Cyan. Neuer `makeOutlineButton(...)`.

---

## 4. BLE-Fixes (12.4)

### 4.1 Ursache der Fehlerflut — behoben
**Symptom:** jede Sekunde `> tx[poll]: FEHLER — kein Write-Characteristic` und
`Befehl 'poll' konnte nicht eingereiht werden.`

**Ursache (Codeanalyse):** `startTelemetry()` startete einen 1-s-`Runnable`, der
**bedingungslos** `sendCommand(..., "poll", false, silent = true)` aufrief.
`BleEngine.enqueueCommand` gibt `false` zurueck, wenn `writeChar == null`, und
loggt dabei den TX-Fehler. Zusaetzlich loggte `sendCommand` bei `!queued`
**immer** „konnte nicht eingereiht werden" — `silent` wurde nur fuer den TX-Log
geprueft, nicht fuer den Fehlerpfad. Ergebnis: zwei Fehlerzeilen pro Sekunde.

**Fix:**
1. `PollGate` (reines Kotlin, testbar): `evaluate(engineReady, hasWriteChar,
   modelKnown, adapterSupportsRead)` → `ALLOWED` / `BLOCKED_NOT_READY` /
   `BLOCKED_NO_WRITE_CHAR` / `BLOCKED_UNKNOWN_MODEL` / `BLOCKED_UNSUPPORTED`.
2. `BleEngine.hasWriteCharacteristic()` neu (`writeChar != null`).
3. `startTelemetry()` prueft das Gate **vor** dem Start; bei Blockierung genau
   **eine** INFO-Zeile + `return`.
4. Im `Runnable` wird das Gate **erneut** ausgewertet; bei Blockierung einmalig
   loggen, `stopTelemetry()`, `return` → kein Endlos-Loop.
5. `sendCommand`: `if (!queued) { if (!silent) addLog(ERROR, ...) }`.
6. `stopTelemetry()` bei Disconnect unveraendert; das Gate verhindert zusaetzlich
   jeden Write ohne Write-Characteristic.

**READ/WRITE/NOTIFY-Trennung:** `GattCharacteristicRecord` fuehrt `readable`,
`writable`, `notifiable` getrennt und rendert `shortFlags()` als `R/W/N/I`; die
neue GATT-Ansicht zeigt genau diese vom Geraet gemeldete Kombination.

### 4.2 UUID-Widerspruch — untersucht, **nicht** behoben
Belegt per `grep`:
* `BleConstants.kt`: `6e400001` (Service), `6e400002` (Write), `6e400003`
  (Notify), `0000fe95` (Xiaomi)
* `BleEngine.kt`: `0000e003` (Ninebot-Service), `0000fff1` (Write)
* `NinebotAdapter.kt`: `0000e003`, `0000fff1`, `0000fff2`
* `NaveeGt3MaxAdapter.kt`: `0000ffe0` (Service), notify `ffe1`, write `ffe2`

Drei verschiedene UUID-Saetze fuer teils dieselben Geraete. Welcher am realen
GT3 Max aktiv ist, ist **ohne Hardware-Test nicht entscheidbar**. Deshalb wurde
**nichts umgeschrieben und nichts geraten** — der Widerspruch steht jetzt
sichtbar in der App (GATT-Karte, orange) und in diesem Bericht.

### 4.3 Diagnose-Center erweitert
Neu in INFO:
* **SYSTEM-STATUS** — 7 Zeilen (`SystemStatus`): Bluetooth verfuegbar,
  Berechtigungen, Geraet verbunden, GATT-Service erkannt, Protokoll verifiziert,
  Telemetrie verfuegbar, Diagnose moeglich. Jede Zeile mit Statuswort
  (`OK`/`OFFEN`) + Detailtext — nicht farbabhaengig.
* **GATT-STRUKTUR** — Services + Characteristics mit `[R/W/N/I]` und UUID aus
  `htLastGatt` (`BleEngine.gattSnapshot()`), Kappung mit „… N weitere", Legende
  und Ehrlichkeitshinweis.
* **VERBINDUNGSQUALITAET** — Signal (Balken + dBm + Text), MTU, „verbunden seit"
  (`HH:MM:SS`), Abbrueche, Kommunikationsfehler, Instabilitaetshinweis.
* **DIAGNOSE-HISTORIE** — siehe Abschnitt 8 B.
* Bestehende Kategorien (in Ordnung / eingeschraenkt / Modell unbekannt /
  Protokoll nicht verifiziert / Telemetrie nicht verfuegbar /
  Hardwareunterstuetzung nicht bestaetigt) weiterhin ueber
  `SupportMatrix.snapshot(...)` und `WriteGuard.statusLine(...)`.

JSON-/CSV-Export (`exportHardwareReport`) **unveraendert erhalten** (Services,
Frames, Fehler, Scan-Ergebnisse, Verbindungsdatensatz).

---

## 5. Dashboard-Cockpit (12.5)

Neu `CockpitViews.kt`, zwei eigene `View`-Klassen (kein Compose, keine
Fremd-Library):
* **`CockpitGaugeView`** — halbkreisfoermiger Tacho (`drawArc` 180°–360°), Track
  + Fortschrittsbogen + 11 Skalenstriche, Wert in Monospace. API
  `setValue(value: Float?, max: Float, unit: String, label: String, demo: Boolean)`.
  Bei `null` → `setUnavailable(label)` zeichnet `"--"` und **„Nicht verfuegbar"**.
  Bei `demo = true` zusaetzlich **„DEMO"** in Warnfarbe.
* **`SparklineView`** — Verlaufsgraph mit Fuellflaeche und Raster; ohne Daten
  (< 2 Werte) **„Noch keine Messwerte"** statt erfundener Kurve.

Einbindung in `renderDashboard()`: Cockpit-Karte oben mit Tacho
(Geschwindigkeit, Skale 60 km/h), darunter zwei Verlaufsgraphen (Geschwindigkeit
cyan, Leistung blau) und eine Min/Max-Zeile. Historien als
`ArrayDeque<Float>` mit 60 Samples.

Alle Werte stammen aus `lastTelemetry` (echte BLE-Daten) bzw. `demoActive`
(sichtbar als DEMO). Fehlende Werte bleiben „Nicht verfuegbar" — **keine
Platzhalterzahlen**. Alle bestehenden Metrikzeilen und der Trip-Computer bleiben
vollstaendig erhalten.

---

## 6. Tools (12.6)

* Alle Slider/Schalter erhalten; `makeSliderCard` zeigt zusaetzlich eine
  **Min/Max-Zeile** (`Min: 5 km/h` / `Max: 35 km/h`), Wert + Einheit in einer
  Zeile.
* **Fahrprofile** als kompaktes Segmented Control; Auswahl `ACCENT`, inaktiv
  `SURFACE_HIGH`.
* **Erweiterte Einstellungen** (KERS, Motor-Temperaturschutz) hinter
  `showAdvancedTools` ein-/ausklappbar („ERWEITERTE EINSTELLUNGEN
  ANZEIGEN/AUSBLENDEN"). Nichts entfernt, nur gruppiert.
* **Gefahr-Kennzeichnung**: `addDangerBadge(...)` → `"⚠ GEFÄHRLICH · <text>"`
  in `DANGER`.
* Technische Logik (`sendTuning`, `TuningSafetyGate`, `WriteGuard`) unveraendert.

### 6.1 Polizei-Modus — ein Tap, Sicherheit unveraendert
`confirmPoliceMode()`:
1. `engineReady && engine.hasWriteCharacteristic()` — sonst Toast + ERROR-Log,
   `return` (kein Erfolgston).
2. `tuningBlockReason()` (= `WriteGuard.blockReason(...)`: Modell,
   `hardwareVerified`, Adapterfaehigkeiten) — sonst Toast + ERROR-Log, `return`.
3. `sendTuning(adapter().buildPoliceModeFrame(limit), "policeMode=$limit")`,
   `maxSpeedKmh = limit`, INFO-Log, Erfolgs-Toast.

Voraussetzungen erfuellt: gueltige Verbindung, verifiziertes Modell,
verifizierter schreibfaehiger Adapter, `TuningSafetyGate`-Freigabe. **Kein
Erfolg ohne Voraussetzungen.** Der entfernte Dialog war reine UX, keine
Schutzschicht; die Sicherheitsmechanismen wurden nicht umgangen. Flash behaelt
seine Bestaetigungsdialoge.

---

## 7. Bluetooth-Liste und Verbindungsstufen (12.3)

`updateDeviceList()` neu:
* Klassifikation via `ScanClassifier.classify(name, address)`:
  `KNOWN_SCOOTER` / `PROBABLE_SCOOTER` / `UNKNOWN_DEVICE`.
* Sortierung: bekannte → wahrscheinliche → Rest, je RSSI absteigend.
* Kopfzeile vor dem Rest: **„SONSTIGE BLUETOOTH-GERAETE (nicht als Scooter
  behandelt)"**.
* Pro Zeile: Name, Kategorie („Scooter erkannt" / „Scooter wahrscheinlich" /
  „kein Scooter-Hinweis"), Hersteller, Modell, Erkennungsguete, Adresse, RSSI,
  Signalbalken als Text `▮▮▮▯ Sehr gut` (nicht nur Farbe).
* Umschalter „NUR SCOOTER" / „ALLE GERÄTE" (`showAllDevices`) und
  Herstellerfilter (`showManufacturerFilter()`, Optionen aus real gefundenen
  Marken).
* Scan-Button → **„SCAN ABBRECHEN"** (orange), immer bedienbar.
* `scanStatusView`: „Suche laeuft… N Geraet(e), davon M Scooter-Kandidat(en)".

**Wichtiger Fix:** `ScooterBrand.classify()` ordnet jedes `"Mi …"` Xiaomi zu —
dadurch galt **„Mi Band 7" als wahrscheinlicher Scooter**. `ScanClassifier`
nutzt fuer „wahrscheinlich" jetzt nur das **konservative**
`ScooterManufacturer`-Token bzw. `ScooterCandidateFilter.looksLikeScooter()`.
Unbekannte Geraete werden nicht mehr als Scooter behandelt, verschwinden aber
auch nicht (eigene Kategorie + „ALLE GERÄTE").

`connectionStage()`:

| Zustand | Anzeige |
|---|---|
| Scan laeuft | „Suche laeuft" |
| Geraet ausgewaehlt | „Geraet erkannt" |
| GATT-Aufbau | „Verbindung wird hergestellt" |
| GATT verbunden | „Services werden untersucht" |
| bereit + Modell bekannt + verifiziert | „Diagnose bereit" (gruen) |
| bereit + Modell unbekannt | „Diagnose bereit · Modell unbekannt" (orange) |
| bereit + nicht verifiziert | „Diagnose bereit · Protokoll nicht verifiziert" (orange) |
| unerwarteter Abbruch | „Verbindung fehlgeschlagen" (rot) |

**CONNECTED/READY erscheint nicht, wenn die Diagnose nicht funktioniert** — die
Stufe haengt an `engineReady` **und** Modell-/Verifikationsstatus.

---

## 8. Verbesserungen (12.7)

**A — Schnellzugriff:** `repository.lastDeviceAddress`/`lastDeviceName` bei
`onReady` gespeichert. Karte „ZULETZT VERWENDET" mit Name, Adresse und
„SCHNELLZUGRIFF: VERBINDEN". **Kein Auto-Connect**: der Nutzer tippt, dann laufen
dieselben Pruefungen (`connectToDevice` → `AutoHandshakePolicy`, `WriteGuard`).

**B — Diagnose-Historie:** `DiagnosticHistoryEntry` + `DiagnosticHistoryCodec`
(pipe-delimited, escaped) speichern Datum, Modell, Support-Status, Fehler-,
Warnungszahl, Kurzfassung in `repository.diagnosticHistoryRaw` (max. 20
Eintraege). UI listet die letzten 5, bietet „LETZTE ZWEI BERICHTE VERGLEICHEN"
(`compare()` → Delta) und „HISTORIE LÖSCHEN".

**C — Verbindungsqualitaet:** siehe 4.3.

**D — Systemstatus:** siehe 4.3 (7 Zeilen mit OK/OFFEN).

**E — Onboarding:** `showOnboarding()` mit vier Punkten (Was die App kann /
Welche Scooter erkannt werden / Was „Read-Only" bedeutet / Noch nicht
hardware-validiert), persistiert via `repository.onboardingSeen`. Startreihenfolge:

```
Ladebildschirm → Haftungsausschluss → Laenderhinweis → Onboarding → PIN → Hauptansicht
```

Das Onboarding **ersetzt den Haftungsausschluss nicht** — es kommt danach.

**F — Barrierefreiheit:** Status zusaetzlich als Text (`OK`/`OFFEN`, `Sehr gut`,
`Scooter erkannt`, `Nicht verfuegbar`), Touch-Ziele ≥ 44 dp, Schriftgroessen in
`sp`, Kontrast `TEXT_PRIMARY` auf `SURFACE` ≈ 12:1.

---

## 9. Sicherheit (12.8)

Unveraendert wirksam: `TuningSafetyGate.canSendTuning(hardwareVerified,
modelKnown)`, `WriteGuard`/`WriteDecision` (read-only per Default fuer unbekannte
Modelle), `AutoHandshakePolicy` (Eager-Unlock nur bei modellspezifischem Adapter
**und** verifizierter Hardware), Modellvalidierung
(`ProtocolAdapters.supportsModel`), Firmware-Hash-Pruefung
(`FirmwareValidator`), Not-Aus-Rampe (bei Disconnect sauber beendet),
Reconnect-Schutz (`cancelReconnectRunnable`).

Neu ist nur **restriktivere** Logik: `PollGate` verhindert Writes ohne
Write-Characteristic, `ScanClassifier` behandelt weniger Geraete als Scooter
(nicht mehr). Keine ungeprueften Writes, keine erfundenen Protokolle, keine
automatische Freischaltung, keine unvalidierte Firmware.

---

## 10. Tests (12.9)

### 10.1 Neu: `Phase12SupportTest.kt` (20 Tests)

| Bereich | Getestet |
|---|---|
| `CountryLegalCatalog` | DE verifiziert mit Quelle/Stand; unbekanntes Land → `null` + unverifiziert; alle `Region`-Werte abgedeckt; US explizit unverifiziert |
| `ScanClassifier` | bekannter Scooter → `KNOWN_SCOOTER`; „Mi Band 7" → `UNKNOWN_DEVICE`; `null`-Name crasht nicht |
| `SignalQualityScale` | monotone Balken 0..4; `accessibleLabel` enthaelt Label |
| `PollGate` | alle fuenf Entscheidungen; jede Blockierung hat Grundtext; `shouldPoll` == `evaluate` |
| `TripClock` | 0 s, 59 s, 60 s, 1 h, 02:03:04; negativer Input |
| `DiagnosticHistoryCodec` | Round-Trip; Escaping von `\|`, `\n`, `\\`; leere/kaputte Eingaben; `compare()`-Deltas |
| `SystemStatus` | 7 Zeilen; Eingaben korrekt gespiegelt; Statuswort `OFFEN` |

### 10.2 Ergebnis
```
./gradlew testDebugUnitTest --offline
268 tests completed, 0 failed, 0 skipped
```
Vor Phase 12: 248 Tests. Neu: 20. Bestehende Tests unveraendert gruen — keine
Regression.

### 10.3 Build
```
./gradlew assembleDebug --offline
BUILD SUCCESSFUL
app/build/outputs/apk/debug/app-debug.apk
18.679.505 Bytes ≈ 17,8 MiB (18,7 MB)
```

### 10.4 Was getestet wurde — und was nicht
**Automatisiert getestet:** Logik aller neuen Bausteine (10.1), Poll-Gate,
Laenderkatalog, Scan-Klassifikation, Signalstufen, Timer-Formatierung,
Historien-Kodierung/-Vergleich, Systemstatus; plus die vollstaendige bestehende
Suite (Frame-Codec, Telemetrie-Decoder, Firmware-Validator,
Multi-Brand-Architektur, Reconnect-Policy, PIN-Hashing, …).

**Nicht automatisiert testbar (ehrlich benannt):** jede echte BLE-Verbindung
(Scan, GATT-Discovery, Notify, MTU, Writes); die Poll-Fehlerflut ist **im Code**
reproduziert und behoben, ein Beweis am realen Geraet steht aus; Polizei-Modus
mit echten Voraussetzungen (nur Gate-Logik geprueft); rechtliche Richtigkeit der
Laenderhinweise im Einzelfall; UI auf kleinen Displays (nicht visuell auf
Geraeten verifiziert); Diagnose-Export auf echtem SAF-Ziel; Firmware-Flash.

### 10.5 Noch notwendige Hardware-Tests
1. Scan mit GT3 Max, Ninebot G30 und einem Fremdgeraet (z. B. Fitnessband) —
   Kategorien und Filter pruefen.
2. Verbindungsaufbau + Service-Discovery: **welcher UUID-Satz** wird real
   gemeldet? (loest 4.2)
3. 10 Minuten Telemetrie: keine `poll`-Fehler mehr, plausibler
   `Fahrtzeit`-Verlauf, Tacho/Sparklines folgen den Werten.
4. Polizei-Modus einmal ohne Verifikation (muss blocken) und einmal freigegeben
   (muss schreiben).
5. Not-Aus waehrend Telemetrie, danach Reconnect.
6. JSON-/CSV-Export oeffnen und gegen die App-Anzeige abgleichen.
7. Kleines Display (≤ 5", 320 dp): Haftungsausschluss, Cockpit, Tools.

---

## 11. Geaenderte / neue Dateien

| Datei | Art | Inhalt |
|---|---|---|
| `Phase12Support.kt` | **neu** | `CountryLegalCatalog`, `ScanClassifier`, `SignalQualityScale`, `PollGate`, `TripClock`, `DiagnosticHistoryCodec`, `SystemStatus` (reines Kotlin) |
| `CockpitViews.kt` | **neu** | `CockpitGaugeView`, `SparklineView` |
| `Phase12SupportTest.kt` | **neu** | 20 Unit-Tests |
| `Palette.kt` | umgeschrieben | semantische Farbtokens + Aliase, Radien, Abstaende |
| `MainActivity.kt` | stark geaendert | Haftungsausschluss, Laenderhinweis, Onboarding, Timer, Cockpit, Scan-Liste, Verbindungsstufen, Diagnose-Erweiterung, Historie, Schnellzugriff, Polizei-Modus, Tools-Gruppierung, Poll-Gate |
| `BleEngine.kt` | ergaenzt | `hasWriteCharacteristic()` |
| `ScooterRepository.kt` | ergaenzt | `countryNoticeAccepted`, `onboardingSeen`, `lastDeviceAddress`, `lastDeviceName`, `diagnosticHistoryRaw` |
| `todo.md` | aktualisiert | Phasenplan |

**Keine Datei geloescht. Keine Funktion entfernt. Kein Tab entfernt.**

---

## 12. Abschluss

* **Branch:** `feat/phase12-professional-ui`
* **Commit:** `ba4ad71`
* **Pull Request:** __PR__
* **Unit-Tests:** 268 / 268 gruen (0 Fehler, 0 uebersprungen)
* **Build:** `assembleDebug` erfolgreich
* **APK:** 18.679.505 Bytes (≈ 18,7 MB)

**Bekannte offene Punkte (bewusst, nicht verschwiegen):**
1. UUID-Widerspruch `BleConstants` vs. `BleEngine`/Adapter — ohne Hardware nicht
   entscheidbar, sichtbar in der App dokumentiert.
2. Servo-Start existierte nie in diesem Repository — nicht wiederherstellbar.
3. Keine Hardware-Validierung; alle Geraete-Kommunikation bleibt bis dahin
   read-only bzw. gate-geschuetzt.
