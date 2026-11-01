# PHASE 17.2 — ERGEBNISBERICHT
## Live-GATT-Erkennung & Geräte-Session-Integration

**Repository:** `syrak7305-bit/SyrakScooterLab`
**Basis-Branch:** `main`
**Basis-Commit:** `e1be76693405dbe04e11458bf16bf70e04d16637`
**Feature-Branch:** `feat/phase17.2-live-gatt-recognition`
**Feature-Commit:** `FIXME_COMMIT`
**Pull Request:** `FIXME_PR`

---

## 0. Kennzeichnung der Nachweisart

Der Auftrag verlangt eine klare Trennung. In diesem Bericht gilt durchgängig:

- **[CODE]** — im Code implementiert, kompiliert.
- **[TEST]** — automatisch durch Unit-Tests abgedeckt und in diesem Lauf grün.
- **[HW-BEOBACHTET]** — auf echter Hardware beobachtet.
- **[HW-REPRO]** — auf echter Hardware reproduzierbar bestätigt.

**Wichtig und bindend:** In dieser Phase wurde **keine** echte Hardware
verwendet. Es gibt daher **keinen** Punkt mit [HW-BEOBACHTET] oder [HW-REPRO].
Alle Aussagen zur Erkennungsgüte sind Aussagen über die Logik, nicht über
reale Scooter. Ein erfolgreicher GATT-Connect beweist keine
Telemetrie-Kompatibilität, ein gefundener Service beweist keine
Protokollrolle, und eine gestiegene Konfidenz ist kein Beweis für
unterstützte Tuning-Funktionen.

---

## 1. Repository-Zustand (Ist)

