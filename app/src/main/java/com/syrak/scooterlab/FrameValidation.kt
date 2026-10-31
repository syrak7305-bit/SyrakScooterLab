package com.syrak.scooterlab

/* ============================================================================
 *  PHASE 16.2 — FRAME-VALIDIERUNG
 *  ---------------------------------------------------------------------------
 *  Dieses Objekt beantwortet fuer einen empfangenen Roh-Frame drei getrennte
 *  Fragen, die bisher NICHT getrennt geprueft wurden:
 *
 *    1. Header erkannt?            (belegt durch Frame-Builder + Protokoll-Layout)
 *    2. Laengenfeld konsistent?    (LEN-Feld gegen tatsaechliche Bytezahl)
 *    3. Pruefsumme stimmt?         (projektdefinierte Formel — UNVERIFIED)
 *
 *  EHRLICHKEITSREGEL (Phase 16.2):
 *  Es gibt KEINEN echten NAVEE-GT3-Max-Mitschnitt. Deshalb gilt:
 *    • Header + Laengenfeld sind aus dem EIGENEN Frame-Builder ableitbar und
 *      damit projektdefiniert belegbar (Round-Trip in Tests).
 *    • Die Pruefsummenformel ist die im Projekt verwendete, aber sie ist
 *      NICHT gegen echte Hardware bestaetigt. `checksumVerified` ist daher
 *      konstant false und wird als UNVERIFIED ausgewiesen.
 *    • Die Pruefsumme wird berechnet und GEMELDET, aber NICHT zum harten
 *      Verwerfen benutzt — sonst koennte eine falsche Formel echte Telemetrie
 *      komplett blockieren. Das ist bewusst konservativ.
 *
 *  Der Validator ist reines Kotlin (keine Android-Abhaengigkeiten) und darf
 *  bei beliebiger Eingabe NIEMALS werfen.
 * ==========================================================================*/

/** Erkannte Frame-Familie anhand des Headers. */
enum class FrameFamily(val label: String) {
    NINEBOT("Ninebot/Xiaomi (5A A5)"),
    NAVEE_STD("Navee/Brightway (AA 55)"),
    NAVEE_ALT("Navee/Brightway alt (55 AA)"),
    UNKNOWN("Unbekannt")
}

/**
 * Ergebnis der strukturellen Frame-Pruefung.
 *
 * @param declaredTotalBytes aus dem LEN-Feld abgeleitete Gesamtlaenge (oder null,
 *   wenn der Frame zu kurz ist, um das LEN-Feld zu lesen).
 * @param lengthOk true, wenn `actualBytes == declaredTotalBytes`.
 * @param checksumComputed berechnete Pruefsumme (null, wenn nicht berechenbar).
 * @param checksumMatches true/false, wenn berechenbar; null sonst.
 * @param checksumVerified IMMER false in Phase 16.2 (kein Hardware-Mitschnitt).
 */
data class FrameValidation(
    val family: FrameFamily,
    val headerOk: Boolean,
    val declaredTotalBytes: Int?,
    val actualBytes: Int,
    val lengthOk: Boolean,
    val checksumComputed: Int?,
    val checksumMatches: Boolean?,
    val checksumVerified: Boolean = false,
    val notes: List<String> = emptyList()
) {
    /** Struktur ist nur dann gueltig, wenn Header UND Laenge stimmen. */
    val structurallyValid: Boolean get() = headerOk && lengthOk

    /** Kurze, logbare Zusammenfassung. */
    fun summary(): String = buildString {
        append(family.label)
        append(" · header=").append(if (headerOk) "ok" else "FEHLT")
        append(" · len=").append(actualBytes).append('/')
        append(declaredTotalBytes?.toString() ?: "?")
        append(if (lengthOk) " ok" else " UNGUELTIG")
        append(" · summe=")
        append(
            when (checksumMatches) {
                true -> "ok"
                false -> "ABWEICHUNG"
                null -> "n/a"
            }
        )
        if (!checksumVerified) append(" (UNVERIFIED)")
    }
}

object FrameValidator {

