# Phase 16.3.2 — Technischer Ist-Zustand-Bericht

**Projekt:** Syrak ScooterLab (`syrak7305-bit/SyrakScooterLab`)
**Branch:** `main`
**Commit (geprüft):** `fa6efe27607850d165c6f2246a074c74dd90bf13`
— „Merge pull request #24 from syrak7305-bit/feat/phase16.3.1-identity-and-notify-integrity"
**Paket:** `com.syrak.scooterlab` · Kotlin · Gradle 8.9 · compileSdk 34 · minSdk 26 · targetSdk 34 · Java 17

**Status dieses Dokuments:** reine Analyse. **Es wurde kein Produktivcode geändert.**
Einzige Datei, die ich im Arbeitsverzeichnis angelegt habe, ist `local.properties`
(`sdk.dir=/opt/android-sdk`) — reine Build-Umgebung, gehört nicht ins Repository.

---

## 0. Verifikationsbasis — was ich tatsächlich selbst geprüft habe

Bevor ich irgendetwas aus der Übergabe als gegeben übernehme, habe ich den
behaupteten Ausgangszustand empirisch nachgemessen:

| Prüfung | Ergebnis | Status |
|---|---|---|
| Repository geklont, HEAD geprüft | `fa6efe2…` = behaupteter Merge-Commit | ✅ selbst verifiziert |
| JDK 17 bereitgestellt | OpenJDK 17.0.21 installiert | ✅ selbst verifiziert |
| Android SDK 34 bereitgestellt | `platform-tools`, `platforms;android-34`, `build-tools;34.0.0` unter `/opt/android-sdk` | ✅ selbst verifiziert |
| `./gradlew testDebugUnitTest` | **BUILD SUCCESSFUL** | ✅ selbst verifiziert |
| JUnit-XML ausgewertet | **32 Suites, 441 Tests, 0 Failures, 0 Errors, 0 Skipped** | ✅ selbst verifiziert |
| `./gradlew assembleDebug` | **BUILD SUCCESSFUL**, `app-debug.apk` (18.7 MB) erzeugt | ✅ selbst verifiziert |
| Behauptete 441 Tests | **bestätigt** (nicht nur übernommen) | ✅ selbst verifiziert |
| Reale Hardware-Tests (NAVEE GT3 Max, Ninebot F3 Pro, E3 Pro D) | **nicht reproduzierbar in dieser Umgebung** | ❌ nicht verifiziert |

Damit gilt für diesen Bericht durchgängig die geforderte Trennung:

* **„im Code vorhanden"** = ich habe die Stelle gelesen und zitiere sie mit Datei:Zeile.
* **„durch Tests belegt"** = es existiert ein automatisierter Test, der die Aussage prüft (und er läuft grün).
* **„an realer Hardware bestätigt"** = **für keine einzige BLE-Aussage in diesem Projekt belegt.**
  Es gibt im gesamten Repository keinen einzigen als bestätigt markierten GATT-Mitschnitt
  (`BleUuids.confirmedOnHardwareCount() == 0`, bewusst so).
* **„nicht verifiziert"** = alles, was nur aus Hardware-Testberichten Dritter (Jayno) stammt.

---

## 1. Frage 1 — Wie ist die aktuelle BLE-Architektur aufgebaut?

### 1.1 Es existieren zwei parallele BLE-Stacks

Das ist der wichtigste strukturelle Befund und er ist bereits im Code dokumentiert
(`BleUuids.kt`, Kopfkommentar Zeilen 1–45).

**(A) Aktiver Stack — `BleEngine` (`app/src/main/java/com/syrak/scooterlab/BleEngine.kt`, 1630 Zeilen)**

* Wird ausschließlich von `MainActivity` benutzt (`MainActivity.kt:2540 ff.`).
* Transport-UUIDs: Ninebot `0000e003` + `0000fff1`/`0000fff2`,
  Navee/Brightway `0000ffe0` + `0000ffe1`/`0000ffe2`.
* Enthält: GATT-Callback, Service-Discovery, Characteristic-Auswahl, CCCD-Aktivierung,
  Write-Queue, Read-Tracker, Frame-Reassembly, Flash-Pfad, Diagnose-Emittern.
* `adapter: ScooterProtocolAdapter` (Default `ProtocolAdapters.forProtocol(NINEBOT)`)
  plus `adapterConfirmed: Boolean` — `false` bedeutet ausdrücklich „angenommen, nicht belegt".

**(B) Legacy-Stack — `core.ble.BleManager` (`BleManager.kt`, 507 Zeilen)**

* Transport-UUIDs: Nordic UART `6e400001/6e400002/6e400003` + Xiaomi `fe95`
  (`BleConstants.kt:43` `SCAN_SERVICE_FILTERS`).
* Wird von `AppContainer.kt:16` instanziiert und von `NinebotSession.kt:3` importiert,
  außerdem von `DashboardViewModel.kt` / `DashboardScreen.kt` (Compose).
* **Diese Compose-UI ist nicht erreichbar:** `MainActivity` benutzt
  `setContentView(rootContainer)` (`MainActivity.kt:518`) und rendert die gesamte
  Oberfläche imperativ (`renderDashboard()`, `MainActivity.kt:2900`).
  Es gibt kein `setContent { }`, keinen Aufruf von `SyrakApp(`/`DashboardScreen(`.

**Konsequenz:** Es laufen keine zwei Stacks gleichzeitig, aber es existiert toter
Code mit **widersprüchlichen UUID-Definitionen**. Das ist eine dokumentierte,
bewusste Baustelle (siehe `BleUuids.transportContradictions()`), keine akute Fehlerquelle —
aber es ist eine erhebliche Verwechslungsgefahr für jede künftige Änderung.

### 1.2 Zentrale UUID-Registry mit Herkunftsangabe

`BleUuids.kt` führt jede UUID genau einmal, mit `UuidProvenance`
(`DOCUMENTED` / `PROJECT_CONSTANT` / `LEGACY_UNCONFIRMED` / `PLACEHOLDER`),
`UuidRole`, `candidateRoles` und `confirmedOnHardware`.

Wichtig und korrekt: **`NINEBOT_SERVICE` und `NINEBOT_CHAR_WRITE*` sind
`PROJECT_CONSTANT`, nicht `DOCUMENTED`.** FFF1/FFF2 werden als Kandidat für
*beide* Rollen (Write und Notify) geführt, weil die reale Rolle ohne
GATT-Mitschnitt nicht entscheidbar ist (`roleAmbiguous`). Das ist genau die
geforderte Ehrlichkeit — es wird nichts erfunden.

### 1.3 Schichtenmodell (aktiver Pfad)

