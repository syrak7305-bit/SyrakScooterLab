package com.syrak.scooterlab

/* ---------------------------------------------------------------------------
 *  NINEBOT / XIAOMI FRAME-BUILDER  (5A A5 | LEN | ADDR | CMD | PAYLOAD | SUM)
 * ------------------------------------------------------------------------- */
object ScooterFrames {
    private const val HEADER_1 = 0x5A
    private const val HEADER_2 = 0xA5

    /**
     * Ninebot-Prüfsumme.
     * ACHTUNG: Die exakte Formel ist firmwareabhängig. Diese Variante entspricht
     * der in der Community am häufigsten verwendeten (Summe ab LEN, Zweierkomplement).
     * Bei echten Schreibversuchen MUSS sie gegen einen Mitschnitt verifiziert werden.
     */
    private fun checksum(frame: ByteArray): Byte {
        var sum = 0
        for (i in 2 until frame.size - 1) sum += frame[i].toInt() and 0xFF
        return ((0x100 - (sum and 0xFF)) and 0xFF).toByte()
    }

    fun buildFrame(address: Int, cmd: Int, payload: ByteArray): ByteArray {
        val frame = ByteArray(6 + payload.size)
        frame[0] = HEADER_1.toByte()
        frame[1] = HEADER_2.toByte()
        frame[2] = (payload.size + 2).toByte()
        frame[3] = address.toByte()
        frame[4] = cmd.toByte()
        System.arraycopy(payload, 0, frame, 5, payload.size)
        frame[frame.size - 1] = checksum(frame)
        return frame
    }

    fun zeroStart(speedKmh: Int): ByteArray = buildFrame(0x20, 0x72, byteArrayOf(speedKmh.toByte()))
    fun maxSpeed(speedKmh: Int): ByteArray = buildFrame(0x20, 0x71, byteArrayOf(speedKmh.toByte()))
    fun maxPower(): ByteArray = buildFrame(0x20, 0x73, byteArrayOf(0x01))
    fun lock(locked: Boolean): ByteArray = buildFrame(0x20, 0x7A, byteArrayOf(if (locked) 1 else 0))
    fun germanManeuver(): ByteArray = buildFrame(0x20, 0x78, byteArrayOf(0x01))
    fun policeMode(legalKmh: Int): ByteArray = buildFrame(0x20, 0x79, byteArrayOf(legalKmh.toByte()))
    fun kers(level: Int): ByteArray = buildFrame(0x20, 0x7B, byteArrayOf(level.toByte()))
    fun cruise(enabled: Boolean): ByteArray = buildFrame(0x20, 0x7C, byteArrayOf(if (enabled) 1 else 0))
    fun motorTempGuard(celsius: Int): ByteArray = buildFrame(0x20, 0x7D, byteArrayOf(celsius.toByte()))
    fun emergencyCut(): ByteArray = buildFrame(0x20, 0x7E, byteArrayOf(0x00))
    fun overspeedAlarmMute(mute: Boolean): ByteArray = buildFrame(0x20, 0x7F, byteArrayOf(if (mute) 1 else 0))

    /** Echter Lese-Request für Telemetrie (ESC-Info). CMD 0x01 = read ESC/BMS-Block. */
    fun readTelemetry(): ByteArray = buildFrame(0x20, 0x01, byteArrayOf(0x00))

    fun toHex(frame: ByteArray): String = frame.joinToString(" ") { "%02X".format(it) }
}

/* ---------------------------------------------------------------------------
 *  NAVEE / BRIGHTWAY FRAME-BUILDER  (AA 55 | CMD | LEN | PAYLOAD | SUM)
 * ------------------------------------------------------------------------- */
object NaveeFrames {
    const val SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val CHAR_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"
    const val CHAR_WRITE_UUID = "0000ffe2-0000-1000-8000-00805f9b34fb"

    private const val H1 = 0xAA
    private const val H2 = 0x55

    const val CMD_HANDSHAKE = 0x01
    const val CMD_SPEED = 0x10
    const val CMD_ZERO_START = 0x11
    const val CMD_REGION = 0x12
    const val CMD_MAX_POWER = 0x13
    const val CMD_OVERSPEED_ALARM_MUTE = 0x14
    const val CMD_GERMAN = 0x18
    const val CMD_LOCK = 0x1A
    const val CMD_KERS = 0x1B
    const val CMD_CRUISE = 0x1C
    const val CMD_TEMP_GUARD = 0x1D
    const val CMD_EMERGENCY = 0x1E
    const val CMD_POLICE = 0x1F
    const val CMD_READ_STATUS = 0x20

    fun buildFrame(cmd: Int, payload: ByteArray): ByteArray {
        val head = ByteArray(4 + payload.size)
        head[0] = H1.toByte()
        head[1] = H2.toByte()
        head[2] = cmd.toByte()
        head[3] = payload.size.toByte()
        System.arraycopy(payload, 0, head, 4, payload.size)
        var sum = 0
        for (b in head) sum += b.toInt() and 0xFF
        val out = head.copyOf(head.size + 1)
        out[out.size - 1] = (sum and 0xFF).toByte()
        return out
    }

