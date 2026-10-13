package com.syrak.scooterlab

/* ============================================================================
 *  SCOOTER-PROTOKOLL-ADAPTER
 *  ----------------------------------------------------------------------------
 *  ZENTRALE ABSTRAKTION des Protokoll-Layers.
 *
 *  Der komplette Rest der App (BleEngine, MainActivity, ScooterRepository)
 *  kennt AUSSCHLIESSLICH dieses Interface. Es gibt im UI-/Engine-Code KEINE
 *  herstellerspezifischen Byte-Operationen mehr. Neue Marken (Ninebot, Xiaomi,
 *  SoFlow, ...) werden als reine Zusatz-Datei implementiert und ueber die
 *  Registry [ProtocolAdapters] registriert — der Kern muss NICHT neu gebaut
 *  werden.
 *
 *  Primäres Referenzmodell #1: NAVEE GT3 MAX (deutsche eKFV-Version, 20 km/h)
 *  -> siehe [NaveeGt3MaxAdapter] (Brightway-Engine, AA 55 / 55 AA).
 * ==========================================================================*/

interface ScooterProtocolAdapter {

    /** Eindeutige, stabile ID des Adapters (z. B. "navee-gt3max"). */
    val id: String

    /** Anzeigename fuer UI + Logs. */
    val displayName: String

    /** Protokollfamilie — bestimmt Service-/Characteristic-Lookup in der BleEngine. */
    val protocol: BleProtocol

    /**
     * true, wenn vor Tuning-Befehlen zwingend ein Unlock-/Auth-Handshake
     * gesendet werden muss (Brightway verlangt das, Ninebot nicht).
     */
    val requiresUnlock: Boolean

    /** true, sobald der Controller den Unlock-Handshake quittiert hat. */
    val isUnlocked: Boolean

    /** Setzt den Unlock-Zustand (nach erfolgreichem Handshake bzw. bei Disconnect). */
    fun setUnlocked(unlocked: Boolean)

    /* ------------------------------------------------------------------ */
    /*  HANDSHAKE / AUTHENTIFIZIERUNG                                      */
    /* ------------------------------------------------------------------ */

    /**
     * Unlock-/Auth-Frame. Liefert null, wenn das Protokoll keinen braucht.
     * Brightway (GT3 Max): `AA 55 01 01 01` (CMD 0x01, Payload 0x01).
     */
    fun handshake(): ByteArray?

    /** Alias fuer [handshake] — der Auftrag nennt beide Begriffe. */
    fun authenticate(): ByteArray? = handshake()

    /* ------------------------------------------------------------------ */
    /*  KERN-BEFEHLE (vom Auftrag explizit gefordert)                      */
    /* ------------------------------------------------------------------ */

    /** Status-/Telemetrie-Request (GT3 Max: CMD 0x20). */
    fun buildReadStatusFrame(): ByteArray

    /** Setzt das Geschwindigkeits-Limit (GT3 Max: CMD 0x10). */
    fun buildSpeedCeilingFrame(kmh: Int): ByteArray

    /** Setzt den Anfahrwert / Zero-Start (GT3 Max: CMD 0x11). */
    fun buildZeroStartFrame(kmh: Int): ByteArray

    /** Legt den legalen Police-Mode-Wert fest (GT3 Max: CMD 0x1F). */
    fun buildPoliceModeFrame(legalKmh: Int): ByteArray

    /* ------------------------------------------------------------------ */
    /*  WEITERE TUNING-BEFEHLE                                             */
    /* ------------------------------------------------------------------ */

    fun buildLockFrame(locked: Boolean): ByteArray
    fun buildKersFrame(level: Int): ByteArray
    fun buildCruiseFrame(enabled: Boolean): ByteArray
    fun buildTempGuardFrame(celsius: Int): ByteArray
    fun buildEmergencyCutFrame(): ByteArray
    fun buildOverspeedMuteFrame(mute: Boolean): ByteArray
    fun buildMaxPowerFrame(): ByteArray
    fun buildGermanManeuverFrame(): ByteArray

    /** Regions-/Laenderumschaltung (GT3 Max: CMD 0x12). */
    fun buildRegionFrame(region: Region): ByteArray

    /* ------------------------------------------------------------------ */
    /*  FLASH                                                              */
    /* ------------------------------------------------------------------ */

    /** Frame, der vor dem Flashen entsperren muss (Standard: [handshake]). */
    fun buildUnlockFrame(): ByteArray? = handshake()

    /** Reboot-/Finalize-Frame nach dem Flashen. */
    fun buildRebootFrame(): ByteArray

    /* ------------------------------------------------------------------ */
    /*  PARSING                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Dekodiert einen Notify-Frame zu [Telemetry]. Liefert null, wenn der Frame
     * nicht zu diesem Protokoll passt ODER strukturell ungueltig ist.
     * Es werden NIEMALS Werte erfunden: nicht empfangene Felder bleiben null.
     */
    fun parseTelemetry(bytes: ByteArray): Telemetry?

    /* ------------------------------------------------------------------ */
    /*  FRAME-METADATEN (fuer die ACK-Auswertung in der BleEngine)         */
    /* ------------------------------------------------------------------ */

    /** Liest die Kommando-ID eines Frames (protokollspezifisch). */
    fun commandIdOf(frame: ByteArray): Int?

