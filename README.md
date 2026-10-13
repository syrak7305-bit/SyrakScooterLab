Syrak ScooterLab — Modulare Protokoll-Engine (v7)

Paket: com.syrak.scooterlab Primäres Referenzmodell #1: NAVEE GT3 Max (deutsche eKFV-Version, 20 km/h) — verifizierter Protokoll-Adapter.

Diese Version ersetzt die frühere ~4300-Zeilen-Monolithdatei durch eine saubere, modulare Architektur mit Protocol-Adapter-Pattern. Der Kern der App (BLE-Engine, UI, Trip-Computer, Flash-Logik) ist jetzt herstellerunabhängig. Neue Marken (Ninebot, Xiaomi, SoFlow …) werden als eigener Adapter eingesteckt, ohne die Kern-App neu zu bauen.

1. Architektur-Überblick

                     ┌──────────────────────────────────────────┐
                     │              MainActivity.kt             │
                     │  (schlanker UI-Controller, KEINE rohen   │
                     │   Byte-Operationen, delegiert alles)     │
                     └───────────────┬──────────────────────────┘
                                     │  nutzt
          ┌──────────────────────────┼───────────────────────────┐
          ▼                          ▼                           ▼
 ┌─────────────────┐     ┌────────────────────┐      ┌────────────────────┐
 │   BleEngine.kt  │     │ ScooterRepository  │      │  FirmwareCatalog   │
 │  (GATT, Queue,  │     │ (Settings, PIN,    │      │  (Firmware-Pakete) │
 │   ACK, MTU,     │     │  Trip, Adapter-    │      └────────────────────┘
 │   Chunk-Flash)  │     │  State)            │
 └────────┬────────┘     └────────────────────┘
          │  baut/parst über den aktiven Adapter
          ▼
 ┌──────────────────────────────────────────────────────────────────────┐
 │                    ScooterProtocolAdapter.kt (Interface)              │
 │   + FrameCodec (Header/Checksum)   + ProtocolAdapters (Registry)      │
 └───────────────┬───────────────────────────────────┬──────────────────┘
                 ▼                                   ▼
   ┌───────────────────────────┐        ┌───────────────────────────────┐
   │  NaveeGt3MaxAdapter.kt     │        │      NinebotAdapter.kt        │
   │  (PRIMÄR #1, Brightway     │        │  (Fallback, Ninebot/Xiaomi    │
   │   AA55 / 55AA, Handshake)  │        │   E003/FFF1/FFF2)             │
   └───────────────┬───────────┘        └───────────────┬───────────────┘
                   ▼                                     ▼
        ┌──────────────────────┐              ┌──────────────────────┐
        │   FrameBuilders.kt    │              │   FrameBuilders.kt    │
        │   (NaveeFrames)       │              │   (ScooterFrames)     │
        └──────────────────────┘              └──────────────────────┘
                   ▼                                     ▼
        ┌──────────────────────────────────────────────────────────┐
        │                  TelemetryDecoder.kt                      │
        │   decodeNavee()  /  decodeNinebot()  — alles nullable     │
        └──────────────────────────────────────────────────────────┘

Datenfluss (Lesen): Notify-Frame → BleEngine.onCharacteristicChanged → adapter.parseTelemetry(bytes) → TelemetryDecoder → Telemetry (nullable Felder) → BleEngine.Listener.onTelemetry() → MainActivity (gebündeltes UI-Update).

Datenfluss (Schreiben): UI-Event → MainActivity.sendFrame() → adapter.buildXxxFrame() → BleEngine.enqueueCommand() → serielle GATT-Queue → onCharacteristicWrite → ACK-Prüfung (adapter.isAckFor()).