`main` stand exakt auf dem im Auftrag genannten Commit `e1be766` (Merge von
PR #28, Phase 17.1). Keine offenen PRs, letzter CI-Lauf erfolgreich, Working
Tree clean. Das Erkennungspaket aus Phase 17.1 war vollständig vorhanden
(`RecognitionEngine`, `RecognitionInput`, `RecognitionResult`,
`AdvertisementFeatures`, `GattFeatures`, `ModelFingerprint`,
`RecognitionSession`, `RecognitionExport`). Produktiver BLE-Pfad war und ist
`BleEngine`; der Legacy-Stack `core.ble.*` wurde nicht angefasst.
Details: siehe `PHASE17.2_IST_BERICHT.md`. **[CODE]**

---

## 2. Identifizierte Probleme

| # | Problem | Status |
|---|---|---|
| P1 | `gattSnapshot()` liefert keinen echten Discovery-Status; leeres Ergebnis ist mehrdeutig | behoben |
| P2 | GATT-Daten erreichten die Engine nie — Bewertung nur im Scan-Callback | behoben |
| P3 | `RecognitionSession` hatte nur einen Slot → fremde Geräte überschrieben die aktive Erkennung | behoben |
| P4 | `RecognitionConfidence.rank = ordinal` widersprach der Doku; Engine wählte das schwächste Profil, Widerspruchsbegrenzung griff nie | behoben |
| P5 | `GATT_NO_EVIDENCE` fehlte bei fehlgeschlagener Discovery (keine Services → keine Begründung) | behoben |
| P6 | Export konnte Scan- und GATT-Ergebnis nicht trennen | behoben |

P4 und P5 waren beim Audit **nicht** im Auftragstext genannt, sind aber echte
Logikfehler, die die Anforderungen aus §6 (Widersprüche müssen wirken,
fehlende Daten dürfen nicht positiv zählen) unterlaufen hätten. Sie wurden
mitbehoben und sind durch Tests abgedeckt. **[CODE] [TEST]**

---

## 3. Implementierte GATT-Anbindung (§3)

`BleEngine` wurde **additiv** erweitert, ohne bestehende Signaturen zu ändern:

- Neue `@Volatile`-Felder `discoveryStatus`, `discoveryError`,
  `discoveryGeneration`.
- `discoveryStatus` wird ausschließlich aus echten Callbacks gesetzt:
  - `STATE_CONNECTED` → `NOT_ATTEMPTED` (Reset je neuer Verbindung),
  - `onServicesDiscovered` mit Fehlerstatus → `FAILED` + Rohtext
    `"Service-Discovery fehlgeschlagen (status=$status)"`,
  - `onServicesDiscovered` mit **leerem** Service-Baum → `INCOMPLETE`
    (ausdrücklich **nicht** `COMPLETE`),
  - `onServicesDiscovered` mit Services → `COMPLETE`.
- Neue Methode `liveGattSnapshot(): GattLiveSnapshot?` liefert Adresse,
  Verbindungsgeneration, Service-Baum, Discovery-Status, Fehlertext und
  Discovery-Generation.
- `GattLiveSnapshot` kapselt die Stale-Prüfung:
  - `discoveryIsFresh` (`discoveryGeneration == generation`),
  - `effectiveDiscoveryStatus` → bei stale `NOT_ATTEMPTED`,
  - `effectiveServices` → bei stale `emptyList()`,
  - `matchesAddress(other)` → case-insensitiver Adressvergleich.
- `gattSnapshot()` und alle bestehenden Diagnose-Aufrufe bleiben unverändert
  nutzbar. **[CODE] [TEST]**

Es wurde **keine** neue BLE-Verbindung und **keine** zusätzliche
GATT-Discovery gestartet. Die Integration hängt sich ausschließlich an den
vorhandenen Lebenszyklus (`onConnected` / `onServicesDiscovered` / `onReady` /
`onDisconnected`).

---

## 4. Session-Management (§5)

`RecognitionSession` wurde erweitert (keine zweite Erkennungsarchitektur):

- `scanResults: LinkedHashMap<String, RecognitionResult>` — **ein Ergebnis je
  Bluetooth-Adresse**.
- `activeAddress` / `activeResult` — eigener Slot für das **aktiv verbundene**
  Gerät (Advertisement + GATT zusammengeführt).
- `lastActiveAddress` / `lastActiveResult` — getrennte Historie der zuletzt
  beendeten Verbindung.
- `observeScan(address, result)` — Scan-Pfad; berührt `activeResult` **nicht**.
- `observe(address, result)` — unverändertes 17.1-Verhalten (Kompatibilität).
- `bindActive(address, result)` — akzeptiert nur Ergebnisse, deren
  `result.address` zur übergebenen Adresse passt; sonst `false` und keine
  Änderung.
- `archiveActive()` — verschiebt das aktive Ergebnis in die Historie und
  leert den aktiven Slot (Disconnect-Pfad).
- `displayResult()` — `activeResult ?: lastActiveResult ?: current`, damit ein
  Export nach dem Trennen weiterhin die echte GATT-Erkenntnis enthält.
- `reset()` — verwirft alles.

Damit gilt: Scan-Ergebnisse verschiedener Adressen bleiben getrennt, ein
fremdes Gerät kann die aktive Hardware-Erkennung nicht mehr überschreiben,
und nach einem Gerätewechsel wird kein alter GATT-Snapshot übernommen.
MAC-Adressen werden weiterhin nicht als sichtbare Gerätenamen verwendet
(Anzeige nutzt `deviceName` bzw. „ohne Namen“). **[CODE] [TEST]**

---

## 5. RecognitionEngine (§6)

- GATT-Merkmale zählen **nur** bei `discoveryStatus == COMPLETE` als positive
  Evidenz. `FAILED`, `INCOMPLETE` und `NOT_ATTEMPTED` können die Konfidenz
  niemals erhöhen.
- `scoreProfile(...)` erhält GATT-Daten nur, wenn die Discovery vollständig
  ist (`gattForScoring`); Service-UUIDs für die Widerspruchserkennung werden
  weiterhin roh herangezogen, damit Widersprüche sichtbar bleiben.
- Neue Begründung `GATT_EVIDENCE_ADDED` (nur bei vollständiger Discovery und
  tatsächlich zählenden GATT-Merkmalen) bzw. `GATT_NO_EVIDENCE` (bei
  fehlender/unvollständiger Discovery **oder** ohne passende Merkmale).
- `RecognitionConfidence.rank` ist jetzt `entries.size - 1 - ordinal`, also
  `UNKNOWN=0 < FAMILY_ONLY=1 < PROBABLE=2 < CONFIRMED=3`. Damit wählt die
  Engine das **stärkste** Profil und die Widerspruchsbegrenzung
  (`rank > FAMILY_ONLY.rank`) greift tatsächlich.
- Konfidenzregeln unverändert: `CONFIRMED` nur mit hardware-bestätigtem
  Profil, ausschließlich `CONFIRMED`-Merkmalen und ≥ 3 unabhängigen
  Kategorien. Da kein Profil hardware-bestätigt ist, bleibt `CONFIRMED`
  derzeit unerreichbar — das ist beabsichtigt.
- Nutzerprofil bleibt vollständig außerhalb der Engine; eine Modellwahl per
  Profil kann keine Erkennung erzeugen. Keine Prozentwerte. **[CODE] [TEST]**

---

## 6. UI (§7)

Der Erkennungsblock in `MainActivity` wurde in vier Blöcke gegliedert, ohne
das dunkle Syrak-Design oder die Kartenstruktur zu verändern:

1. **NUTZERPROFIL** — gewählte Marke / gewähltes Modell, klar als *gewählt*
   und nicht als *erkannt* gekennzeichnet.
2. **SCAN-ERKENNUNG** — Advertisement-Ergebnis: Gerätename, erkannte Marke,
   Modellfamilie, Konfidenz (Advertisement), Advertisement-Merkmale.
3. **LIVE-HARDWARE-ERKENNUNG** — aktives Gerät: GATT-Discovery-Status
   farbcodiert (grün/gelb/rot/grau), erkannte Services,
   Charakteristik-Anzahl, Discovery-Fehler, finale Konfidenz, erkanntes
   Modell. Bei fehlender Discovery steht explizit
   „Noch keine GATT-Discovery — das Advertisement-Ergebnis gilt.“ Ist nur
   noch die Historie vorhanden, erscheint „GETRENNT (Historie)“.
4. **ERGEBNISVERGLEICH** — Konfidenz vor/nach GATT, neu hinzugekommene
   Evidenz, weiterhin fehlende Informationen, widersprüchliche Merkmale,
   Begründungssatz.

Alle bestehenden Diagnose- und Verbindungsanzeigen bleiben unverändert.
**[CODE]**

---

## 7. Export (§8)

`RecognitionExport` wurde **rein additiv** erweitert (alle neuen Felder mit
Default, `from(...)` mit Default-Parametern):

- `scanConfidence`, `finalConfidence`
- `gattEvidenceAdded` (GATT-Merkmale, die im reinen Scan-Ergebnis fehlten)
- `stillMissing`
- `scanObservedAtMs`, `gattObservedAtMs`

`HardwareTestReport`:

- JSON: neues Objekt `"comparison"` innerhalb von `"recognition"`.
- CSV: neue Sektion `recognition_compare` mit denselben sieben Feldern.
- `schemaVersion` bleibt **1**; bestehende Felder und Sektionen bleiben
  unverändert, alte Parser funktionieren weiter.
- Seriennummer wird weiterhin nicht exportiert; MAC-Adressen bleiben
  maskiert (`AA:BB:**:**:EE:FF`). **[CODE] [TEST]**

---

## 8. Neue Tests (§9)

Neue Suite `app/src/test/java/com/syrak/scooterlab/Phase172LiveGattRecognitionTest.kt`
mit **27 Tests**. Zuordnung zu den 20 geforderten Szenarien:

| §9 | Szenario | Test(s) |
|---|---|---|
| 1 | Advertisement-Erkennung ohne Verbindung | `advertisementRecognitionWithoutConnection` |
| 2 | GATT-Erkennung nach erfolgreicher Discovery | `gattRecognitionAfterSuccessfulDiscovery` |
| 3 | GATT-Discovery fehlgeschlagen | `gattDiscoveryFailed` |
| 4 | GATT-Discovery noch nicht gestartet | `gattDiscoveryNotAttempted` |
| 5 | Leerer GATT-Snapshot | `emptyGattSnapshotIsNotSuccessfulDiscovery` |
| 6 | Unvollständige GATT-Daten | `incompleteGattDataYieldsNoPositiveEvidence` |
| 7 | Advertisement und GATT stimmen überein | `advertisementAndGattAgree` |
| 8 | Advertisement und GATT widersprechen sich | `advertisementAndGattContradict` |
| 9 | Zwei gleichzeitig sichtbare Scooter | `twoSimultaneouslyVisibleScootersStaySeparate` |
| 10 | Fremdes Scan-Ergebnis überschreibt aktive Verbindung nicht | `foreignScanResultDoesNotOverwriteActiveConnection` |
| 11 | GATT-Daten der richtigen Adresse zugeordnet | `gattDataBoundOnlyToMatchingAddress` |
| 12 | Disconnect setzt aktive Erkennung zurück | `disconnectResetsActiveRecognition` |
| 13 | Reconnect auf dasselbe Gerät | `reconnectToSameDevice` |
| 14 | Reconnect auf ein anderes Gerät | `reconnectToDifferentDeviceDiscardsOldData` |
| 15 | Verspäteter Callback einer alten Verbindung | `lateCallbackOfOldConnectionIsDiscarded`, `freshSnapshotIsUsed` |
| 16 | Generische UUIDs bleiben ohne Modellbeweis | `genericUuidsGiveNoModelProof` |
| 17 | READ/WRITE/NOTIFY korrekt übernommen | `readWriteNotifyPropertiesAreAdopted`, `indicateAndReadPropertiesAreAdopted` |
| 18 | Bestehende Erkennungstests bleiben gültig | `Phase171RecognitionTest` (32 Tests, unverändert) |
| 19 | Bestehende BLE-Tests bleiben gültig | alle übrigen Suiten, unverändert |
| 20 | JSON- und CSV-Export bleiben gültig | `jsonExportContainsComparisonAndStaysBackwardCompatible`, `csvExportContainsComparisonSection` |

Zusätzliche Absicherungen: `legacySessionObserveStillWorks`,
`engineIsDeterministic`, `gattFeaturesDefaultStatusRemainsComplete`,
`snapshotWithoutDiscoveryIsNotAttempted`, `noArtificialPercentagesInReasoning`,
`macAddressIsMaskedInExport`.

**Es wurde kein Test entfernt und keiner übersprungen.** **[CODE] [TEST]**

---

## 9. Vollständige Testergebnisse (§11)

Befehl: `./gradlew testDebugUnitTest --offline`

```
37 Test-Suiten
573 Tests
0 Fehler (failures)
0 Fehler (errors)
0 übersprungen (skipped)
BUILD SUCCESSFUL
```

Aufteilung:

| Suite | Tests |
|---|---|
| `Phase172LiveGattRecognitionTest` (neu) | 27 |
| `Phase171RecognitionTest` (unverändert) | 32 |
| übrige 35 Suiten (unverändert) | 514 |
| **Summe** | **573** |

Vor Phase 17.2 lag der Stand bei 546 Tests. Die Differenz von 27 entspricht
exakt der neuen Suite. **[TEST]**

---

## 10. Build-Ergebnis (§11)

Befehl: `./gradlew assembleDebug --offline`

```
BUILD SUCCESSFUL
APK: app/build/outputs/apk/debug/app-debug.apk
Größe: 19.251.428 Bytes (≈ 18,4 MiB)
```

Hinweis: `assembleDebug` hängt seit Phase 16.2 hart von
`testDebugUnitTest` ab; der Build hat die 573 Tests daher mit ausgeführt.

**Warnungen:** genau eine, und zwar vorbestehend:

```
w: app/src/main/java/com/syrak/scooterlab/BleEngine.kt:932:53
   'fun writeDescriptor(p0: BluetoothGattDescriptor!): Boolean' is deprecated.
```

Zusätzlich die übliche, nicht blockierende Meldung von
`stripDebugDebugSymbols` über nicht strippbare NDK-Bibliotheken
(`libandroidx.graphics.path.so`, `libdatastore_shared_counter.so`) — das ist
Standardverhalten von AGP, keine Fehlermeldung.

**Geänderte Dateien:**

| Datei | Art |
|---|---|
| `app/src/main/java/com/syrak/scooterlab/BleEngine.kt` | +99 |
| `app/src/main/java/com/syrak/scooterlab/MainActivity.kt` | +245 / −… |
| `app/src/main/java/com/syrak/scooterlab/diagnostics/HardwareTestReport.kt` | +18 |
| `app/src/main/java/com/syrak/scooterlab/recognition/RecognitionEngine.kt` | +34 / −… |
| `app/src/main/java/com/syrak/scooterlab/recognition/RecognitionExport.kt` | +39 / −… |
| `app/src/main/java/com/syrak/scooterlab/recognition/RecognitionModel.kt` | +17 / −… |
| `app/src/main/java/com/syrak/scooterlab/recognition/RecognitionSession.kt` | +163 / −… |
| `app/src/test/java/com/syrak/scooterlab/Phase172LiveGattRecognitionTest.kt` | neu (27 Tests) |
| `PHASE17.2_IST_BERICHT.md`, `PHASE17.2_ERGEBNIS.md`, `todo.md` | Doku |

---

## 11. Branch und Commit (§12)

- Feature-Branch: `feat/phase17.2-live-gatt-recognition`
- Basis: `e1be76693405dbe04e11458bf16bf70e04d16637` (`main`)
- Feature-Commit: `FIXME_COMMIT`
- Kein direkter Commit auf `main`.

---

## 12. Pull Request (§12)

`FIXME_PR`

---

## 13. Offene Probleme

1. **Keine Hardware-Validierung.** Sämtliche Erkennungsaussagen sind
   Logik-Aussagen. Ob ein realer Navee/Brightway-Scooter die UUID
   `0000ffe0` tatsächlich meldet und ob `0000ffe1` wirklich Telemetrie
   überträgt, ist **nicht** belegt.
2. **Kein Profil ist hardware-bestätigt.** `CONFIRMED` bleibt damit
   unerreichbar. Das ist beabsichtigt, aber es bedeutet: die UI wird im
   Feldbetrieb praktisch nie „BESTÄTIGT“ zeigen.
3. **`INCOMPLETE` ist konservativ.** Ein Discovery-Callback mit leerem
   Service-Baum wird als `INCOMPLETE` gewertet. Ob Android in der Praxis
   jemals einen erfolgreichen Callback mit leerer Liste liefert, ist nicht
   verifiziert; die Behandlung ist bewusst defensiv.
4. **Zeitstempel** stammen aus `System.currentTimeMillis()` und sind damit
   nicht monoton gegen Systemzeitänderungen abgesichert. Für die
   Export-Nachvollziehbarkeit ausreichend, für Messreihen nicht.
5. **Legacy-Stack** `core.ble.*` bleibt unangetastet und weiterhin totes
   Gleis. Eine Bereinigung war nicht Teil dieses Auftrags.

---

## 14. Notwendige Hardware-Tests

Alle folgenden Punkte sind **offen** und erfordern echte Geräte:

1. **Advertisement-Erkennung**: realen Scooter scannen, prüfen, ob Marke und
   Modellfamilie im Block SCAN-ERKENNUNG plausibel sind.
2. **GATT-Discovery-Status**: verbinden und im Block LIVE-HARDWARE-ERKENNUNG
   prüfen, ob der Status tatsächlich auf „vollständig“ springt und die
   Service-Liste der Realität entspricht.
3. **Charakteristik-Properties**: READ/WRITE/NOTIFY/INDICATE der realen
   Characteristics gegen die Anzeige abgleichen. Insbesondere prüfen, ob
   eine NOTIFY-Characteristic fälschlich als Telemetrie interpretiert würde —
   sie darf es nicht.
4. **Discovery-Fehlschlag**: einen Verbindungsabbruch während der Discovery
   provozieren und prüfen, dass `FAILED` erscheint und die Konfidenz **nicht**
   steigt.
5. **Zwei Geräte gleichzeitig**: zwei Scooter sichtbar machen, eines
   verbinden, prüfen, dass Scan-Callbacks des zweiten Geräts die
   LIVE-HARDWARE-ERKENNUNG nicht verändern.
6. **Reconnect / Gerätewechsel**: nach Trennen und Wechsel auf ein anderes
   Gerät prüfen, dass keine alten Services angezeigt werden.
7. **Verspätete Callbacks**: Verbindung trennen und sofort neu verbinden;
   prüfen, dass kein Status der alten Verbindung übernommen wird.
8. **Export**: JSON und CSV nach einem echten Verbindungszyklus erzeugen und
   prüfen, dass `comparison`/`recognition_compare` die Advertisement- und die
   GATT-Phase unterscheidbar abbilden und die MAC weiterhin maskiert ist.
9. **Kein Schreibzugriff**: sicherstellen, dass in dieser Phase keine
   zusätzlichen BLE-Schreibbefehle ausgelöst werden (nur bestehende
   Telemetrie-/Diagnosepfade).

Erst nach diesen Tests darf von „auf Hardware bestätigt“ gesprochen werden.
Bis dahin gilt: **Die Erkennung ist eine begründete Vermutung, kein Beweis.**