    /**
     * Prueft einen Roh-Frame. Wirft niemals.
     *
     * Laengen-Konventionen (aus den Frame-Buildern abgeleitet):
     *   Ninebot  : 5A A5 | LEN | ADDR | CMD | PAYLOAD | SUM   -> total = LEN + 4
     *              (Builder setzt LEN = payload + 2)
     *   Navee std: AA 55 | CMD | LEN | PAYLOAD | SUM          -> total = LEN + 5
     *   Navee alt: 55 AA | CMD | LEN | PAYLOAD | SUM(XOR)     -> total = LEN + 5
     */
    fun validate(frame: ByteArray): FrameValidation {
        if (frame.size < 2) {
            return FrameValidation(
                family = FrameFamily.UNKNOWN,
                headerOk = false,
                declaredTotalBytes = null,
                actualBytes = frame.size,
                lengthOk = false,
                checksumComputed = null,
                checksumMatches = null,
                notes = listOf("Frame kuerzer als 2 Byte — kein Header moeglich.")
            )
        }
        val b0 = frame[0].toInt() and 0xFF
        val b1 = frame[1].toInt() and 0xFF
        return when {
            b0 == 0x5A && b1 == 0xA5 -> validateNinebot(frame)
            b0 == 0xAA && b1 == 0x55 -> validateNavee(frame, FrameFamily.NAVEE_STD, alt = false)
            b0 == 0x55 && b1 == 0xAA -> validateNavee(frame, FrameFamily.NAVEE_ALT, alt = true)
            else -> FrameValidation(
                family = FrameFamily.UNKNOWN,
                headerOk = false,
                declaredTotalBytes = null,
                actualBytes = frame.size,
                lengthOk = false,
                checksumComputed = null,
                checksumMatches = null,
                notes = listOf("Unbekannter Header ${hexByte(b0)} ${hexByte(b1)}.")
            )
        }
    }

    private fun validateNinebot(frame: ByteArray): FrameValidation {
        if (frame.size < 3) {
            return FrameValidation(
                FrameFamily.NINEBOT, headerOk = true, declaredTotalBytes = null,
                actualBytes = frame.size, lengthOk = false,
                checksumComputed = null, checksumMatches = null,
                notes = listOf("Ninebot-Frame zu kurz fuer LEN-Feld.")
            )
        }
        val len = frame[2].toInt() and 0xFF
        val declared = len + 4
        val lengthOk = declared == frame.size
        val sum = if (frame.size >= 3) {
            var s = 0
            for (i in 2 until frame.size - 1) s += frame[i].toInt() and 0xFF
            (0x100 - (s and 0xFF)) and 0xFF
        } else null
        val actualSum = if (frame.size >= 1) frame[frame.size - 1].toInt() and 0xFF else null
        return FrameValidation(
            family = FrameFamily.NINEBOT,
            headerOk = true,
            declaredTotalBytes = declared,
            actualBytes = frame.size,
            lengthOk = lengthOk,
            checksumComputed = sum,
            checksumMatches = if (sum != null && actualSum != null) sum == actualSum else null,
            checksumVerified = false,
            notes = buildList {
                if (!lengthOk) add("LEN=$len erwartet $declared Byte, empfangen ${frame.size}.")
                add("Pruefsumme projektdefiniert (Zweierkomplement ab Index 2) — UNVERIFIED.")
            }
        )
    }

    private fun validateNavee(
        frame: ByteArray,
        family: FrameFamily,
        alt: Boolean
    ): FrameValidation {
        if (frame.size < 4) {
            return FrameValidation(
                family, headerOk = true, declaredTotalBytes = null,
                actualBytes = frame.size, lengthOk = false,
                checksumComputed = null, checksumMatches = null,
                notes = listOf("Navee-Frame zu kurz fuer LEN-Feld.")
            )
        }
        val len = frame[3].toInt() and 0xFF
        val declared = len + 5
        val lengthOk = declared == frame.size
        val computed: Int = if (alt) {
            var x = 0
            for (i in 0 until frame.size - 1) x = x xor (frame[i].toInt() and 0xFF)
            x and 0xFF
        } else {
            var s = 0
            for (i in 0 until frame.size - 1) s += frame[i].toInt() and 0xFF
            s and 0xFF
        }
        val actualSum = frame[frame.size - 1].toInt() and 0xFF
        return FrameValidation(
            family = family,
            headerOk = true,
            declaredTotalBytes = declared,
            actualBytes = frame.size,
            lengthOk = lengthOk,
            checksumComputed = computed,
            checksumMatches = computed == actualSum,
            checksumVerified = false,
            notes = buildList {
                if (!lengthOk) add("LEN=$len erwartet $declared Byte, empfangen ${frame.size}.")
                add(
                    if (alt) "Pruefsumme projektdefiniert (XOR) — UNVERIFIED."
                    else "Pruefsumme projektdefiniert (Summe) — UNVERIFIED."
                )
            }
        )
    }

    private fun hexByte(v: Int): String = "%02X".format(v and 0xFF)
}