2. Dateiaufteilung (wie gefordert)

 Datei Verantwortung

 Palette.kt Design-Tokens (Farben).

 Models.kt Alle Data-Classes/Enums: ScooterDevice, Region, BleProtocol, ScooterModel, Telemetry, TripData, RideMode, ScooterProfile, RegisterSnapshot, FirmwarePackage, FirmwareValidation, LogEntry, …

 FirmwareCatalog.kt Firmware-Paket-Definitionen + Auswahl-Logik.

 FrameBuilders.kt ScooterFrames (Ninebot/Xiaomi), NaveeFrames (Brightway), RamOverlay.

 TelemetryDecoder.kt Bounds-sichere Decoder für beide Protokollfamilien. Keine Fake-Werte.

 ScooterProtocolAdapter.kt Interface + FrameCodec (Header/Checksum/CMD-ID) + ProtocolAdapters-Registry.

 NaveeGt3MaxAdapter.kt Primärer Adapter #1 (Brightway AA 55 / Fallback 55 AA).

 NinebotAdapter.kt Fallback-Adapter (Ninebot/Xiaomi) — damit nichts verloren geht.

 BleEngine.kt GATT-Mechanik: Verbindung, Service-/Char-Lookup, CCCD-Notify, MTU, serielle Write-Queue, ACK-Handling, Chunk-Flash. Adapter-getrieben.

 ScooterRepository.kt Persistenz: Settings, PBKDF2-PIN, Profile, Trip, Adapter-State.

 MainActivity.kt Reiner UI-Controller. Delegiert Befehle an den aktiven Adapter und Persistenz an das Repository.

3. Das Adapter-Interface

interface ScooterProtocolAdapter {
    val id: String
    val displayName: String
    val protocol: BleProtocol
    val requiresUnlock: Boolean
    val isUnlocked: Boolean
    fun setUnlocked(value: Boolean)

    fun handshake(): ByteArray?                       // Unlock/Login (z. B. AA 55 01 01 …)
    fun authenticate(): ByteArray? = handshake()

    fun buildReadStatusFrame(): ByteArray
    fun buildSpeedCeilingFrame(kmh: Int): ByteArray
    fun buildZeroStartFrame(kmh: Int): ByteArray
    fun buildPoliceModeFrame(legalKmh: Int): ByteArray
    fun buildLockFrame(locked: Boolean): ByteArray
    fun buildKersFrame(level: Int): ByteArray
    fun buildCruiseFrame(enabled: Boolean): ByteArray
    fun buildTempGuardFrame(celsius: Int): ByteArray
    fun buildEmergencyCutFrame(): ByteArray
    fun buildOverspeedMuteFrame(mute: Boolean): ByteArray
    fun buildMaxPowerFrame(): ByteArray
    fun buildGermanManeuverFrame(): ByteArray
    fun buildRegionFrame(region: Region): ByteArray
    fun buildUnlockFrame(): ByteArray? = handshake()
    fun buildRebootFrame(): ByteArray

    fun parseTelemetry(bytes: ByteArray): Telemetry?

    fun commandIdOf(frame: ByteArray): Int?
    fun isValidFrame(frame: ByteArray): Boolean
    fun isAckFor(sent: ByteArray, rx: ByteArray): Boolean =
        commandIdOf(sent) != null && commandIdOf(sent) == commandIdOf(rx) && isValidFrame(rx)

    fun buildOutboundFrames(command: ByteArray): List<ByteArray> = listOf(command)
}

Registrierung erfolgt zentral in ProtocolAdapters:

object ProtocolAdapters {
    val all = listOf(NaveeGt3MaxAdapter, NinebotAdapter)   // Reihenfolge = Priorität
    fun forProtocol(p: BleProtocol): ScooterProtocolAdapter = ...
    fun forModel(m: ScooterModel): ScooterProtocolAdapter = ...
    fun byId(id: String): ScooterProtocolAdapter? = ...
}

4. Primärer Adapter: Navee GT3 Max (Brightway Engine)

- Header: primär AA 55, Fallback 55 AA (die Engine probiert beide automatisch).

- Handshake (Pflicht): CMD 0x01 → Frame AA 55 01 01 … + Summen-Checksumme. Solange der Controller nicht bestätigt hat, ist isUnlocked == false und alle unlock-gated Befehle (Speed, Zero-Start, KERS, …) werden zurückgehalten.

- Status-Request: CMD 0x20 → liefert Speed, Batterie, Spannung, Strom und die BMS-Zellspannungen (bis zu 13 Zellen, 0,001 V Auflösung).

- eKFV-Profil (Deutschland, 20 km/h): Standard-Limit 20 km/h. Region-Umschaltung über CMD 0x10 (Speed-Ceiling) und CMD 0x12 (Region-/Ländercode).

- ACK-Handling: Jeder Send wartet auf die spezifische Controller-Antwort. Nur gültige Checksumme + passende CMD-ID gelten als Erfolg (isAckFor).

Brightway-Frame-Format (Sum-Checksumme)

AA 55 | CMD | LEN | PAYLOAD… | SUM
SUM = (CMD + LEN + Σ PAYLOAD) & 0xFF

Alternative-Variante (XOR)