    /** Strukturelle + Pruefsummen-Validierung eines Frames. */
    fun isValidFrame(frame: ByteArray): Boolean

    /**
     * Prueft, ob [rx] ein ECHTES Controller-ACK fuer den gesendeten Befehl [sent]
     * ist: gleiche Kommando-ID UND strukturell gueltiger Frame. Reine Telemetrie
     * (andere CMD) zaehlt ausdruecklich NICHT als ACK.
     */
    fun isAckFor(sent: ByteArray, rx: ByteArray): Boolean {
        val a = commandIdOf(sent) ?: return false
        val b = commandIdOf(rx) ?: return false
        return a == b && isValidFrame(rx)
    }

    /**
     * Liefert die Ausgangs-Frames fuer einen Befehl als Fallback-Kette
     * (erster Eintrag = Standard-Kodierung). Brightway liefert z. B.
     * [Standard-Header AA 55, Alt-Header 55 AA]; Ninebot nur [Standard].
     */
    fun buildOutboundFrames(command: ByteArray): List<ByteArray> = listOf(command)
}

/* ============================================================================
 *  FRAME-CODEC — generische Frame-Metadaten fuer ALLE Protokollfamilien.
 *  Wird von den Adaptern als Default-Implementierung genutzt, damit die
 *  ACK-/Validierungslogik nicht dupliziert werden muss.
 * ==========================================================================*/
object FrameCodec {

    /**
     * Liest die Kommando-ID protokollabhaengig aus:
     *   Ninebot   5A A5 | LEN | ADDR | CMD | ...  -> CMD @ Index 4
     *   Brightway AA 55 | CMD | LEN | ...         -> CMD @ Index 2
     *   Brightway 55 AA | CMD | LEN | ...         -> CMD @ Index 2
     * FIX: Bounds-Guard korrekt (Ninebot braucht mind. 5 Bytes, nicht 4).
     */
    fun commandIdOf(frame: ByteArray): Int? {
        if (frame.size < 3) return null
        val b0 = frame[0].toInt() and 0xFF
        val b1 = frame[1].toInt() and 0xFF
        return when {
            b0 == 0x5A && b1 == 0xA5 -> if (frame.size >= 5) frame[4].toInt() and 0xFF else null
            b0 == 0xAA && b1 == 0x55 -> frame[2].toInt() and 0xFF
            b0 == 0x55 && b1 == 0xAA -> frame[2].toInt() and 0xFF
            else -> null
        }
    }

    /** Strukturelle + Pruefsummen-Validierung eines Frames. */
    fun isValidFrame(frame: ByteArray): Boolean {
        if (frame.size < 5) return false
        val b0 = frame[0].toInt() and 0xFF
        val b1 = frame[1].toInt() and 0xFF
        val sum = frame[frame.size - 1].toInt() and 0xFF
        return when {
            b0 == 0x5A && b1 == 0xA5 -> {
                if (frame.size < 6) return false
                val len = frame[2].toInt() and 0xFF
                if (len + 4 != frame.size) return false
                var s = 0
                for (i in 2 until frame.size - 1) s += frame[i].toInt() and 0xFF
                ((0x100 - (s and 0xFF)) and 0xFF) == sum
            }
            b0 == 0xAA && b1 == 0x55 -> {
                val len = frame[3].toInt() and 0xFF
                if (len + 5 != frame.size) return false
                var s = 0
                for (i in 0 until frame.size - 1) s += frame[i].toInt() and 0xFF
                (s and 0xFF) == sum
            }
            b0 == 0x55 && b1 == 0xAA -> {
                val len = frame[3].toInt() and 0xFF
                if (len + 5 != frame.size) return false
                var x = 0
                for (i in 0 until frame.size - 1) x = x xor (frame[i].toInt() and 0xFF)
                (x and 0xFF) == sum
            }
            else -> false
        }
    }
}

/* ============================================================================
 *  ADAPTER-REGISTRY
 *  ----------------------------------------------------------------------------
 *  Einzige Stelle, an der neue Marken registriert werden. Der Kern der App
 *  (Engine/UI) fragt ausschliesslich hier nach dem passenden Adapter.
 * ==========================================================================*/
object ProtocolAdapters {

    private val naveeGt3Max = NaveeGt3MaxAdapter()
    private val ninebot = NinebotAdapter()

    /** Alle registrierten Adapter (Reihenfolge = Prioritaet fuer Auto-Detect). */
    val all: List<ScooterProtocolAdapter> = listOf(naveeGt3Max, ninebot)

    /** Liefert den Adapter fuer eine Protokollfamilie. */
    fun forProtocol(protocol: BleProtocol): ScooterProtocolAdapter = when (protocol) {
        BleProtocol.NAVEE_BRIGHTWAY -> naveeGt3Max
        BleProtocol.NINEBOT -> ninebot
    }

    /** Liefert den Adapter fuer ein erkanntes Modell. */
    fun forModel(model: ScooterModel): ScooterProtocolAdapter = forProtocol(model.protocol)

    /** Liefert den Adapter anhand seiner ID (null, wenn unbekannt). */
    fun byId(id: String): ScooterProtocolAdapter? = all.firstOrNull { it.id == id }
}