```
MainActivity (UI, Scan, Auswahl, Status, Diagnose-Anzeige)
   │  setProtocolHint / setModelHint / setAutoHandshakeAllowed
   ▼
BleEngine (GATT-Lebenszyklus, Queues, Reassembly, Diagnose)
   │
   ├── ScooterProtocolAdapter (NinebotAdapter | NaveeGt3MaxAdapter)
   │      buildReadStatusFrame, isAckFor, decode, capabilities
   ├── GattCharacteristicSelector (reine Auswahl-Logik, testbar)
   ├── FrameReassembler (Header-Suche, Längenfeld, Resync)
   ├── FrameValidator (Struktur + Prüfsumme; checksumVerified bleibt false)
   └── TelemetryDiagnostics (6-stufige Ursachenkette)
   ▼
Listener → MainActivity: onConnected / onReady / onTelemetry /
           onRawNotification / onWriteResult / onTelemetryDiagnostics
```

### 1.4 Verbindungs-Zustandsmaschine

`BleLinkState.kt` (59 Zeilen): `IDLE → CONNECTING → CONNECTED → READY`,
`transition(event)` verwirft unzulässige/späte Events und gibt den alten Zustand
zurück. `isUsable` ist ausschließlich `READY`. Diese Trennung ist sauber und
wird von `MainActivity` auch benutzt.

### 1.5 Schreibpfad

`BleEngine` serialisiert alle Writes über `writeLock` + `writeQueue: ArrayDeque<PendingWrite>`
+ `writeInFlight` + `currentWrite`:

* Write-Type: `WRITE_TYPE_DEFAULT`, wenn `PROPERTY_WRITE` gesetzt, sonst
  `WRITE_TYPE_NO_RESPONSE` (`processNextWrite`).
* API-33-Pfad (`writeCharacteristic(char, bytes, writeType)` + `BluetoothStatusCodes`)
  und Legacy-Pfad getrennt.
* Retry: `MAX_WRITE_RETRIES = 1` (aktiver Pfad; der Legacy-Stack hat 2).
* Alternative Encodings (`alternatives`) als Fallback-Kette.
* ACK-Timeout `RESPONSE_TIMEOUT_MS = 900L`, aber ein ACK zählt nur, wenn
  `adapter.isAckFor(pending.data, v)` zutrifft — reine Telemetrie bricht den
  Timeout **nicht** ab (`BleEngine.kt:768 ff.`, ausdrücklich kommentiert).
* `failPendingWrites(reason)` entsperrt die Queue bei Disconnect/Discovery-Fehler.

### 1.6 Lesepfad

`readCharacteristic(uuid,label,manual)` + `abortPendingRead` + `armReadTimeout`
(`READ_TIMEOUT_MS = 3_000`) + `ReadOutcome`/`ReadStatus`
(`SUCCESS`/`TIMEOUT`/`ABORTED`/`DISCONNECTED`) + `ReadClassifier`.
`collectReadable(g)` liefert alle lesbaren Characteristics an die UI.

### 1.7 Diagnose

`TelemetryDiagnostics.kt` führt einen `synchronized` Zählerstand:
Chunks, Bytes, Frames, Decode-Erfolge/-Fehler, Pufferstand, Discovery-Status,
Notify-Kette (`notifyCharFound`, `cccdPresent`, `cccdWriteRequested/Accepted/Status`,
`localNotifyRegistrationAttempted/Registered`, `descriptorCallbackReceived/Success`,
`notifyActive`). `TelemetryDiagnosis.cause()` bildet daraus eine **geordnete**
Ursachenkette und liefert bei Erfolg ausdrücklich „dekodiert, aber UNVERIFIZIERT".

**Bewertung:** Die Architektur ist für Phase 16.3.2 eine gute Basis. Sie ist
nicht das Problem. Das Problem liegt eine Ebene höher (Scan/Erkennung) und in
der **Kommunikation** der Ergebnisse an den Nutzer.

---

## 2. Frage 2 — Wie funktioniert die Geräteerkennung?

Die Kette hat sechs Stufen. Ich gehe sie in Ausführungsreihenfolge durch.

### Stufe 1 — Scan starten (`MainActivity.kt:2331–2378`)

```kotlin
val settings = ScanSettings.Builder()
    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
    .build()
bt.bluetoothLeScanner?.startScan(null, settings, leScanCallback)
```

Beobachtungen (belegt):

* **`startScan(null, …)`** → kein `ScanFilter`. Der Scan ist vollständig ungefiltert.
* **Nur `SCAN_MODE_LOW_LATENCY`** gesetzt. Es fehlen:
  `setCallbackType(SCAN_TYPE_ALL_MATCHES)`, `setMatchMode(MATCH_MODE_AGGRESSIVE)`,
  `setReportDelay(0)`, PHY-Einstellungen (`setLegacy`, `setPhy`).
  Der Default von `setCallbackType` ist `CALLBACK_TYPE_ALL_MATCHES` und von
  `setMatchMode` ist `MATCH_MODE_AGGRESSIVE` — insofern ist das **kein Defekt**,
  aber es ist implizit statt explizit. Bei `setReportDelay` ist der Default 0,
  also ebenfalls in Ordnung. Ich führe das hier auf, weil die Frage danach
  lautet — **ich stelle es nicht als Fehler dar, wo keiner ist.**
* `foundDevices.clear()` vor jedem Scan (`:2348`) — korrekt, kein Altbestand.
* 12-s-Timeout über `ScanTimeoutGuard` mit Generationenschutz (`:2365–2377`) —
  korrekt, veraltete Timeouts können keinen neuen Scan killen.

Der Legacy-Stack dagegen filtert (`BleManager.startScan(filtered = true)`) auf
`SCAN_SERVICE_FILTERS = [UART_SERVICE, XIAOMI_SERVICE]`. Das wäre für reale
Ninebot/Navee-Geräte **schädlich**, weil diese `e003`/`ffe0` bewerben, nicht
`6e400001`/`fe95`. Gut, dass dieser Pfad nicht aktiv ist — aber es ist ein
weiterer Grund, ihn nicht zu reaktivieren.

### Stufe 2 — Namensauflösung im Callback (`MainActivity.kt:417–448`)

```kotlin
val name = try {
    if (SDK_INT < S || BLUETOOTH_CONNECT granted)
        device.name ?: result.scanRecord?.deviceName ?: "Unknown Device"
    else result.scanRecord?.deviceName ?: "Unknown Device"
} catch (_: SecurityException) {
    result.scanRecord?.deviceName ?: "Unknown Device"
}
```