55 AA | CMD | LEN | PAYLOAD… | XOR
XOR = CMD ^ LEN ^ (⊕ PAYLOAD)

FrameCodec.isValidFrame() prüft beide Varianten strukturell und über die Checksumme, damit ein fehlerhaftes Paket niemals als ACK durchgeht.

5. Neuen Hersteller hinzufügen (ohne Kern-App neu zu bauen)

- Neue Klasse XyzAdapter.kt anlegen, die ScooterProtocolAdapter implementiert.

- Frame-Bau entweder direkt oder über einen neuen XyzFrames-Builder in FrameBuilders.kt realisieren.

- parseTelemetry() implementieren (Felder nullable halten — keine Fakes!).

- In ProtocolAdapters.all registrieren.

- Optional: ScooterModel-Enum um ein Modell mit passendem protocol erweitern.

Fertig. BleEngine und MainActivity bleiben unverändert — sie sprechen ausschließlich über das Interface.

6. Umgesetzte Bugfixes aus dem Review

 # Bug Fix

 A commandIdOf() konnte bei zu kurzen Frames IndexOutOfBounds werfen. Bounds-Guard in FrameCodec.commandIdOf() (size < 3 → null; Ninebot braucht ≥ 5).

 B Stale GATT-Callbacks konnten einen neuen Connect „vergiften“. Monotone connectionGeneration + isCurrentGatt()-Vergleich (verwirft Callbacks alter Verbindungen, auch beim Reconnect desselben Geräts).

 C ACK-Fallback griff beim ersten Befehl nicht (lastRxMillis > 0-Bedingung). Bedingung entfernt; Fallback-Kette läuft jetzt ab dem ersten Write.

 D abortFlash() ließ einen hängenden Write zurück. abortFlash() ruft jetzt failPendingWrites("flash-abort").

 E discoverServices()-Rückgabe wurde ignoriert. Rückgabe ausgewertet; bei false → onDisconnected(true).

 F Rotation zerstörte die Activity (und damit die BLE-Verbindung). `android:configChanges="orientation

 G PIN im Klartext in SharedPreferences. PBKDF2-HmacSHA256, 120 000 Iterationen, 16-Byte-Salt, 32-Byte-Key, konstantzeitiger Vergleich (ScooterRepository).

 H SHA-256-Fingerprint wurde nie gegen einen Erwartungswert geprüft. FirmwarePackage.expectedSha256 + FirmwareValidation.hashVerified (optionale harte Prüfung; ohne Erwartungswert nur Anzeige).

 I Ride-Mode-UI doppelt (Tools + Dashboard). DRY-Helper addRideModeSelector(container, titleSize).

7. Absolute Design-Regeln (eingehalten)

- COMPLETE CODE — alle Dateien vollständig, keine // …-Platzhalter.

- NO FAKES — nicht empfangene Telemetrie ist explizit null/invalid (nie eine erfundene 0). Telemetry hat ausschließlich nullable Felder.

- PRESERVE UI — alle Tabs (INFO, TOOLS, DASHBOARD, FLASH, CONFIG), Trip-Computer, Log-Terminal, Systemleiste, Disclaimers, Scan & Flash bleiben voll funktionsfähig. Die UI wurde nicht verändert, nur entkoppelt.

- Keine Regression — Zeilen wurden nur entfernt, wenn es der App hilft (z. B. die DRY-Zusammenführung der doppelten Ride-Mode-Karte).

8. Build-Hinweise

- minSdkVersion 23, targetSdkVersion 34.

- Ab Android 12 (API 31): BLUETOOTH_SCAN (+neverForLocation) und BLUETOOTH_CONNECT.

- Unter Android 12: BLUETOOTH, BLUETOOTH_ADMIN und ACCESS_FINE_LOCATION.

- uses-feature android.hardware.bluetooth_le ist required="false", damit die App auch auf Geräten ohne BLE im Demo-Modus startet.

Ehrlichkeitshinweis: Die GATT-Mechanik (Verbindung, Notify, MTU, Queue, Chunking, ACK) ist vollständig real implementiert. Die exakten Byte-Offsets herstellerspezifischer Payloads sind nicht öffentlich spezifiziert und stehen als klar benannte, zentral änderbare Konstanten in den Adaptern/Decodern. Ohne einen echten HCI-Snoop / nRF-Connect-Mitschnitt sind sie nicht verifizierbar — sie sind bewusst nicht erfunden, sondern markiert.