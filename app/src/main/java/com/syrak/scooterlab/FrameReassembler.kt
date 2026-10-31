package com.syrak.scooterlab

/* ============================================================================
 *  PHASE 16.2 — BLE-FRAME-REASSEMBLER
 *  ---------------------------------------------------------------------------
 *  BEFUND (Phase 16.2): Bisher wurde JEDE eingehende BLE-Notification direkt an
 *  den Decoder gegeben. Eine BLE-Notification ist aber durch MTU-3 begrenzt
 *  (Standard 20 Byte, bei MTU 247 maximal 244 Byte). Ein vollstaendiger
 *  Navee-Status-Frame mit 13 Zellspannungen ist deutlich laenger (~50 Byte).
 *  Wird er vom Controller in mehreren Notifications gesendet, sah der Decoder
 *  nur Fragmente — Zellwerte/Kapazitaet gingen verloren, und ein Fragment, das
 *  mitten im Frame beginnt, konnte falsch interpretiert werden.
 *
 *  Diese Klasse puffert eingehende Bytes und schneidet anhand des im Frame
 *  deklarierten Laengenfelds VOLLSTAENDIGE Frames heraus. Sie ist bewusst
 *  protokollgetrieben (Header + LEN), nicht ratend:
 *
 *    Ninebot  : 5A A5 | LEN | ...   -> total = LEN + 4
 *    Navee std: AA 55 | CMD | LEN | ... -> total = LEN + 5
 *    Navee alt: 55 AA | CMD | LEN | ... -> total = LEN + 5
 *
 *  WICHTIG — kein Datenverlust gegenueber dem Altverhalten:
 *  • Enthaelt eine Notification bereits einen vollstaendigen Frame, wird er
 *    sofort zurueckgegeben (identisch zum bisherigen Verhalten).
 *  • Beginnt ein Frame mitten in den Daten, wird vorangestellter Muell
 *    verworfen und neu synchronisiert.
 *  • Ist ein Frame noch unvollstaendig, wird er gepuffert (statt falsch
 *    dekodiert) und beim naechsten Chunk vervollstaendigt.
 *  • Der Puffer ist hart begrenzt; bei Ueberlauf wird verworfen (kein
 *    unbegrenztes Wachstum).
 *
 *  Reines Kotlin, deterministisch, vollstaendig unit-testbar. Wirft nie.
 * ==========================================================================*/

/** Ergebnis eines [FrameReassembler.feed]-Aufrufs. */
data class ReassemblyResult(
    /** Vollstaendige, anhand des Laengenfelds abgeschnittene Frames. */
    val frames: List<ByteArray>,
    /** Anzahl Bytes, die als nicht zuordenbarer Muell verworfen wurden. */
    val droppedBytes: Int,
    /** Aktuelle Puffergroesse nach der Verarbeitung. */
    val bufferedBytes: Int
) {
    val hasFrames: Boolean get() = frames.isNotEmpty()
}

class FrameReassembler(
    /** Maximale Puffergroesse. Ein einzelner Frame ist max. 260 Byte (LEN 255). */
    private val maxBuffer: Int = 1024
) {
    private var buffer = ByteArray(0)

    val bufferedBytes: Int get() = buffer.size

    fun reset() {
        buffer = ByteArray(0)
    }

    /**
     * Nimmt einen eingehenden BLE-Chunk entgegen und liefert alle darin (bzw.
     * durch vorherige Chunks) vollstaendig gewordenen Frames.
     */
    fun feed(chunk: ByteArray): ReassemblyResult {
        if (chunk.isEmpty()) {
            return ReassemblyResult(emptyList(), 0, buffer.size)
        }
        buffer = concat(buffer, chunk)
        val out = ArrayList<ByteArray>()
        var dropped = 0

        while (true) {
            if (buffer.isEmpty()) break
            val headerIndex = indexOfHeader(buffer)
            if (headerIndex < 0) {
                // Kein Header: alles bis auf ein moegliches Teil-Header-Byte verwerfen.
                val keep = if (isHeaderPrefix(buffer[buffer.size - 1])) 1 else 0
                dropped += buffer.size - keep
                buffer = if (keep == 1) buffer.copyOfRange(buffer.size - 1, buffer.size) else ByteArray(0)
                break
            }
            if (headerIndex > 0) {
                dropped += headerIndex
                buffer = buffer.copyOfRange(headerIndex, buffer.size)
                // weiter pruefen (Header steht jetzt an Index 0)
            }
            val expected = expectedTotal(buffer)
            if (expected == null || expected < MIN_FRAME || expected > maxBuffer) {
                // Laengenfeld unplausibel -> ein Byte verwerfen und neu synchronisieren.
                dropped += 1
                buffer = buffer.copyOfRange(1, buffer.size)
                continue
            }
            if (buffer.size < expected) {
                // Noch unvollstaendig: auf naechsten Chunk warten.
                break
            }
            out.add(buffer.copyOfRange(0, expected))
            buffer = buffer.copyOfRange(expected, buffer.size)
        }

        // Harte Obergrenze: nie unbegrenzt puffern.
        if (buffer.size > maxBuffer) {
            dropped += buffer.size
            buffer = ByteArray(0)
        }
        return ReassemblyResult(out, dropped, buffer.size)
    }

    /* ------------------------------ Helfer ------------------------------ */

    /** Sucht den fruehesten bekannten Header und liefert seinen Index (-1 = keiner). */
    private fun indexOfHeader(b: ByteArray): Int {
        var i = 0
        while (i + 1 < b.size) {
            val x = b[i].toInt() and 0xFF
            val y = b[i + 1].toInt() and 0xFF
            if ((x == 0xAA && y == 0x55) ||
                (x == 0x55 && y == 0xAA) ||
                (x == 0x5A && y == 0xA5)
            ) {
                return i
            }
            i++
        }
        return -1
    }

    /** Kann dieses Byte der erste Teil eines Headers sein? */
    private fun isHeaderPrefix(b: Byte): Boolean {
        val v = b.toInt() and 0xFF
        return v == 0xAA || v == 0x55 || v == 0x5A
    }

    /** Erwartete Gesamtlaenge anhand des LEN-Felds, oder null. */
    private fun expectedTotal(b: ByteArray): Int? {
        if (b.size < 2) return null
        val b0 = b[0].toInt() and 0xFF
        val b1 = b[1].toInt() and 0xFF
        return when {
            b0 == 0x5A && b1 == 0xA5 -> {
                if (b.size < 3) null else (b[2].toInt() and 0xFF) + 4
            }
            b0 == 0xAA && b1 == 0x55 -> {
                if (b.size < 4) null else (b[3].toInt() and 0xFF) + 5
            }
            b0 == 0x55 && b1 == 0xAA -> {
                if (b.size < 4) null else (b[3].toInt() and 0xFF) + 5
            }
            else -> null
        }
    }

    private fun concat(a: ByteArray, b: ByteArray): ByteArray {
        if (a.isEmpty()) return b.copyOf()
        val out = ByteArray(a.size + b.size)
        a.copyInto(out, 0)
        b.copyInto(out, a.size)
        return out
    }

    companion object {
        /** Kleinster strukturell sinnvoller Frame (Header + LEN + min. 1 Byte). */
        const val MIN_FRAME = 4
    }
}
