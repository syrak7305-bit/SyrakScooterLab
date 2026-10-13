package com.syrak.scooterlab

/* ---------------------------------------------------------------------------
 *  TELEMETRIE-DECODER
 *  ---------------------------------------------------------------------------
 *  !! VERIFIKATIONSPFLICHT !!
 *  Die folgenden Offsets sind die einzigen Werte in dieser Datei, die NICHT
 *  aus öffentlicher Spezifikation stammen (es gibt keine). Sie sind bewusst
 *  als benannte Konstanten ausgelagert, damit sie nach einem echten Mitschnitt
 *  (HCI-Snoop oder nRF Connect Logger) in EINER Zeile korrigiert werden können.
 *  Der Decoder selbst (Header-Check, Längen-Check, Vorzeichen, Skalierung) ist
 *  voll funktional und real.
 * ------------------------------------------------------------------------- */
object TelemetryDecoder {

    // ---- Ninebot/Xiaomi ESC-Info-Payload ----------------------------------
    // Antwort: 5A A5 LEN ADDR CMD <payload> SUM  -> payload beginnt bei Index 5
    private const val NB_PAYLOAD_START = 5
    // Offsets RELATIV zum Payload-Start (VERIFY gegen Mitschnitt):
    private const val NB_OFF_SPEED = 4      // uint16 LE, 0.1 km/h
    private const val NB_OFF_VOLTAGE = 6    // uint16 LE, 0.01 V  (Wert/100)
    private const val NB_OFF_CURRENT = 8    // int16  LE, 0.01 A  (signed)
    private const val NB_OFF_BATTERY = 10   // uint8, %
    private const val NB_OFF_TEMP = 11      // int8, °C
    private const val NB_OFF_CELLS = 12     // uint16 LE je Zelle, 0.001 V (VERIFY)
    private const val NB_OFF_DESIGN_CAP = 40// uint16 LE, mAh (VERIFY)
    private const val NB_OFF_FULL_CAP = 42  // uint16 LE, mAh (VERIFY)
    private const val NB_OFF_CYCLES = 44    // uint16 LE (VERIFY)
    private const val NB_CELL_COUNT = 10
    private const val NB_MIN_LEN = NB_PAYLOAD_START + 12

    /**
     * Dekodiert einen Ninebot/Xiaomi-Notify-Frame ABSOLUT absturzsicher.
     * Jedes Feld wird einzeln bounds-geprüft; fehlt ein Feld oder ist es
     * unplausibel, bleibt es null (Tacho zeigt dann '--' statt eines Falschwerts).
     */
    fun decodeNinebot(frame: ByteArray): Telemetry? {
        if (!hasRange(frame, 0, 2)) return null
        if (frame.size < NB_MIN_LEN) return null
        if (frame[0] != 0x5A.toByte() || frame[1] != 0xA5.toByte()) return null
        val p = NB_PAYLOAD_START
        return try {
            val cells = decodeCellArray(frame, p + NB_OFF_CELLS, NB_CELL_COUNT)
            Telemetry(
                speedKmh = u16leOrNull(frame, p + NB_OFF_SPEED)
                    ?.takeIf { it != 0xFFFF }?.div(10f),
                voltageV = u16leOrNull(frame, p + NB_OFF_VOLTAGE)
                    ?.takeIf { it != 0 && it != 0xFFFF }?.div(100f),
                currentA = i16leOrNull(frame, p + NB_OFF_CURRENT)
                    ?.takeIf { it != -32768 }?.div(100f),
                batteryPercent = u8OrNull(frame, p + NB_OFF_BATTERY)?.takeIf { it in 0..100 },
                motorTempC = i8OrNull(frame, p + NB_OFF_TEMP)?.takeIf { it in -40..125 }?.toFloat(),
                cellMinV = cells?.minOrNull(),
                cellMaxV = cells?.maxOrNull(),
                cellVoltages = cells,
                designCapacityMah = u16leOrNull(frame, p + NB_OFF_DESIGN_CAP)?.takeIf { it > 0 },
                fullChargeCapacityMah = u16leOrNull(frame, p + NB_OFF_FULL_CAP)?.takeIf { it > 0 },
                cycleCount = u16leOrNull(frame, p + NB_OFF_CYCLES)?.takeIf { it != 0xFFFF },
                rawHex = ScooterFrames.toHex(frame),
                source = BleProtocol.NINEBOT
            )
        } catch (_: Exception) { null }
    }

