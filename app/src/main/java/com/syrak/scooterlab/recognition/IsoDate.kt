package com.syrak.scooterlab.recognition

import java.time.DateTimeException
import java.time.LocalDate

/* ============================================================================
 *  PHASE 17.4 — ECHTE KALENDERPRUEFUNG FUER TESTDATEN
 *  ---------------------------------------------------------------------------
 *  Die bisherige Pruefung in [HardwareTestRecord.hasValidTestDate] akzeptierte
 *  jeden Tag 1..31 und jeden Monat 1..12. Damit waren z. B. 2026-02-31 oder
 *  2026-04-31 "gueltig". Fuer einen nachvollziehbaren Hardware-Testbeleg ist
 *  das nicht ausreichend.
 *
 *  Diese Datei ist reines Kotlin (java.time ist ab API 26 verfuegbar und wird
 *  ausserdem in lokalen Unit-Tests direkt genutzt).
 * ==========================================================================*/
object IsoDate {

    private val PATTERN = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")

    /**
     * true nur bei exaktem Format `yyyy-MM-dd` UND real existierendem
     * Kalendertag (inkl. Schaltjahren).
     */
    fun isValid(value: String?): Boolean {
        val text = value?.trim() ?: return false
        val match = PATTERN.matchEntire(text) ?: return false
        val year = match.groupValues[1].toIntOrNull() ?: return false
        val month = match.groupValues[2].toIntOrNull() ?: return false
        val day = match.groupValues[3].toIntOrNull() ?: return false
        return try {
            LocalDate.of(year, month, day)
            true
        } catch (_: DateTimeException) {
            false
        }
    }

    /** Kanonische Form oder null bei ungueltigem Datum. */
    fun normalize(value: String?): String? {
        if (!isValid(value)) return null
        return value!!.trim()
    }

    /** Vergleich zweier ISO-Daten; ungueltige Werte sortieren nach hinten. */
    fun compare(a: String?, b: String?): Int {
        val va = normalize(a)
        val vb = normalize(b)
        return when {
            va == null && vb == null -> 0
            va == null -> 1
            vb == null -> -1
            else -> va.compareTo(vb)
        }
    }
}