    fun handshake(): ByteArray = buildFrame(CMD_HANDSHAKE, byteArrayOf(0x01))
    fun speedCeiling(kmh: Int): ByteArray = buildFrame(CMD_SPEED, byteArrayOf(kmh.toByte()))
    fun zeroStart(kmh: Int): ByteArray = buildFrame(CMD_ZERO_START, byteArrayOf(kmh.toByte()))
    fun regionToggle(regionCode: Int): ByteArray = buildFrame(CMD_REGION, byteArrayOf(regionCode.toByte()))
    fun maxPower(): ByteArray = buildFrame(CMD_MAX_POWER, byteArrayOf(0x01))
    fun overspeedAlarmMute(mute: Boolean): ByteArray =
        buildFrame(CMD_OVERSPEED_ALARM_MUTE, byteArrayOf(if (mute) 1 else 0))
    fun germanManeuver(): ByteArray = buildFrame(CMD_GERMAN, byteArrayOf(0x01))
    fun lock(locked: Boolean): ByteArray = buildFrame(CMD_LOCK, byteArrayOf(if (locked) 1 else 0))
    fun kers(level: Int): ByteArray = buildFrame(CMD_KERS, byteArrayOf(level.toByte()))
    fun cruise(enabled: Boolean): ByteArray = buildFrame(CMD_CRUISE, byteArrayOf(if (enabled) 1 else 0))
    fun motorTempGuard(celsius: Int): ByteArray = buildFrame(CMD_TEMP_GUARD, byteArrayOf(celsius.toByte()))
    fun emergencyCut(): ByteArray = buildFrame(CMD_EMERGENCY, byteArrayOf(0x00))
    fun policeMode(legalKmh: Int): ByteArray = buildFrame(CMD_POLICE, byteArrayOf(legalKmh.toByte()))

    /** Echter Status-/Telemetrie-Request. */
    fun readStatus(): ByteArray = buildFrame(CMD_READ_STATUS, byteArrayOf(0x00))

    /**
     * Übersetzt einen Ninebot-Frame in das Brightway-Äquivalent.
     * @param alt true -> alternative Kodierung (vertauschter Header 55 AA + XOR-Prüfsumme).
     *            Wird als Fallback-Kandidat genutzt, falls der Controller auf die
     *            Standard-Kodierung nicht mit dem gewohnten ACK antwortet.
     */
    fun fromNinebot(frame: ByteArray, alt: Boolean = false): ByteArray? {
        if (frame.size < 6) return null
        if (frame[0] != 0x5A.toByte() || frame[1] != 0xA5.toByte()) return null
        val cmd = frame[4].toInt() and 0xFF
        val payload = frame.copyOfRange(5, frame.size - 1)
        val value = if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else 0
        val b: (Int, ByteArray) -> ByteArray =
            if (alt) { c, p -> buildFrameAlt(c, p) } else { c, p -> buildFrame(c, p) }
        return when (cmd) {
            0x71 -> b(CMD_SPEED, byteArrayOf(value.toByte()))
            0x72 -> b(CMD_ZERO_START, byteArrayOf(value.toByte()))
            0x73 -> b(CMD_MAX_POWER, byteArrayOf(0x01))
            0x78 -> b(CMD_GERMAN, byteArrayOf(0x01))
            0x79 -> b(CMD_POLICE, byteArrayOf(value.toByte()))
            0x7A -> b(CMD_LOCK, byteArrayOf(if (value != 0) 1 else 0))
            0x7B -> b(CMD_KERS, byteArrayOf(value.toByte()))
            0x7C -> b(CMD_CRUISE, byteArrayOf(if (value != 0) 1 else 0))
            0x7D -> b(CMD_TEMP_GUARD, byteArrayOf(value.toByte()))
            0x7E -> b(CMD_EMERGENCY, byteArrayOf(0x00))
            0x7F -> b(CMD_OVERSPEED_ALARM_MUTE, byteArrayOf(if (value != 0) 1 else 0))
            0x01 -> b(CMD_READ_STATUS, byteArrayOf(0x00))
            else -> null
        }
    }

    /**
     * Alternative Brightway-Kodierung: vertauschter Header (55 AA) + XOR-Prüfsumme.
     * Dient ausschließlich als Fallback-Kandidat (manche GT3-Max-FW-Revisionen
     * quittieren nur eine der beiden Header-Varianten).
     */
    fun buildFrameAlt(cmd: Int, payload: ByteArray): ByteArray {
        val head = ByteArray(4 + payload.size)
        head[0] = H2.toByte()
        head[1] = H1.toByte()
        head[2] = cmd.toByte()
        head[3] = payload.size.toByte()
        System.arraycopy(payload, 0, head, 4, payload.size)
        var x = 0
        for (bb in head) x = x xor (bb.toInt() and 0xFF)
        val out = head.copyOf(head.size + 1)
        out[out.size - 1] = (x and 0xFF).toByte()
        return out
    }

    fun toHex(frame: ByteArray): String = frame.joinToString(" ") { "%02X".format(it) }
}

/* ---------------------------------------------------------------------------
 *  RAM-OVERLAY
 * ------------------------------------------------------------------------- */
class RamOverlay {
    private val patches = linkedMapOf<String, ByteArray>()
    var active = false
        private set

    /**
     * Merkt sich einen Patch fuer den spaeteren Restore.
     * [frame] ist nullable: Liefert der aktive Adapter fuer einen Befehl KEINEN
     * Frame (z. B. nicht unterstuetzt), wird auch KEIN Patch gespeichert \u2014 so
     * bleibt der Restore ehrlich (es gibt nichts wiederherzustellen).
     */
    fun apply(name: String, frame: ByteArray?) {
        if (frame == null) return
        patches[name] = frame; active = true
    }

    fun flush(): List<String> {
        val keys = patches.keys.toList()
        patches.clear()
        active = false
        return keys
    }

    val size: Int get() = patches.size
    val names: List<String> get() = patches.keys.toList()
}
