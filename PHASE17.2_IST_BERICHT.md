# PHASE 17.2 — IST-BERICHT (Repository-Audit vor der Umsetzung)

**Repository:** `syrak7305-bit/SyrakScooterLab`
**Basis-Branch:** `main`
**Basis-Commit (verifiziert):** `e1be76693405dbe04e11458bf16bf70e04d16637`
**Commit-Nachricht:** `Merge pull request #28 from syrak7305-bit/feat/phase17.1-advanced-scooter-recognition`
**Audit-Zeitpunkt:** vor Beginn der Umsetzung von Phase 17.2
**Geplanter Feature-Branch:** `feat/phase17.2-live-gatt-recognition`

---

## 1. Repository-Zustand

| Prüfpunkt | Ergebnis |
|---|---|
| `git rev-parse HEAD` auf `main` | `e1be76693405dbe04e11458bf16bf70e04d16637` |
| Stimmt mit dem im Auftrag genannten Basis-Commit überein? | **Ja, exakt.** Keine Abweichung. |
| Offene Pull Requests gegen `main` | 0 |
| Letzter CI-Lauf auf `main` (Run-ID `41354270199`) | erfolgreich |
| `git status` auf `main` vor Arbeitsbeginn | clean |
| Modulstruktur | ein Modul `:app`, Namespace `com.syrak.scooterlab` |
| Kotlin / AGP / Gradle | Kotlin 2.0.21, AGP 8.7.2, Gradle 8.9 |
| SDK | compileSdk 34, minSdk 26, targetSdk 34, JVM 17 |
| Testframework | JUnit 4.13.2 |

**Wichtige Build-Eigenschaft:** `assembleDebug` hat seit Phase 16.2 ein hartes
`dependsOn("testDebugUnitTest")`. Ein Build ohne grüne Unit-Tests ist damit
technisch nicht möglich — das ist für §11 des Auftrags günstig, weil der
Build-Befehl die Tests zwingend mitzieht.

---

## 2. Vorgefundene Architektur (tatsächlich gelesen, nicht angenommen)

### 2.1 Erkennung (Phase 17.1, bereits in `main`)
Paket `app/src/main/java/com/syrak/scooterlab/recognition/`:

- `RecognitionEngine` — deterministische Bewertung, Signatur bewusst **ohne**
  Nutzerprofil (`RecognitionInput(advertisement, gatt)`).
- `RecognitionInput`, `RecognitionResult` — Ergebnis mit `confidence`,
  `reasons`, `matchedFeatures`, `missingFeatures`, `contradictoryFeatures`,
  `unknownFeatures`.
- `AdvertisementFeatures` — strukturierte Advertisement-Daten
  (`serviceUuids`, `manufacturerIds`, `serviceDataUuids`, `rssi`).
- `GattFeatures` — GATT-Fingerprint inkl. `GattDiscoveryStatus`
  (`NOT_ATTEMPTED`, `COMPLETE`, `INCOMPLETE`, `FAILED`), `services`,
  `characteristics`, `errors`.
- `ModelFingerprint` / `BuiltInModelFingerprints` — 3 Profile, **alle**
  `HardwareTestStatus.NOT_TESTED`, `confirmationDate == null`.
- `RecognitionSession` — **ein einziger Slot** (`current`, `currentAddress`).
- `RecognitionExport` — Exportblock für den Hardware-Testbericht.
- `RecognitionConfidence`, `EvidenceGrade`, `RecognitionReasonCode`,
  `RecognitionFeatureMatch`, `GenericUuids`, `BrandFamily`.

### 2.2 Aktive BLE-Schicht
- `BleEngine` ist die produktive Engine. `BleEngine.Listener` liefert u. a.
  `onConnected`, `onReady(address, protocol, mtu)`, `onDisconnected(unexpected)`,
  `onDisconnectStatus`, `onTelemetry`, `onReadableCharacteristics`,
  `onRawNotification`, `onCharacteristicReadResult`, `onWriteResult`.
- `connectionGeneration` (`@Volatile`, monoton) + `isCurrentGatt(g)` +
  `closeStaleGatt(g)` + `resetConnectionState(resetDiagnostics)` bilden die
  vorhandene Identitäts- und Stale-Callback-Absicherung.
- `gattSnapshot(): List<GattServiceRecord>` liefert den real gecachten
  GATT-Baum (Services → Characteristics → Properties).
- `propertySet(ch)` bildet Android-Property-Bits auf `GattProperty` ab
  (READ, WRITE, WRITE_NO_RESPONSE, NOTIFY, INDICATE, BROADCAST, SIGNED_WRITE,
  EXTENDED_PROPERTIES, UNKNOWN).
- `BleUuids`-Registry mit `UuidProvenance`, `UuidRole`, `candidateRoles`,
  `roleAmbiguous`, `confirmedOnHardware` (durchgängig `false`).
- Legacy-Stack `core.ble.BleManager` / `BleConstants` existiert, ist aber
  **nicht** der produktive Pfad (nur Compose-Altlast). Er wurde nicht angefasst.

