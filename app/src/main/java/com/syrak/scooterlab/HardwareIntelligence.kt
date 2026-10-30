package com.syrak.scooterlab

import kotlin.math.abs
import kotlin.math.roundToInt

/* ============================================================================
 *  PHASE 14 — HARDWARE INTELLIGENCE (reine Logik, unit-testbar)
 *  ----------------------------------------------------------------------------
 *  GRUNDREGEL: Keine erfundenen Kompatibilitaeten.
 *
 *  Die sechs Stufen sind bewusst KUMULATIV und streng geordnet. Eine spaetere
 *  Stufe darf NUR erreicht sein, wenn alle vorherigen belegt sind:
 *
 *    1 Detected                  Geraet per BLE-Scan gesehen
 *    2 GATT Discovered           Services/Characteristics gelesen
 *    3 Read-only Tested          Lesezugriff tatsaechlich erfolgreich
 *    4 Telemetry Validated       plausible Telemetrie empfangen
 *    5 Write Protocol Validated  Schreibprotokoll nachgewiesen (nicht nur Code)
 *    6 Hardware Verified         am realen Fahrzeug bestaetigt
 *
 *  "Geraet erkannt" ist also NICHT "Protokoll unterstuetzt", und eine
 *  Bluetooth-Verbindung ist KEIN Beweis fuer funktionierende Telemetrie.
 * ==========================================================================*/

/** Kumulative Kompatibilitaetsstufen. */
enum class CompatibilityStage(val rank: Int) {
    DETECTED(1),
    GATT_DISCOVERED(2),
    /** PHASE 16: Es EXISTIERT eine lesbare Characteristic (nur Bestandsaufnahme). */
    READ_PROPERTY_FOUND(3),
    /** PHASE 16: Ein ECHTER Read ist mit Callback erfolgreich zurueckgekommen. */
    READ_ONLY_TESTED(4),
    TELEMETRY_VALIDATED(5),
    WRITE_PROTOCOL_VALIDATED(6),
    HARDWARE_VERIFIED(7);

    fun label(language: AppLanguage): String = when (this) {
        DETECTED -> if (language == AppLanguage.DE) "Erkannt" else "Detected"
        GATT_DISCOVERED -> if (language == AppLanguage.DE) "GATT gelesen" else "GATT discovered"
        READ_PROPERTY_FOUND -> if (language == AppLanguage.DE) "Read-Property gefunden" else "Read property found"
        READ_ONLY_TESTED -> if (language == AppLanguage.DE) "Read erfolgreich" else "Read succeeded"
        TELEMETRY_VALIDATED -> if (language == AppLanguage.DE) "Telemetrie bestaetigt" else "Telemetry validated"
        WRITE_PROTOCOL_VALIDATED -> if (language == AppLanguage.DE) "Schreibprotokoll bestaetigt" else "Write protocol validated"
        HARDWARE_VERIFIED -> if (language == AppLanguage.DE) "Hardware verifiziert" else "Hardware verified"
    }

    companion object {
        fun ordered(): List<CompatibilityStage> = entries.sortedBy { it.rank }
    }
}

/** Rohbelege, die von aussen (BLE-Schicht) geliefert werden. */
data class CompatibilityEvidence(
    val detected: Boolean = false,
    val gattDiscovered: Boolean = false,
    /**
     * PHASE 16: Es gibt mindestens eine lesbare Characteristic im GATT-Baum.
     * Das ist NICHT dasselbe wie ein erfolgreicher Read.
     */
    val readPropertyFound: Boolean = false,
    /** PHASE 16: Ein echter Read kam mit GATT_SUCCESS und Bytes zurueck. */
    val readOnlyTested: Boolean = false,
    val telemetryValidated: Boolean = false,
    val writeProtocolValidated: Boolean = false,
    val hardwareVerified: Boolean = false
)

data class CompatibilityStageEntry(
    val stage: CompatibilityStage,
    val reached: Boolean,
    /** Kurze Begruendung, warum die Stufe (nicht) erreicht ist. */
    val evidence: String
)

data class CompatibilityReport(
    val stages: List<CompatibilityStageEntry>,
    val highest: CompatibilityStage?,
    /** Stufen, die NICHT belegt sind — werden in der UI nie als erreicht gezeigt. */
    val missing: List<CompatibilityStage>
)

object CompatibilityLadder {