    // ---- Navee/Brightway Status-Payload -----------------------------------
    // Antwort: AA 55 CMD LEN <payload> SUM -> payload beginnt bei Index 4
    private const val NV_PAYLOAD_START = 4
    private const val NV_OFF_SPEED = 0      // uint16 LE, 0.1 km/h
    private const val NV_OFF_VOLTAGE = 2    // uint16 LE, 0.01 V
    private const val NV_OFF_CURRENT = 4    // int16  LE, 0.01 A
    private const val NV_OFF_BATTERY = 6    // uint8, %
    private const val NV_OFF_TEMP = 7       // int8, °C
    private const val NV_OFF_CELLS = 8      // uint16 LE je Zelle, 0.001 V (VERIFY)
    private const val NV_OFF_DESIGN_CAP = 36// uint16 LE, mAh (VERIFY)
    private const val NV_OFF_FULL_CAP = 38  // uint16 LE, mAh (VERIFY)
    private const val NV_OFF_CYCLES = 40    // uint16 LE (VERIFY)
    private const val NV_CELL_COUNT = 13
    private const val NV_MIN_LEN = NV_PAYLOAD_START + 8

    /**
     * Dekodiert einen Navee/Brightway-Notify-Frame ABSOLUT absturzsicher.
     * Kernwerte (Spannung, Geschwindigkeit, Akku-%) werden dekodiert, sobald sie
     * im Frame enthalten sind; nicht empfangene Felder bleiben null.
     */
    fun decodeNavee(frame: ByteArray): Telemetry? {
        if (!hasRange(frame, 0, 2)) return null
        if (frame.size < NV_MIN_LEN) return null
        if (frame[0] != 0xAA.toByte() || frame[1] != 0x55.toByte()) return null
        val p = NV_PAYLOAD_START
        return try {
            val cells = decodeCellArray(frame, p + NV_OFF_CELLS, NV_CELL_COUNT)
            Telemetry(
                speedKmh = u16leOrNull(frame, p + NV_OFF_SPEED)
                    ?.takeIf { it != 0xFFFF }?.div(10f),
                voltageV = u16leOrNull(frame, p + NV_OFF_VOLTAGE)
                    ?.takeIf { it != 0 && it != 0xFFFF }?.div(100f),
                currentA = i16leOrNull(frame, p + NV_OFF_CURRENT)
                    ?.takeIf { it != -32768 }?.div(100f),
                batteryPercent = u8OrNull(frame, p + NV_OFF_BATTERY)?.takeIf { it in 0..100 },
                motorTempC = i8OrNull(frame, p + NV_OFF_TEMP)?.takeIf { it in -40..125 }?.toFloat(),
                cellMinV = cells?.minOrNull(),
                cellMaxV = cells?.maxOrNull(),
                cellVoltages = cells,
                designCapacityMah = u16leOrNull(frame, p + NV_OFF_DESIGN_CAP)?.takeIf { it > 0 },
                fullChargeCapacityMah = u16leOrNull(frame, p + NV_OFF_FULL_CAP)?.takeIf { it > 0 },
                cycleCount = u16leOrNull(frame, p + NV_OFF_CYCLES)?.takeIf { it != 0xFFFF },
                rawHex = NaveeFrames.toHex(frame),
                source = BleProtocol.NAVEE_BRIGHTWAY
            )
        } catch (_: Exception) { null }
    }

    /**
     * Dekodiert ein Zellgruppen-Array (uint16 LE je Zelle, Aufloesung 0.001 V).
     * Plausibilitaetsfilter: liegt eine Zelle ausserhalb 1.5..4.5 V, wird das
     * gesamte Array verworfen (verhindert Muell-Grafiken bei falschem Offset).
     */
    private fun decodeCellArray(frame: ByteArray, start: Int, count: Int): List<Float>? {
        if (count <= 0) return null
        if (!hasRange(frame, start, count * 2)) return null
        val cells = ArrayList<Float>(count)
        for (i in 0 until count) {
            val v = u16leOrNull(frame, start + i * 2)?.div(1000f) ?: return null
            if (v < 1.5f || v > 4.5f) return null
            cells.add(v)
        }
        return cells
    }

    // ---- zentrale, absolute Bereichspruefung ------------------------------
    /**
     * Liefert true NUR wenn [offset, offset+length) vollstaendig innerhalb von
     * [data] liegt (und length > 0). Wird vor JEDEM Byte-Zugriff im Decoder
     * aufgerufen, damit ein zu kurzer Frame NIEMALS einen IndexOutOfBounds
     * ausloesen kann (z. B. Feld bei Offset 40 in einem 17-Byte-Frame).
     */
    private fun hasRange(data: ByteArray, offset: Int, length: Int): Boolean =
        offset >= 0 && length > 0 && offset + length <= data.size

    // ---- absturzsichere Zugriffe (liefern null ausserhalb des Arrays) ------
    private fun u8OrNull(b: ByteArray, i: Int): Int? =
        if (hasRange(b, i, 1)) (b[i].toInt() and 0xFF) else null

    private fun i8OrNull(b: ByteArray, i: Int): Int? =
        if (hasRange(b, i, 1)) b[i].toInt() else null

    private fun u16leOrNull(b: ByteArray, i: Int): Int? =
        if (hasRange(b, i, 2))
            (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        else null

    private fun i16leOrNull(b: ByteArray, i: Int): Int? {
        val v = u16leOrNull(b, i) ?: return null
        return if (v >= 0x8000) v - 0x10000 else v
    }
}