### 2.3 UI
- `MainActivity` ist die reale UI. Sie hält u. a. `activeIdentityAddress`,
  `activeIdentityName`, `htLastGatt`, `htGattAddress`, `linkState`
  (`BleLinkState`), `htConnectedAt`, `htReadyAt`, `htDisconnectedAt`,
  `htUnexpectedDisconnect`, `htDisconnectStatus`.
- Der Erkennungsblock wurde bis 17.1 aus **einem** `recognitionSession.current`
  gerendert (Abschnitt „HARDWARE-ERKENNUNG“).

### 2.4 Diagnose-Export
- `HardwareTestReport` (schemaVersion 1) mit `toJson()`, `toCsv()`
  (flaches `section,field,value`-Format), `toSummary()`.
- MAC-Adressen werden bereits maskiert (`AA:BB:**:**:EE:FF`), Seriennummer
  wird nicht exportiert.

### 2.5 Tests
- `Phase171RecognitionTest` — 32 Tests, decken Engine, Session, Export ab.
- Weitere BLE-/Diagnose-/Intelligenz-Suiten.
- Gesamtstand vor Phase 17.2: **546 Tests, 0 Fehler, 0 übersprungen**
  (36 Suiten). Nach der Erweiterung: 37 Suiten.

---

## 3. Identifizierte Probleme / Lücken

### P1 — Kein echter GATT-Status im Snapshot (§3)
`gattSnapshot()` liefert nur `List<GattServiceRecord>`. Ein leeres Ergebnis ist
mehrdeutig: „keine Verbindung“, „Discovery noch nicht gelaufen“ und
„Discovery lief, meldete aber nichts“ sind nicht unterscheidbar. Ein
fehlgeschlagener Discovery-Vorgang ist aus dem Rückgabewert nicht erkennbar.

### P2 — GATT-Daten erreichten die Engine nicht (§2)
`MainActivity` rief die Engine ausschließlich im Scan-Callback mit
`AdvertisementFeatures` auf. Nach `onReady` wurde keine erneute Bewertung
durchgeführt. GATT-Daten flossen damit praktisch nie in die Erkennung ein.

### P3 — Session konnte nur ein Ergebnis halten (§5, ausdrücklicher Defekt)
`RecognitionSession` hatte genau einen Slot. Bei mehreren sichtbaren Geräten
überschrieb der jeweils letzte Scan-Callback das Ergebnis des vorherigen
Geräts. Ein fremdes Gerät konnte damit die aktive Hardware-Erkennung
verdrängen. Es gab keinen getrennten Slot für die aktive Verbindung.

### P4 — `RecognitionConfidence.rank` widersprach seiner eigenen Dokumentation
Der Kommentar sagt „höher = stärker“, implementiert war `rank = ordinal`.
Deklarationsreihenfolge ist `CONFIRMED, PROBABLE, FAMILY_ONLY, UNKNOWN`, also
galt `CONFIRMED.rank = 0` … `UNKNOWN.rank = 3`. Zwei konkrete Folgen:

1. `RecognitionEngine` wählt mit `classified.maxOf { it.second.rank }` das
   **schwächste** Profil statt des stärksten.
2. Die Widerspruchsbegrenzung `confidence.rank > FAMILY_ONLY.rank` konnte nie
   greifen, weil sie nur bei `UNKNOWN` wahr geworden wäre.

Das ist ein echter Logikfehler, der die in §6 geforderte
Widerspruchsbehandlung unterläuft. Er wurde in Phase 17.2 mitbehoben.

### P5 — `GATT_NO_EVIDENCE` fehlte bei fehlgeschlagener Discovery
Die Begründung wurde nur erzeugt, wenn `gatt.hasAnyData` galt. Eine
fehlgeschlagene Discovery hat aber gerade keine Services — es entstand also
gar keine Begründung. Der Fehlschlag war im Ergebnis unsichtbar.

### P6 — Export konnte Scan- und GATT-Ergebnis nicht trennen (§8)
`RecognitionExport` enthielt nur den Endzustand. Ohne Scan-Zwischenstand,
Zeitpunkte und „neu hinzugekommene Evidenz“ ist der in §7 geforderte
Ergebnisvergleich nicht exportierbar.

---

## 4. Abweichungen vom Auftragstext

Keine inhaltlichen Abweichungen. Der im Auftrag genannte Basis-Commit
`e1be766…` ist tatsächlich `main`-HEAD. Die im Auftrag beschriebene
Architektur (Erkennungspaket, `BleEngine`, `MainActivity`,
`HardwareTestReport`) entspricht dem vorgefundenen Code.

Einzige betriebliche Randnotiz: Das Sandbox-Dateisystem war zu Beginn zu
100 % gefüllt (24 MB frei). Nach Bereinigung von `/tmp`, `app/build` und
`.gradle` (340 MB frei) war ein vollständiger Neuaufbau erforderlich. Das
beeinflusst keine Ergebnisse, nur die Laufzeit.