Die Reihenfolge ist grundsätzlich richtig: **echter Gerätename → Advertisement-Name →
Platzhalter**. Der Berechtigungsschutz ist korrekt umgesetzt (auf Android 12+ wirft
`device.name` ohne `BLUETOOTH_CONNECT` `SecurityException`).

**Aber:** Der Platzhalter `"Unknown Device"` wird als *Name* in das Datenmodell
geschrieben. Damit ist im gesamten weiteren Verlauf nicht mehr unterscheidbar, ob
ein Gerät „keinen Namen hatte" oder „so heißt". Genau das verlangt Teilziel B.

### Stufe 3 — Kandidatenfilter (`DeviceIdentification.kt:80–102`)

```kotlin
fun looksLikeScooter(name: String?): Boolean {
    val n = name?.trim() ?: return false
    if (n.isEmpty()) return false
    if (n.equals("Unknown Device", ignoreCase = true)) return false
    if (ScooterManufacturer.classify(n).isKnown) return true
    return KEYWORDS.any { n.contains(it, ignoreCase = true) }
}
```

`KEYWORDS` = `scooter`, `e-scooter`, `escooter`, `ninebot`, `segway`, `xiaomi`,
`navee`, `m365`, `soflow`, `kukirin`, `mi scooter`, `mi electric`.

`ScooterManufacturer.classify` ist **bewusst konservativ**: nur eindeutige
Marken-Tokens, sonst `UNKNOWN` — es wird nichts geraten. Das ist korrekt und
entspricht Regel 6/7 der Vorgabe.

**Der Filter arbeitet ausschließlich auf dem Namen.** Es gibt keine zweite
Datenquelle. Das ist die zentrale Ursache für Punkt 3.

### Stufe 4 — Klassifikation (`Phase12Support.kt`, `ScanClassifier.classify(name, address)`)

Drei Kategorien:

* `KNOWN_SCOOTER` — `ScooterModel.detectDetailed` liefert ein bekanntes Modell.
* `PROBABLE_SCOOTER` — Hersteller bekannt **oder** Namens-Hinweis.
* `UNKNOWN_DEVICE` — sonst. **Nie automatisch „Scooter".**

### Stufe 5 — Modellerkennung (`Models.kt:290–380`, `detectDetailed(name, address)`)