    /**
     * Baut die Leiter. Eine Stufe gilt nur dann als erreicht, wenn sie selbst
     * UND alle Vorgaenger belegt sind (Kumulativitaet). Widerspruechliche
     * Eingaben (z. B. "hardwareVerified" ohne "detected") werden dadurch
     * automatisch entschaerft.
     */
    fun evaluate(evidence: CompatibilityEvidence, language: AppLanguage = AppLanguage.DE): CompatibilityReport {
        val flags = mapOf(
            CompatibilityStage.DETECTED to evidence.detected,
            CompatibilityStage.GATT_DISCOVERED to evidence.gattDiscovered,
            CompatibilityStage.READ_PROPERTY_FOUND to evidence.readPropertyFound,
            CompatibilityStage.READ_ONLY_TESTED to evidence.readOnlyTested,
            CompatibilityStage.TELEMETRY_VALIDATED to evidence.telemetryValidated,
            CompatibilityStage.WRITE_PROTOCOL_VALIDATED to evidence.writeProtocolValidated,
            CompatibilityStage.HARDWARE_VERIFIED to evidence.hardwareVerified
        )

        var stillReached = true
        val entries = CompatibilityStage.ordered().map { stage ->
            val own = flags[stage] == true
            val reached = stillReached && own
            if (!reached) stillReached = false
            CompatibilityStageEntry(
                stage = stage,
                reached = reached,
                evidence = when {
                    reached -> if (language == AppLanguage.DE) "belegt" else "verified"
                    own -> if (language == AppLanguage.DE)
                        "Vorgaengerstufe fehlt" else "previous stage missing"
                    else -> if (language == AppLanguage.DE)
                        "nicht getestet" else "not tested"
                }
            )
        }
        val highest = entries.lastOrNull { it.reached }?.stage
        val missing = entries.filter { !it.reached }.map { it.stage }
        return CompatibilityReport(entries, highest, missing)
    }

    /** Nur die belegten Stufen (fuer kompakte Anzeige). */
    fun reachedStages(evidence: CompatibilityEvidence): List<CompatibilityStage> =
        evaluate(evidence).stages.filter { it.reached }.map { it.stage }
}

/* ---------------------------------------------------------------------------
 *  GATT-ANALYSE
 * -------------------------------------------------------------------------*/

/** Eigenschaften einer Characteristic, wie sie das Geraet MELDET. */
data class CharacteristicFacts(
    val uuid: String,
    val readable: Boolean = false,
    val writable: Boolean = false,
    val notifying: Boolean = false,
    val indicating: Boolean = false
) {
    /** Kompakte Flag-Schreibweise: R/W/N/I. */
    fun flags(): String = buildString {
        if (readable) append('R')
        if (writable) append('W')
        if (notifying) append('N')
        if (indicating) append('I')
    }.ifEmpty { "-" }
}

data class ServiceFacts(
    val uuid: String,
    val label: String = "",
    val characteristics: List<CharacteristicFacts> = emptyList()
)

enum class GattCompleteness(val label: String) {
    EMPTY("Kein GATT-Baum"),
    PARTIAL("Unvollstaendig"),
    COMPLETE("Vollstaendig gelesen")
}

data class GattAnalysis(
    val services: Int,
    val characteristics: Int,
    val readable: Int,
    val writable: Int,
    val notifying: Int,
    val indicating: Int,
    val completeness: GattCompleteness,
    val notes: List<String>
) {
    val hasNotifyPath: Boolean get() = notifying > 0 || indicating > 0
}

object GattAnalyzer {