* Der Parameter `address` ist **explizit unbenutzt** (`@Suppress("UNUSED_PARAMETER")`).
  Es gibt also **keine OUI-/MAC-basierte Herstellererkennung** — bewusst, weil das
  laut Vorgabe unzulässig wäre („niemals Hersteller/Modell aus MAC-Adressen erfinden").
* Token-Grenzen-Matching, `MIN_MATCH_KEYWORD_LENGTH = 3`.
* Bei Gleichstand mehrerer Keywords → `UNKNOWN` (kein Raten).
* `CONFIRMED` nur, wenn das längste Treffer-Keyword ≥ 5 Zeichen ist **und** eine
  Ziffer enthält. Sonst `PROBABLE`.
* Hersteller-Restriktion: ein Xiaomi-Modell-Keyword kann nicht bei einem
  Navee-Gerät greifen usw.

### Stufe 6 — Anzeige (`MainActivity.kt:2382 ff.` `updateDeviceList()`)

* `foundDevices.values` → `ScanClassifier.classify` → Split in `scooterLike` / `unknown`.
* Statuszeile: `"${all.size} Gerät(e): ${scooterLike.size} Scooter-Kandidat(en), ${unknown.size} sonstige."`
* `showAllDevices`-Toggle, Herstellerfilter, Sortierung (KNOWN → scooterLike → RSSI).
* Jede Zeile zeigt Name, Adresse, RSSI, Kategorie, Brand, Modell, Confidence, VERBINDEN-Button.

**Das ist funktional vollständig — aber es gibt genau eine Informationsquelle: den Namen.**

---

## 3. Frage 3 — Warum erscheinen echte Scooter als unbekannte Geräte?

Ich habe **vier** voneinander unabhängige Ursachen belegt. Sie verstärken sich
gegenseitig; jede einzelne reicht aus, um „16 Treffer, 0 Kandidaten" zu erzeugen.

### U1 — `device.name` ist bei realen Scootern häufig `null` (Hauptursache)

Ein E-Scooter bewerbt im Advertisement in der Regel **keinen** `Complete Local Name`,
sondern nur Flags, TX-Power und ggf. Manufacturer/Service-Data. Der Name kommt erst
nach dem Verbindungsaufbau über GAP (`Device Name`-Characteristic `0x2A00`).
Beim **Scan** ist `device.name` deshalb oft `null` — und `scanRecord.deviceName`
ebenfalls.

Folge: `name = "Unknown Device"` → `ScooterCandidateFilter` gibt `false` zurück
(ausdrücklich, `DeviceIdentification.kt:99`) → `ScanClassifier` → `UNKNOWN_DEVICE`
→ Gerät landet im „sonstige"-Bucket → **0 Kandidaten**.

Das ist **kein Fehler des Filters** — der Filter verhält sich korrekt. Es ist ein
Fehler der **Datenbasis**: es wird nur eine Quelle ausgewertet.

### U2 — Advertisement-Daten werden überhaupt nicht ausgewertet

Ich habe das gesamte `app/src` durchsucht:

```
grep -rn "manufacturerData|getServiceData|serviceUuids|getServiceSolicitation|txPowerLevel"
→ 0 Treffer
```

Es gibt **keine einzige** Stelle im Projekt, die `ScanRecord.manufacturerSpecificData`,
`getServiceData()`, `serviceUuids` oder `txPowerLevel` liest.

Damit bleiben ungenutzt:

* **Service-UUIDs im Advertisement.** Ein Gerät, das `0000e003` oder `0000ffe0`
  bewerbt, ist ein *starker* Hinweis auf Ninebot/Navee — und diese UUIDs sind im
  Projekt bereits als Projektkonstanten vorhanden. Sie werden nur nie gegen das
  Advertisement geprüft.
* **Manufacturer Specific Data (0xFF).** Die Company Identifier (Bluetooth SIG)
  sind eine belastbare, nicht erfundene Quelle. Für Segway/Ninebot ist ein
  Company-ID-Eintrag im Advertisement ein deutlich stärkeres Signal als ein Name.
* **Service Data (0x16).** Enthält bei vielen Herstellern Modell-/Status-Bytes.

Wichtig für die Sicherheit: Eine Auswertung dieser Felder ist **kein Raten**,
sondern das Lesen tatsächlich gesendeter Daten. Sie muss aber konservativ bleiben:
ein unbekanntes Advertisement darf **nie** zu „Scooter" führen, und ein
Service-UUID-Treffer darf **nicht** automatisch „Protokoll kompatibel" bedeuten.

### U3 — Namens-Regression: ein späterer Treffer überschreibt einen guten Namen

`MainActivity.kt:439`:

```kotlin
foundDevices[address] = ScooterDevice(device, name, address, result.rssi, candidate, false)
```

Das ist ein **bedingungsloses Überschreiben** pro Scan-Callback. Ein Gerät, das
zuerst mit Namen gesehen wird und im nächsten Advertisement ohne Namen auftaucht
(oder umgekehrt), verliert den guten Namen und wird zu `"Unknown Device"`.
Der Kandidatenstatus `candidate` wird ebenfalls neu berechnet und kann von
`true` auf `false` kippen. Das ist ein echter, reproduzierbarer Logikfehler —
und er erklärt, warum ein Gerät „manchmal" als Kandidat erscheint und manchmal nicht.

### U4 — Keine Aggregation von Duplikaten; Bericht zählt jeden Callback als neues Gerät

`MainActivity.kt:443–456`: `hardwareTest.recordScan(ScanRecord(..., firstSeenMs = now,
lastSeenMs = now, seenCount = 1))` — bei **jedem** Callback.

Folgen:

* Der Hardware-Testbericht enthält für dasselbe Gerät viele Einträge mit
  `seenCount = 1` statt eines Eintrags mit echtem Zähler und Zeitfenster.
* Die Zahl „16 Scan-Treffer" bei Jayno kann daher sowohl 16 verschiedene Geräte
  als auch 16 Callbacks von wenigen Geräten bedeuten. **Der Bericht ist an dieser
  Stelle nicht belastbar** — das ist ein Diagnose-Defekt, kein Anzeigedetail.

### U5 (nachrangig) — Generische Namens-Präfixe im Legacy-Stack

`BleConstants.kt:46–49`:

```kotlin
val NAME_PREFIXES = listOf(
    "Mi Scooter", "MIScooter", "Mi Electric", "Ninebot", "Segway", "NAVEE", "Navee",
    "M365", "1S", "Pro 2", "ES", "MAX", "F", "G30", "D", "P65",
)
```

`"F"`, `"D"`, `"ES"`, `"1S"`, `"MAX"`, `"G30"`, `"P65"` sind als Präfixe extrem
unspezifisch und würden bei Verwendung massenhaft Fremdgeräte als Scooter
klassifizieren. Diese Liste wird **nur** von `BleManager.ingest` benutzt (Legacy,
nicht aktiv). Sie ist damit aktuell harmlos, aber sie ist eine **Falle** für
jeden, der den Legacy-Pfad reaktiviert. Sie sollte als `LEGACY_UNCONFIRMED`
gekennzeichnet oder entfernt werden — nicht stillschweigend übernommen.

---

## 4. Frage 4 — Wie wird der Bluetooth-Name ermittelt und angezeigt?

### Ermittlung

Genau eine Stelle: `MainActivity.kt:421–432`.

Priorität: `device.name` → `scanRecord.deviceName` → Literal `"Unknown Device"`.
Berechtigungsprüfung korrekt, `SecurityException` abgefangen.

### Speicherung

`Models.kt`, `ScooterDevice`:

```kotlin
data class ScooterDevice(
    val device: BluetoothDevice?,
    val name: String,          // nicht-null!
    val address: String,
    val rssi: Int,
    val isScooterCandidate: Boolean,
    val isDemo: Boolean = false,
)
```

`name` ist **nicht-null `String`**. Damit ist die Information „es gab keinen Namen"
strukturell nicht mehr vorhanden. Es gibt kein Feld für:

* echten GATT-Gerätenamen (nach Verbindung, `0x2A00`),
* Advertisement-Namen,
* Hersteller (aus Advertisement),
* Modell (aus Advertisement),
* interne Geräte-ID,
* bestätigtes Scooter-Profil.

### Anzeige

`updateDeviceList()` zeigt `it.name` direkt. Für ein namenloses Gerät steht dort
also wörtlich „Unknown Device" — was wie ein Gerätename aussieht, aber keiner ist.
Teilziel B verlangt hier eine verständliche, ehrliche Darstellung („Name nicht
verfügbar"), keine Pseudo-Bezeichnung.

`modelEvidence()` (`MainActivity.kt:~2280`) ist hier bereits vorbildlich und
sollte als Muster dienen:

> „Kein Gerätename vorhanden — es wird bewusst kein Modell angenommen."

### Was fehlt (Teilziel B konkret)

1. Ein `DeviceNameSource`-Enum (`GATT_DEVICE_NAME` / `ADVERTISEMENT` / `NONE`) im
   Datenmodell, damit die UI die Herkunft nennen kann.
2. Ein `nullable` Namensfeld statt des Literals.
3. Nach erfolgreicher Verbindung: Nachlesen von `0x2A00` (Generic Access →
   Device Name) und Aktualisierung der Anzeige — das ist eine reale, belastbare
   Quelle und kein Raten.
4. Getrennte Anzeige von: Bluetooth-Name · Hersteller (mit Quelle) · Modell (mit
   Confidence) · Geräte-ID · Profil (bestätigt/vermutet/unbekannt).

---

## 5. Frage 5 — Wie stabil ist der GATT-Verbindungsfluss?

### 5.1 Was bereits solide gelöst ist (im Code vorhanden, teils durch Tests belegt)

| Mechanismus | Ort | Bewertung |
|---|---|---|
| Generationen-Zähler gegen veraltete GATT-Callbacks | `BleEngine` `connectionGeneration`, `isCurrentGatt()`, `closeStaleGatt()` | ✅ korrekt, löst das klassische Stale-GATT-Problem |
| Rückgabewert von `discoverServices()` geprüft | `onConnectionStateChange` | ✅ Fehlschlag wird nicht verschluckt |
| Discovery-Fehler → sauberer Abbau | `onServicesDiscovered` | ✅ `failPendingWrites` + `onDisconnected(true)`, kein Hänger |
| Connect-Timeout 15 s | `CONNECT_TIMEOUT_MS` | ✅ vorhanden, mit `cancelConnectTimeout()` an allen Erfolgspfaden |
| `afterMtu` nur einmal pro Verbindung | `initializationStarted`-Guard | ✅ verhindert doppelte CCCD-Writes |
| Write-Queue serialisiert | `writeLock`, `writeInFlight`, `currentWrite` | ✅ keine parallelen GATT-Operationen |
| ACK-Timeout nur bei echtem ACK aufgehoben | `isAckFor` | ✅ sehr gut, verhindert Fehl-ACK durch Telemetrie |
| `failPendingWrites` bei Disconnect | `onConnectionStateChange`/`disconnect` | ✅ Queue wird entsperrt |
| Read-Tracker mit 3 s Timeout + Status-Enum | `ReadStatus`, `ReadOutcome` | ✅ ehrlich (TIMEOUT ≠ SUCCESS) |
| Reconnect-Policy als reine Funktion | `ReconnectPolicy` | ✅ kein Auto-Reconnect nach bewusstem Trennen, Backoff begrenzt |
| Nur eine Engine gleichzeitig | `ConnectionSwitchPolicy` | ✅ verhindert zwei GATT-Clients |
| Scan-Timeout mit Generation | `ScanTimeoutGuard` | ✅ veralteter Timeout wirkungslos |

Das ist deutlich besser als der typische Zustand vergleichbarer Projekte. Die
Kernursachen für „instabile Verbindung" liegen **nicht** hier.

### 5.2 Verbleibende Risiken (belegt, nicht spekulativ)

**R-GATT-1 — Nur die 2-Argument-Variante von `onCharacteristicChanged` ist überschrieben**
(`BleEngine.kt:768`). Der Kommentar begründet das korrekt: der AOSP-Default der
3-Argument-Variante ruft intern die 2-Argument-Variante auf. Das gilt für AOSP,
**nicht garantiert für jede OEM-Anpassung**. Risiko: auf einzelnen Hersteller-ROMs
können Notifications ausbleiben, obwohl CCCD korrekt aktiviert wurde. Das würde
sich in der Diagnose als „CCCD OK, 0 Notifications" zeigen — genau das Muster aus
Jaynos Bericht. **Ich kann das ohne Hardware nicht entscheiden** und werde es
deshalb nicht als Ursache behaupten, sondern als zu prüfende Hypothese führen.

**R-GATT-2 — `disconnect()` nullt `gatt` vor `old.disconnect()`** (`BleEngine.kt:380 ff.`).
Der DISCONNECTED-Callback des alten GATT wird dadurch von `isCurrentGatt`
verworfen. Die Engine kompensiert das, indem sie `listener.onDisconnected(false)`
selbst aufruft (im Code als PHASE-4-FIX dokumentiert). Funktioniert, ist aber ein
empfindliches Muster: jede künftige Änderung an der Reihenfolge kann hier einen
verschluckten Disconnect erzeugen.

**R-GATT-3 — `writeChar`/`notifyChar` sind `@Volatile`, werden aber teils außerhalb
von `writeLock` zugewiesen.** Für Sichtbarkeit reicht `@Volatile`; für
Konsistenz zwischen Auswahl und Nutzung wäre eine Zuweisung unter `writeLock`
sauberer. Kein akuter Fehler, aber eine Race-Fläche.

**R-GATT-4 — `resetConnectionState()` wird bei `CONNECTED` aufgerufen** und setzt
`telemetryDiagnostics` sowie `adapter.setUnlocked(false)` zurück. Korrekt für einen
neuen Verbindungsaufbau — aber es bedeutet, dass Diagnosezähler einer
*vorherigen* Verbindung beim Reconnect verloren gehen, wenn nicht vorher
exportiert wurde. Für den Hardware-Testbericht relevant.

**R-GATT-5 — Kein explizites `refresh()` des GATT-Cache.** Nach einem
fehlgeschlagenen Verbindungsversuch mit veraltetem Cache kann ein erneuter
`connectGatt` auf einem Android-Gerät mit defektem Cache scheitern (bekanntes
Android-Problem, `BluetoothGatt.refresh()` ist hidden API). Das Projekt löst das
nicht und **darf es auch nicht** über Reflection tun. Ehrliche Konsequenz: als
„bekannte Plattformgrenze" dokumentieren, nicht als App-Fehler.

---

## 6. Frage 6 — Welche konkreten Fehler und Risiken finde ich?

Ich nummeriere sie, damit die Umsetzung in Phase 16.3.2 einzeln nachverfolgbar ist.

| ID | Befund | Ort | Schwere | Beleg |
|---|---|---|---|---|
| **R1** | Advertisement-Daten (Service-UUIDs, Manufacturer Data, Service Data) werden **nirgends** ausgewertet | gesamtes `app/src` | **hoch** | grep: 0 Treffer |
| **R2** | Namenlose Geräte können **prinzipiell** nie Kandidat werden | `DeviceIdentification.kt:96` | **hoch** | Code + Jayno-Befund |
| **R3** | Namens-Regression: späterer Callback überschreibt guten Namen mit `"Unknown Device"` | `MainActivity.kt:439` | **hoch** | Code |
| **R4** | Keine Unterscheidung „echter Name / Advertisement-Name / kein Name" im Datenmodell | `Models.kt` `ScooterDevice` | **hoch** | `name: String` nicht-null |
| **R5** | Scan-Duplikate werden nicht aggregiert; `recordScan` mit `seenCount=1`, `firstSeen=lastSeen=now` bei jedem Callback → Testbericht unbelastbar | `MainActivity.kt:443` | **mittel** | Code |
| **R6** | `onDisconnected` schreibt **immer** einen `DISCONNECT`-Fehler in den Bericht — auch bei bewusstem Trennen | `MainActivity.kt:4336` | **hoch** | Code |
| **R7** | `commErrorCount` wird aus **Log-Texten** hochgezählt (`contains("fehler")` etc.) — also aus Formulierungen, nicht aus Ereignissen | `MainActivity.kt:4258` | **mittel** | Code |
| **R8** | Kopfstatus „Bereit: …"/„Diagnose bereit" kann erscheinen, obwohl **0 Notifications / 0 Frames** vorliegen → verletzt Teilziel D | `MainActivity.kt:4306`, `:2255` | **hoch** | Code |
| **R9** | `PollGate` blockiert Polling bei unbekanntem Modell (`BLOCKED_UNKNOWN_MODEL`) → **es wird nie ein Status-Request gesendet** → 0 TX/0 RX ist **erwartetes Verhalten**, kein GATT-Defekt | `Phase12Support.kt:256`, `MainActivity.kt:3044` | **hoch (Erklärungsbedarf)** | Code |
| **R10** | Legacy-Stack mit widersprüchlichen UUIDs und gefährlicher `NAME_PREFIXES`-Liste weiterhin im Baum | `BleManager.kt`, `BleConstants.kt:46` | **mittel** | Code |
| **R11** | Nur 2-Arg-`onCharacteristicChanged` überschrieben (OEM-Risiko) | `BleEngine.kt:768` | **mittel** | Code + Kommentar |
| **R12** | `disconnect()` nullt `gatt` vor `old.disconnect()` — fragiles Muster | `BleEngine.kt:380` | **niedrig** | Code |
| **R13** | `writeChar`/`notifyChar` teils außerhalb `writeLock` zugewiesen | `BleEngine.kt` | **niedrig** | Code |
| **R14** | Kein `BluetoothGatt.refresh()`; veralteter GATT-Cache nach Fehlversuch bleibt möglich | – | **niedrig (Plattformgrenze)** | bekanntes Android-Verhalten |

### 6.1 Der „10 Fehler"-Befund — konkrete Rechnung

Jaynos Bericht nennt 10 Fehler. Aus dem Code sind folgende Quellen belegbar:

1. **Jedes bewusste Trennen erzeugt einen Fehler** (R6). Bei einem Test mit
   mehreren Verbindungszyklen sind das schnell 3–6 Einträge — ohne jeden Defekt.
2. **`onLog`-Textmuster** (R7): Zeilen mit „fehlgeschlagen", „timeout", „fehler",
   „exception" erhöhen `commErrorCount`. Das umfasst auch informative Zeilen wie
   „Polling blockiert: Modell unbekannt" (falls so formuliert) und
   „WRITE_FAILED: … fehlgeschlagen".
3. **`WRITE_FAILED`** wird für jeden fehlgeschlagenen Write als Fehler erfasst
   (`MainActivity.kt:4384`). Wenn ein Status-Request versucht wird, aber kein
   Write-Characteristic existiert, entstehen mehrere Fehler pro Sekunde.

**Wichtig:** Ich kann die 10 Einträge ohne Jaynos Export-JSON nicht aufschlüsseln.
Ich behaupte daher **nicht**, dass es genau diese Ursachen sind — ich sage: diese
drei Mechanismen sind im Code vorhanden und **erzeugen Fehler unabhängig von einem
echten Defekt**. Das ist ein Diagnose-Problem, kein Verbindungsproblem, und es
erklärt einen erheblichen Teil der Zahl. Die genaue Zuordnung braucht den
exportierten Bericht.

### 6.2 Der „9 Services / 16 Characteristics / MTU 247 / 0 Frames"-Befund

Das ist der technisch interessanteste Teil, und er ist **überwiegend gute Nachricht**:

* 9 Services und 16 Characteristics gefunden → Service-Discovery **hat
  funktioniert**. Der GATT-Baum wurde gelesen.
* MTU 247 → MTU-Negotiation **hat funktioniert**.
* 0 Frames, 0 TX, 0 RX → es wurde **kein einziges Byte** gesendet oder empfangen.

Die Kette `PollGate.evaluate(engineReady, hasWriteChar, modelKnown, adapterSupportsRead)`
(`Phase12Support.kt:247 ff.`) blockiert bei `!modelKnown` mit
`BLOCKED_UNKNOWN_MODEL` und der Begründung „Modell unbekannt — kein Schreibbefehl".

Bei Jaynos E3 Pro D:
* Name im Advertisement offenbar nicht auswertbar → `detectDetailed` → `UNKNOWN`
  → `modelKnown = false`
* → `PollGate` blockiert
* → **es wird nie ein `buildReadStatusFrame()` gesendet**
* → der Controller hat keinen Anlass, Telemetrie zu senden
* → 0 TX, 0 RX, 0 Frames

**Damit ist „0 Frames" kein Beweis für einen GATT- oder Notify-Defekt, sondern die
logische Folge einer Sicherheitsentscheidung.** Diese Sicherheitsentscheidung ist
richtig (kein Schreibbefehl an unbekanntes Gerät), aber sie führt dazu, dass der
Test **nichts** über die Telemetriefähigkeit aussagt. Das muss in der UI und im
Bericht klar kommuniziert werden — aktuell ist es das nicht (R8).

Das ist gleichzeitig der stärkste Hebel für Phase 16.3.2: **Wenn die Erkennung
verbessert wird (R1/R2), wird `modelKnown` wahr, `PollGate` öffnet, und dann — erst
dann — ist ein Telemetrie-Test überhaupt aussagefähig.**

### 6.3 Was ich ausdrücklich **nicht** als Ursache behaupte

* Ich behaupte **nicht**, dass die Ninebot-UUIDs `e003`/`fff1`/`fff2` falsch sind.
  Sie sind Projektkonstanten ohne externen Beleg; ob sie für die E3-Pro-D-Firmware
  gelten, ist **offen**.
* Ich behaupte **nicht**, dass der Adapter `ninebot-generic` inkompatibel ist.
  Der Name im Bericht belegt nur, dass **kein modellspezifischer Adapter** gewählt
  wurde (`modelAdapterExact() == false`), nicht dass das Protokoll nicht passt.
* Ich behaupte **nicht**, dass die 10 Fehler durch einen GATT-Bug entstanden.
  Die drei Mechanismen aus 6.1 erklären sie plausibel, aber die Zuordnung braucht
  den Export.

---

## 7. Frage 7 — Welche Dateien müssen tatsächlich geändert werden?

Ich halte die Änderungen bewusst klein und auf bestehende Strukturen aufbauend.
**Keine Architekturänderung, kein Rewrite.**

### Neue Dateien (reine Kotlin-Logik, ohne Android-Abhängigkeit → unit-testbar)

| Datei | Zweck | Warum neu |
|---|---|---|
| `ScanObservation.kt` | Datenmodell für **einen** Advertisement-Beobachtungspunkt: Adresse, `deviceName` (nullable), Service-UUIDs, Manufacturer-Data, Service-Data, RSSI, Zeitstempel, Quelle des Namens | Es gibt heute kein Modell, das Advertisement-Daten transportiert (R1/R4) |
| `AdvertisementAnalyzer.kt` | Reine Funktion: `ScanObservation → AdvertisementEvidence` (bekannte Service-UUID? bekannter Company-ID? Namens-Hinweis?) mit **explizitem** `confidence` und `source` | Kapselt R1/R2 testbar; keine erfundenen IDs, nur bereits im Projekt vorhandene UUIDs + dokumentierte SIG-Company-IDs |
| `DeviceNameResolver.kt` | Reine Funktion: Priorität echter Name > Advertisement-Name > `null`, plus `DeviceNameSource` | R4; macht „kein Name" explizit statt Pseudo-Name |
| `ScanAggregator.kt` | Führt Duplikate pro Adresse zusammen: `seenCount`, `firstSeen`, `lastSeen`, **Namens-Monotonie** (ein einmal bekannter Name wird nicht durch `null` ersetzt) | R3/R5 |

### Zu ändernde Dateien

| Datei | Änderung | Umfang |
|---|---|---|
| `MainActivity.kt` | Scan-Callback: `ScanObservation` bauen, `ScanAggregator` nutzen, `AdvertisementAnalyzer` + `DeviceNameResolver` einbinden; `updateDeviceList` um Herkunft/Quelle erweitern; `onDisconnected` → `DISCONNECT` nur bei `unexpected`; `onReady`/`connectionStage` → Telemetrie-Status **separat** ausweisen; `onLog`-Fehlerzählung entfernen oder auf strukturierte Ereignisse umstellen | **mittel**, aber lokal begrenzt (4 Methoden) |
| `Models.kt` | `ScooterDevice`: `name: String?` + `nameSource: DeviceNameSource`; `detectDetailed` bleibt signaturgleich | klein |
| `DeviceIdentification.kt` | `ScooterCandidateFilter` um `AdvertisementEvidence`-Overload erweitern; bestehende `looksLikeScooter(name)` **bleibt unverändert** (Rückwärtskompatibilität + bestehende Tests) | klein |
| `Phase12Support.kt` | `ScanClassifier` um Overload mit `AdvertisementEvidence`; bestehende Signatur bleibt | klein |
| `BleConstants.kt` | `NAME_PREFIXES` als `LEGACY_UNCONFIRMED` markieren (Kommentar + ggf. `@Deprecated`), **nicht löschen** (Regel 1) | trivial |
| `diagnostics/HardwareTestReport.kt` | `ScanRecord` um `seenCount`-Aggregation und `nameSource` erweitern; Fehlerkategorie für „bewusst getrennt" ergänzen oder `DISCONNECT` nur bei `unexpected` | klein |
| `BleEngine.kt` | Nur wenn technisch gerechtfertigt: 3-Arg-`onCharacteristicChanged` zusätzlich überschreiben (R11); `writeChar`/`notifyChar`-Zuweisung unter `writeLock` (R13) | **klein und optional**, erst nach Rückfrage |

### Dateien, die ich **nicht** anfassen werde

* `BleManager.kt`, `DashboardScreen.kt`, `DashboardViewModel.kt`, `SyrakApp.kt` —
  Legacy/toter Pfad. Löschen wäre eine Architekturänderung; ich markiere nur.
* `FrameReassembler.kt`, `FrameValidation.kt`, `NinebotAdapter.kt`,
  `NaveeGt3MaxAdapter.kt`, `TelemetryDiagnostics.kt` — funktionieren, kein Anlass.
* Jede UUID, jeder Protokoll-Offset, jede Prüfsummenlogik.

---

## 8. Frage 8 — Welche Tests fehlen?

Vorhanden sind 29 Testdateien / 441 Tests (selbst verifiziert). Für Phase 16.3.2
fehlen konkret:

### Zur Erkennung

1. Gerät **mit** Namen → Kandidat, `DeviceNameSource.GATT_DEVICE_NAME`.
2. Gerät **ohne** Namen, aber mit bekannter Service-UUID `e003` im Advertisement
   → Kandidat mit `confidence = PROBABLE`, Quelle `SERVICE_UUID`.
3. Gerät ohne Namen und ohne Advertisement-Hinweis → **kein** Kandidat,
   Quelle `NONE`, kein geratener Hersteller.
4. Unbekannter Hersteller (Company-ID nicht in der Liste) → `UNKNOWN`, kein Kandidat.
5. Unbekanntes Modell (Name = „Ninebot", aber kein Modell-Token) → Hersteller
   bekannt, Modell `UNKNOWN`, **kein** `CONFIRMED`.
6. **Mehrere gleichzeitige Geräte** → korrekt getrennt, keine Vermischung von
   Name/RSSI/Evidence.
7. **Duplikat-Scan-Ergebnisse** → `seenCount` steigt, `firstSeen` bleibt, RSSI wird
   aktualisiert.
8. **Namens-Regression**: Treffer 1 mit Name, Treffer 2 ohne Name → Name bleibt
   erhalten, `nameSource` bleibt `GATT_DEVICE_NAME`.
9. **Fehlerhafte Advertisement-Daten** (leeres `manufacturerSpecificData`, zu kurze
   Service-Data, `null`-Record) → kein Crash, `UNKNOWN`.
10. Namensauflösungs-Priorität: `device.name` schlägt `scanRecord.deviceName`;
    ohne Berechtigung nur `scanRecord.deviceName`.

### Zur Verbindung

11. Disconnect → Zustand vollständig zurückgesetzt, `connectedDevice = null`.
12. Reconnect → neue Generation, alter GATT wird verworfen.
13. Fehlende GATT-Services → sauberer Abbau, **kein** Hänger.
14. Fehlende Characteristics → `onReady` trotzdem, aber `hasWriteCharacteristic() == false`.
15. Fehlendes CCCD → `notifyChainComplete == false`, Ursache benannt.
16. Fehlgeschlagene Notify-Aktivierung → `notifyActive == false`, Ursache benannt.
17. Keine Notifications empfangen → Ursachenkette endet bei „Notifications == 0".
18. Alter GATT-Instanz nach Disconnect → Callback wird verworfen.

### Zur Ehrlichkeit der Anzeige (Teilziel D — bisher **null** Tests)

19. `engineReady == true`, aber `framesDetected == 0` → Status darf **nicht**
    „Erfolg/bereit" im Sinne von Telemetrie signalisieren.
20. `PollGate` mit unbekanntem Modell → `BLOCKED_UNKNOWN_MODEL`, **kein** Write.
21. Bewusstes Trennen → **kein** `DISCONNECT`-Fehler im Bericht.
22. Unerwartetes Trennen → **doch** `DISCONNECT`-Fehler.
23. `commErrorCount` reagiert nicht auf reine Textformulierungen.

Diese 23 Tests sind alle ohne Hardware schreibbar, weil sie auf reiner Logik
basieren. Das ist der Grund, warum ich `ScanObservation`/`AdvertisementAnalyzer`/
`DeviceNameResolver`/`ScanAggregator` als **reine** Kotlin-Module plane.

---

## 9. Frage 9 — Welche Verbesserungen haben höchste Priorität?

Ich ordne nach „belegter Ursache × Wirkung auf Jaynos Befund × Risiko".

### Priorität 1 — Erkennung über Advertisement-Daten (R1, R2)

**Warum zuerst:** Es ist die einzige Ursache, die den Kernbefund „16 Treffer,
0 Kandidaten" direkt erklärt, und sie ist ohne Hardware testbar. Solange sie
besteht, ist **jeder** weitere Telemetrie-Test wertlos, weil `PollGate` nie öffnet.

**Umfang:** `ScanObservation` + `AdvertisementAnalyzer` + Einbindung im Callback.
**Sicherheitsgrenze:** Nur bereits im Projekt vorhandene UUIDs (`e003`, `ffe0`)
und dokumentierte SIG-Company-IDs. Unbekanntes Advertisement → `UNKNOWN`.
Ein Service-UUID-Treffer bedeutet **„Kandidat"**, **nicht** „kompatibel".

### Priorität 2 — Ehrliche Statussprache (R8, R9)

**Warum:** Teilziel D ist eine **harte Anforderung**, und aktuell wird sie verletzt.
Ein erfolgreicher GATT-Connect wird als „Bereit" angezeigt, obwohl 0 Frames
vorliegen. Das ist genau der Fehler, den die Vorgabe verbietet.

**Umfang:** `onReady` und `connectionStage` in drei getrennte Aussagen zerlegen:
1. Verbindung (GATT),
2. Dienst/Notify (Kette),
3. Telemetrie (Frames/Decode).
Jede mit eigenem Status und eigener Farbe. „Bereit" nur, wenn Telemetrie
tatsächlich fließt oder wenn explizit „verbunden, keine Telemetrie" steht.

### Priorität 3 — Namensintegrität (R3, R4, R5)

**Warum:** Billig umzusetzen, hoher Effekt. Verhindert, dass ein Gerät „verschwindet",
und macht „kein Name" ehrlich sichtbar. `ScanAggregator` löst R3 und R5 in einem.

### Priorität 4 — Fehlerzählung entkoppeln (R6, R7)

**Warum:** Ohne das ist der Hardware-Testbericht nicht interpretierbar. Solange
bewusstes Trennen als Fehler zählt, ist „10 Fehler" keine Information.

### Priorität 5 — GATT-Feinschliff (R11, R12, R13)

**Warum zuletzt:** Diese Punkte sind plausibel, aber **nicht belegt**. Ich werde
sie nur mit ausdrücklicher Freigabe anfassen, weil jede Änderung am Callback-Override
ein Regressionsrisiko auf funktionierender Hardware darstellt. R12/R13 sind
Kosmetik ohne belegten Fehler — ich empfehle, sie **nicht** anzufassen.

### Priorität 6 — Legacy-Stack kennzeichnen (R10)

**Warum:** Kein Funktionsgewinn, aber Verhinderung künftiger Fehler. Nur
Kommentar/Annotation, kein Löschen.

---

## 10. Zusammenfassung in einem Absatz

Die BLE-Engine (`BleEngine`) ist technisch in einem guten Zustand: Generationenschutz
gegen veraltete GATT-Callbacks, geprüfter `discoverServices`-Rückgabewert,
sauberer Abbau bei Discovery-Fehler, einmalige CCCD-Aktivierung pro Verbindung,
serialisierte Write-Queue mit echtem ACK-Kriterium, Read-Tracker mit ehrlichem
Status-Enum, und eine sechsstufige Diagnose-Ursachenkette. Die dokumentierten
Defekte aus Phase 16.2/16.3/16.3.1 sind im Code nachvollziehbar behoben, und die
441 Tests laufen grün. **Das Problem liegt eine Ebene darüber:** Die Geräteerkennung
wertet ausschließlich den BLE-Namen aus und ignoriert sämtliche Advertisement-Daten,
obwohl reale E-Scooter im Scan regelmäßig keinen Namen senden. Dadurch bleibt
`modelKnown` falsch, `PollGate` blockiert jeden Schreibbefehl, und es entstehen
0 TX / 0 RX / 0 Frames — ein **erwartetes** Ergebnis der Sicherheitssperre, kein
GATT-Defekt. Gleichzeitig meldet die UI „Bereit", obwohl keine Telemetrie fließt,
und der Hardware-Testbericht zählt bewusste Trennungen als Fehler. Die höchste
Priorität hat daher: Advertisement-basierte Erkennung (ohne falsche Positive),
ehrliche Trennung von Verbindungs- und Telemetriestatus, Namensintegrität über
Duplikate hinweg, und eine Fehlerzählung, die Ereignisse statt Formulierungen zählt.

---

## 11. Was ich als Nächstes tun würde — und was ich **nicht** tue

**Vorgeschlagenes Vorgehen (nach Freigabe):**

1. Feature-Branch `feat/phase16.3.2-scan-and-detection` von `fa6efe2`.
2. Neue reine Kotlin-Module + Tests (Priorität 1–4). Kein Android-Code in den
   neuen Modulen → sofort testbar.
3. Einbindung in `MainActivity` in vier klar abgegrenzten Methoden.
4. `./gradlew testDebugUnitTest` → bestehende 441 Tests müssen **unverändert**
   grün bleiben, neue Tests kommen hinzu.
5. `./gradlew assembleDebug`.
6. Bericht mit exakten Testzahlen vorher/nachher, geänderten Dateien und
   expliziter Liste „was weiterhin Hardware-Verifikation braucht".
7. Push auf den Feature-Branch (kein Merge nach `main` ohne deine Freigabe).

**Was ich ausdrücklich nicht tue:**

* keine Änderung an UUIDs, Protokoll-Offsets, Prüfsummen oder Decodern,
* keine neuen Schreibbefehle, keine Tuning-Funktionen, keine Firmware-Themen,
* keine Behauptung „kompatibel"/„bestätigt" ohne Hardware-Beleg,
* kein Löschen bestehender Funktionen oder Tests,
* keine Architekturänderung, kein Rewrite,
* keine Änderung an `BleEngine`-Callbacks ohne deine ausdrückliche Freigabe (R11).

---

## 12. Offene Fragen an dich (bitte vor der Umsetzung klären)

1. **Jaynos Export-JSON:** Kannst du den Hardware-Testbericht (JSON/CSV) der
   E3-Pro-D-Sitzung bereitstellen? Damit könnte ich die 10 Fehler **exakt**
   zuordnen statt sie nur plausibel zu erklären.
2. **Advertisement-Auswertung:** Einverstanden, dass ich Service-UUIDs (`e003`,
   `ffe0`) und dokumentierte SIG-Company-IDs als **Kandidaten-Hinweis** nutze —
   ausdrücklich **nicht** als Kompatibilitätsbeweis?
3. **`BleEngine`-Callbacks (R11):** Soll ich die 3-Argument-Variante von
   `onCharacteristicChanged` zusätzlich überschreiben (höhere OEM-Kompatibilität,
   aber Regressionsrisiko), oder erst nach einem gezielten Hardware-Test?
4. **Legacy-Stack:** Nur kennzeichnen (mein Vorschlag) oder langfristig entfernen?
   Entfernen wäre eine Architekturänderung und bräuchte eine eigene Freigabe.

---

**Ende des Ist-Zustand-Berichts. Es wurde kein Produktivcode verändert.**
Ich warte auf deine Freigabe, bevor ich mit der Umsetzung beginne.