    /**
     * Wertet einen GATT-Baum aus. Es wird nichts ergaenzt: fehlt ein Baum,
     * ist das Ergebnis EMPTY und wird auch so angezeigt.
     */
    fun analyze(services: List<ServiceFacts>): GattAnalysis {
        if (services.isEmpty()) {
            return GattAnalysis(
                services = 0, characteristics = 0, readable = 0, writable = 0,
                notifying = 0, indicating = 0,
                completeness = GattCompleteness.EMPTY,
                notes = listOf("Noch keine Service-Discovery erfolgt.")
            )
        }
        val chars = services.flatMap { it.characteristics }
        val notes = mutableListOf<String>()
        if (chars.isEmpty()) notes.add("Services vorhanden, aber keine Characteristics gelesen.")
        if (chars.none { it.readable }) notes.add("Keine lesbare Characteristic gemeldet.")
        if (chars.none { it.notifying || it.indicating }) {
            notes.add("Kein Notify/Indicate gemeldet \u2014 Telemetrie waere nicht empfangbar.")
        }
        val completeness = when {
            chars.isEmpty() -> GattCompleteness.PARTIAL
            services.any { it.characteristics.isEmpty() } -> GattCompleteness.PARTIAL
            else -> GattCompleteness.COMPLETE
        }
        return GattAnalysis(
            services = services.size,
            characteristics = chars.size,
            readable = chars.count { it.readable },
            writable = chars.count { it.writable },
            notifying = chars.count { it.notifying },
            indicating = chars.count { it.indicating },
            completeness = completeness,
            notes = notes
        )
    }
}

/* ---------------------------------------------------------------------------
 *  VERBINDUNGSQUALITAET
 * -------------------------------------------------------------------------*/

/** Eine RSSI-Messung mit monotonem Zeitstempel. */
data class LinkSample(val rssiDbm: Int, val atElapsedMs: Long)

enum class LinkTrend(val label: String) {
    IMPROVING("verbessert sich"),
    STABLE("stabil"),
    DEGRADING("verschlechtert sich"),
    UNKNOWN("keine Tendenz")
}

data class LinkQualitySummary(
    val samples: Int,
    val minDbm: Int?,
    val maxDbm: Int?,
    val avgDbm: Int?,
    val trend: LinkTrend,
    val stale: Boolean,
    val label: String
) {
    val available: Boolean get() = samples > 0
}

object LinkQuality {

    /** Ab dieser Abweichung gilt eine Tendenz als erkennbar (dBm). */
    const val TREND_THRESHOLD_DBM = 6

    fun summarize(
        samples: List<LinkSample>,
        nowElapsedMs: Long,
        staleAfterMs: Long = 15_000L
    ): LinkQualitySummary {
        if (samples.isEmpty()) {
            return LinkQualitySummary(0, null, null, null, LinkTrend.UNKNOWN, true, "Nicht verfuegbar")
        }
        val values = samples.map { it.rssiDbm }
        val avg = values.average().roundToInt()
        val last = samples.maxByOrNull { it.atElapsedMs }
        val stale = last == null || (nowElapsedMs - last.atElapsedMs) > staleAfterMs
        val trend = trendOf(samples)
        val label = when {
            stale -> "Veraltet"
            avg >= -60 -> "Sehr gut"
            avg >= -70 -> "Gut"
            avg >= -80 -> "Mittel"
            avg >= -90 -> "Schwach"
            else -> "Sehr schwach"
        }
        return LinkQualitySummary(
            samples = samples.size,
            minDbm = values.minOrNull(),
            maxDbm = values.maxOrNull(),
            avgDbm = avg,
            trend = trend,
            stale = stale,
            label = label
        )
    }

    /**
     * Vergleicht die aeltere Haelfte mit der neueren. Nur eine deutliche
     * Differenz gilt als Tendenz — Rauschen wird nicht als Trend verkauft.
     */
    fun trendOf(samples: List<LinkSample>): LinkTrend {
        if (samples.size < 4) return LinkTrend.UNKNOWN
        val sorted = samples.sortedBy { it.atElapsedMs }
        val half = sorted.size / 2
        val oldAvg = sorted.take(half).map { it.rssiDbm }.average()
        val newAvg = sorted.takeLast(half).map { it.rssiDbm }.average()
        val delta = newAvg - oldAvg
        return when {
            delta >= TREND_THRESHOLD_DBM -> LinkTrend.IMPROVING
            delta <= -TREND_THRESHOLD_DBM -> LinkTrend.DEGRADING
            else -> LinkTrend.STABLE
        }
    }
}

/* ---------------------------------------------------------------------------
 *  DATENFRISCHE
 * -------------------------------------------------------------------------*/

enum class FreshnessState {
    NEVER, FRESH, AGING, STALE;

    fun label(language: AppLanguage): String = when (this) {
        NEVER -> if (language == AppLanguage.DE) "Nicht verfuegbar" else "Not available"
        FRESH -> if (language == AppLanguage.DE) "Aktuell" else "Current"
        AGING -> if (language == AppLanguage.DE) "Wird alt" else "Aging"
        STALE -> if (language == AppLanguage.DE) "Veraltet" else "Stale"
    }

    val isUsable: Boolean get() = this == FRESH || this == AGING
}

object Freshness {

    const val DEFAULT_STALE_MS = 3_000L

    fun state(
        lastAtElapsedMs: Long?,
        nowElapsedMs: Long,
        staleAfterMs: Long = DEFAULT_STALE_MS
    ): FreshnessState {
        if (lastAtElapsedMs == null || lastAtElapsedMs <= 0L) return FreshnessState.NEVER
        val age = nowElapsedMs - lastAtElapsedMs
        if (age < 0L) return FreshnessState.FRESH
        return when {
            age > staleAfterMs * 2 -> FreshnessState.STALE
            age > staleAfterMs -> FreshnessState.AGING
            else -> FreshnessState.FRESH
        }
    }

    fun ageSeconds(lastAtElapsedMs: Long?, nowElapsedMs: Long): Int? {
        if (lastAtElapsedMs == null || lastAtElapsedMs <= 0L) return null
        val age = nowElapsedMs - lastAtElapsedMs
        return if (age < 0L) 0 else (abs(age) / 1000L).toInt()
    }
}

/* ---------------------------------------------------------------------------
 *  VERSTAENDLICHE NUTZERTEXTE
 * -------------------------------------------------------------------------*/

/**
 * Uebersetzt technische Zustaende in klare Nutzersaetze. Interne Details
 * bleiben im Entwicklerbereich/Export erhalten — hier steht nur, was der
 * Nutzer tun kann.
 */
object UserTexts {

    fun bluetoothOff(language: AppLanguage): String = when (language) {
        AppLanguage.EN -> "Bluetooth is switched off. Please enable Bluetooth and try again."
        AppLanguage.DE -> "Bluetooth ist ausgeschaltet. Bitte Bluetooth einschalten und erneut versuchen."
    }

    fun permissionsMissing(language: AppLanguage, count: Int): String = when (language) {
        AppLanguage.EN -> "Bluetooth permission is missing ($count). Please allow it in the app settings."
        AppLanguage.DE -> "Bluetooth-Berechtigung fehlt ($count). Bitte in den App-Einstellungen erlauben."
    }

    fun notConnected(language: AppLanguage): String = when (language) {
        AppLanguage.EN -> "No scooter connected. Start a scan and select your scooter."
        AppLanguage.DE -> "Kein Scooter verbunden. Starte einen Scan und waehle deinen Scooter."
    }

    fun connectionLost(language: AppLanguage): String = when (language) {
        AppLanguage.EN -> "Connection lost. The last values are marked as outdated."
        AppLanguage.DE -> "Verbindung verloren. Die letzten Werte sind als veraltet markiert."
    }

    fun staleValues(language: AppLanguage, seconds: Int?): String = when (language) {
        AppLanguage.EN -> "Values are outdated" + (seconds?.let { " (last update ${it}s ago)" } ?: "") + "."
        AppLanguage.DE -> "Werte sind veraltet" + (seconds?.let { " (letzte Aktualisierung vor ${it}s)" } ?: "") + "."
    }

    fun unknownModelReadOnly(language: AppLanguage): String = when (language) {
        AppLanguage.EN -> "Model not recognised: read-only. No write commands are sent."
        AppLanguage.DE -> "Modell nicht erkannt: Nur-Lesen. Es werden keine Schreibbefehle gesendet."
    }

    fun gattIncomplete(language: AppLanguage): String = when (language) {
        AppLanguage.EN -> "Device data incomplete. Move closer and reconnect."
        AppLanguage.DE -> "Geraetedaten unvollstaendig. Naeher herangehen und neu verbinden."
    }

    fun noTelemetry(language: AppLanguage): String = when (language) {
        AppLanguage.EN -> "No live data received yet. Values are shown as unavailable."
        AppLanguage.DE -> "Noch keine Live-Daten empfangen. Werte werden als nicht verfuegbar angezeigt."
    }

    fun developerAreaWarning(language: AppLanguage): String = when (language) {
        AppLanguage.EN -> "Developer area: raw BLE data, logs and exports. Intended for testing and diagnostics."
        AppLanguage.DE -> "Entwicklerbereich: BLE-Rohdaten, Logs und Exporte. Gedacht fuer Tests und Diagnose."
    }
}
