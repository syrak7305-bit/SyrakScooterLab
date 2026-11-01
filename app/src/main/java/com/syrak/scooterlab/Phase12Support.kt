package com.syrak.scooterlab

import com.syrak.scooterlab.core.ble.ScooterBrand
import java.util.Locale

/* ============================================================================
 *  PHASE 12 — UNTERSTUETZENDE, REINE LOGIK
 *  ----------------------------------------------------------------------------
 *  Diese Datei enthaelt ausschliesslich plattformunabhaengige Logik (keine
 *  Android-Abhaengigkeiten), damit sie ohne Geraet/Emulator unit-getestet
 *  werden kann:
 *    • CountryLegalCatalog  — belegbare Laenderhinweise inkl. Quelle/Stand
 *    • ScanClassifier       — Einordnung von BLE-Scan-Treffern
 *    • SignalQuality        — RSSI in verstaendliche Qualitaet
 *    • PollGate             — Voraussetzungen fuer Telemetrie-Polling
 *    • TripClock            — Formatierung der Fahrtzeit
 *    • DiagnosticHistory    — lokale Diagnose-Historie + Vergleich
 *    • SystemStatus         — kompakte Systemuebersicht
 *
 *  GRUNDREGEL: Es werden KEINE Gesetze, Geschwindigkeitsgrenzen, UUIDs oder
 *  Hardware-Funktionen erfunden. Wo eine Angabe nicht belegbar ist, wird sie
 *  ausdruecklich als unbestimmt/ungeprueft gekennzeichnet.
 * ==========================================================================*/

/* ---------------------------------------------------------------------------
 *  LAENDER-HINWEISE
 * -------------------------------------------------------------------------*/

/**
 * Ein belegbarer Laenderhinweis. [verified] ist nur dann true, wenn die
 * Kernaussage (z. B. bauartbedingte Hoechstgeschwindigkeit) durch eine
 * benannte Rechtsquelle gestuetzt ist. [source] nennt die Quelle, [sourceDate]
 * den Stand der Recherche. Dies ist KEINE Rechtsberatung.
 */
data class CountryLegalNotice(
    val code: String,
    val displayName: String,
    val legalSpeedKmh: Int?,
    val verified: Boolean,
    val source: String,
    val sourceDate: String,
    val points: List<String>
)

object CountryLegalCatalog {

    /** Stand der hinterlegten Angaben. Bei Aenderung mitpflegen. */
    const val CATALOG_DATE = "2024-10"

    private const val DISCLAIMER =
        "Allgemeine Hinweise, keine Rechtsberatung. Im Zweifel zustaendige Behoerde konsultieren."

    val DE = CountryLegalNotice(
        code = "DE",
        displayName = "Deutschland",
        legalSpeedKmh = 20,
        verified = true,
        source = "eKFV (Elektrokleinstfahrzeuge-Verordnung) i. V. m. StVO",
        sourceDate = CATALOG_DATE,
        points = listOf(
            "Elektrokleinstfahrzeuge unterliegen den technischen Vorgaben der eKFV.",
            "Die bauartbedingte Hoechstgeschwindigkeit betraegt 20 km/h; ein Ueberschreiten im oeffentlichen Verkehr ist unzulaessig.",
            "Manipulationen an Motorsteuerung oder Firmware lassen die Betriebserlaubnis (ABE) erloeschen.",
            "Ohne gueltige ABE entfaellt in der Regel der Versicherungsschutz.",
            DISCLAIMER
        )
    )

    val AT = CountryLegalNotice(
        code = "AT",
        displayName = "Oesterreich",
        legalSpeedKmh = 25,
        verified = true,
        source = "Kraftfahrgesetz (KFG) / E-Scooter-Regelung, StVO",
        sourceDate = CATALOG_DATE,
        points = listOf(
            "E-Scooter sind in Oesterreich an eine bauartbedingte Hoechstgeschwindigkeit von 25 km/h gebunden.",
            "Ueberschreitungen koennen das Fahrzeug rechtlich als unzulaessiges Kraftfahrzeug einstufen.",
            "Betrieb im oeffentlichen Raum erfordert die Einhaltung der StVO.",
            DISCLAIMER
        )
    )

    val CH = CountryLegalNotice(
        code = "CH",
        displayName = "Schweiz",
        legalSpeedKmh = 20,
        verified = true,
        source = "VTS (Verordnung ueber die technischen Anforderungen an Strassenfahrzeuge)",
        sourceDate = CATALOG_DATE,
        points = listOf(
            "E-Trottinette duerfen bauartbedingt maximal 20 km/h mit Motorunterstuetzung erreichen.",
            "Technische Tuning-Eingriffe sind auf oeffentlichen Strassen untersagt und koennen die Zulassung kosten.",
            DISCLAIMER
        )
    )

    val US = CountryLegalNotice(
        code = "US",
        displayName = "United States",
        legalSpeedKmh = null,
        verified = false,
        source = "Bundesweit keine einheitliche Grenze; Regelung je Bundesstaat/Kommune",
        sourceDate = CATALOG_DATE,
        points = listOf(
            "In den USA gibt es keine bundesweit einheitliche Hoechstgeschwindigkeit fuer E-Scooter.",
            "Massgeblich sind die Vorschriften des jeweiligen Bundesstaates bzw. der Kommune.",
            "Eine konkrete Grenze wird hier bewusst NICHT genannt, weil sie nicht belegbar ist.",
            DISCLAIMER
        )
    )

    val WORLD = CountryLegalNotice(
        code = "XX",
        displayName = "Unbekanntes Land",
        legalSpeedKmh = null,
        verified = false,
        source = "Keine belastbare Quelle fuer dieses Land hinterlegt",
        sourceDate = CATALOG_DATE,
        points = listOf(
            "Fuer dieses Land ist keine geprueften Rechtsangaben hinterlegt.",
            "Es werden bewusst KEINE erfundenen Geschwindigkeitsgrenzen angezeigt.",
            "Pruefe die lokalen Vorschriften eigenstaendig, bevor du das Fahrzeug im oeffentlichen Raum nutzt.",
            DISCLAIMER
        )
    )

    fun all(): List<CountryLegalNotice> = listOf(DE, AT, CH, US, WORLD)

    /** Liefert den Hinweis zu einer [Region]. Unbekannte Region -> neutraler Hinweis. */
    fun forRegion(region: Region): CountryLegalNotice = when (region) {
        Region.DE -> DE
        Region.AT -> AT
        Region.CH -> CH
        Region.US -> US
        Region.WORLD -> WORLD
    }

    /** Liefert den Hinweis zu einem ISO-Laendercode (z. B. "DE"). */
    fun forCountryCode(code: String?): CountryLegalNotice {
        val region = Region.fromCountry(code ?: "")
        return forRegion(region)
    }
}

/* ---------------------------------------------------------------------------
 *  SCAN-EINORDNUNG
 * -------------------------------------------------------------------------*/

enum class ScanCategory {
    /** Modell sicher/probabel erkannt. */
    KNOWN_SCOOTER,
    /** Hersteller oder Geraeteklasse deutet auf einen Scooter hin, Modell unklar. */
    PROBABLE_SCOOTER,
    /** Kein Hinweis auf einen Scooter (TV, Kopfhoerer, sonstiges). */
    UNKNOWN_DEVICE
}

data class ScanClassification(
    val category: ScanCategory,
    val brandLabel: String,
    val modelLabel: String,
    val confidenceLabel: String,
    val manufacturerKnown: Boolean,
    /**
     * PHASE 16.3.2: menschenlesbare Begruendung, WORAUS die Einordnung stammt
     * (Name, Advertisement-Service-UUID, Company-ID, Service-Data oder nichts).
     * Leer bei der rein namensbasierten Alt-Signatur — dort gibt es keine
     * zusaetzliche Quelle zu benennen.
     */
    val evidenceLabel: String = "",
    /**
     * PHASE 16.3.2: Herkunft des angezeigten Namens. NONE bedeutet: es liegt
     * KEIN Name vor (nicht: "das Geraet heisst Unknown Device").
     */
    val nameSource: DeviceNameSource = DeviceNameSource.NONE
) {
    val isScooterLike: Boolean get() = category != ScanCategory.UNKNOWN_DEVICE
}

object ScanClassifier {

    /**
     * Ordnet einen Scan-Treffer ein. Ein unbekanntes Geraet wird NIEMALS
     * automatisch als Scooter behandelt — es landet in [ScanCategory.UNKNOWN_DEVICE]
     * und bleibt in der Liste sichtbar (nicht verstecken!).
     */
    fun classify(name: String?, address: String): ScanClassification {
        val safeName = name ?: ""
        val det = ScooterModel.detectDetailed(safeName, address)
        val brand = ScooterBrand.classify(safeName)
        // PHASE 12.3: Hersteller gilt nur als bekannt, wenn ein KONSERVATIVES
        // Marken-Token passt (ScooterManufacturer). ScooterBrand.classify()
        // ordnet z. B. jedes "Mi ..." Xiaomi zu - "Mi Band 7" ist aber ein
        // Fitnessband und darf NICHT als Scooter-Kandidat gelten.
        val scooterNameHint = ScooterCandidateFilter.looksLikeScooter(safeName)
        val manufacturerKnown = det.manufacturer.isKnown

        val category = when {
            det.model.isKnown -> ScanCategory.KNOWN_SCOOTER
            manufacturerKnown -> ScanCategory.PROBABLE_SCOOTER
            scooterNameHint -> ScanCategory.PROBABLE_SCOOTER
            else -> ScanCategory.UNKNOWN_DEVICE
        }
        val modelLabel = if (det.model.isKnown) det.model.displayName else "unbekannt"
        val brandLabel = when {
            det.manufacturer.isKnown -> det.manufacturer.displayName
            scooterNameHint && brand != ScooterBrand.UNKNOWN -> brand.displayName
            else -> "unbekannt"
        }
        return ScanClassification(
            category = category,
            brandLabel = brandLabel,
            modelLabel = modelLabel,
            confidenceLabel = det.confidence.label,
            manufacturerKnown = manufacturerKnown
        )
    }

    /**
     * PHASE 16.3.2: Einordnung auf Basis einer vollstaendigen Scan-Beobachtung.
     *
     * Neu gegenueber [classify] ist die Auswertung der Advertisement-Daten.
     * Die Regeln bleiben bewusst konservativ:
     *
     *   • Ein bekanntes MODELL kommt weiterhin NUR aus dem Namen. Aus einer
     *     Service-UUID laesst sich kein Modell ableiten — das waere geraten.
     *   • Ein Advertisement-Hinweis kann ein Geraet hoechstens zu
     *     [ScanCategory.PROBABLE_SCOOTER] machen, niemals zu KNOWN_SCOOTER.
     *   • Ein schwacher Hinweis (z. B. Xiaomi fe95) rechtfertigt KEINEN
     *     Kandidatenstatus — er wird nur als Begruendung angezeigt.
     *   • Ohne jeden Hinweis bleibt es [ScanCategory.UNKNOWN_DEVICE].
     *
     * Damit kann ein Fremdgeraet (Kopfhoerer, Fitnessband, TV) nicht allein
     * durch eine zufaellig passende UUID als Scooter erscheinen: dafuer ist
     * eine transportspezifische UUID (STRONG) oder ein Name noetig.
     */
    fun classify(observation: ScanObservation): ScanClassification {
        val resolved = DeviceNameResolver.resolve(observation)
        val nameBased = classify(resolved.name, observation.address)
        val evidence = AdvertisementAnalyzer.analyze(observation)

        // Ein bekanntes Modell aus dem Namen hat immer Vorrang.
        if (nameBased.category == ScanCategory.KNOWN_SCOOTER) {
            return nameBased.copy(
                evidenceLabel = evidence.description,
                nameSource = resolved.source
            )
        }

        // Name-basierter Hersteller-/Namenshinweis: bleibt PROBABLE.
        if (nameBased.isScooterLike) {
            return nameBased.copy(
                evidenceLabel = evidence.description,
                nameSource = resolved.source
            )
        }

        // Ab hier: der Name allein reicht nicht. Advertisement pruefen.
        if (evidence.justifiesCandidate) {
            val brandLabel = if (evidence.manufacturer.isKnown) {
                evidence.manufacturer.displayName
            } else {
                "unbekannt"
            }
            return ScanClassification(
                category = ScanCategory.PROBABLE_SCOOTER,
                brandLabel = brandLabel,
                modelLabel = "unbekannt",
                // Bewusst NICHT "bestaetigt": es ist ein Hinweis, kein Beweis.
                confidenceLabel = "Hinweis (Advertisement)",
                manufacturerKnown = evidence.manufacturer.isKnown,
                evidenceLabel = evidence.description,
                nameSource = resolved.source
            )
        }

        // Kein ausreichender Hinweis -> ehrlich unbekannt.
        return nameBased.copy(
            evidenceLabel = evidence.description,
            nameSource = resolved.source
        )
    }
}

/* ---------------------------------------------------------------------------
 *  SIGNALQUALITAET
 * -------------------------------------------------------------------------*/

data class SignalQuality(val bars: Int, val label: String) {
    /** Textuelle Kennzeichnung, damit Status nicht nur ueber Farbe transportiert wird. */
    val accessibleLabel: String get() = "$label ($bars/4)"
}

object SignalQualityScale {
    /** RSSI in dBm -> 0..4 Balken + Text. Reine Naeherung, keine Hardware-Aussage. */
    fun fromRssi(rssi: Int): SignalQuality = when {
        rssi >= -55 -> SignalQuality(4, "Sehr gut")
        rssi >= -67 -> SignalQuality(3, "Gut")
        rssi >= -78 -> SignalQuality(2, "Mittel")
        rssi >= -88 -> SignalQuality(1, "Schwach")
        else -> SignalQuality(0, "Sehr schwach")
    }
}

/* ---------------------------------------------------------------------------
 *  POLL-GATE
 * -------------------------------------------------------------------------*/

enum class PollDecision {
    ALLOWED,
    BLOCKED_NOT_READY,
    BLOCKED_NO_WRITE_CHAR,
    BLOCKED_UNKNOWN_MODEL,
    BLOCKED_UNSUPPORTED
}

/**
 * Entscheidet, ob ein Telemetrie-Poll gesendet werden darf. Ein Poll ist ein
 * GATT-WRITE eines Lese-Requests; deshalb wird er nur bei erkanntem Modell,
 * vorhandenem Write-Characteristic und belegter Lese-Faehigkeit gesendet.
 * Unbekannte Geraete erhalten KEINE Schreibbefehle.
 */
object PollGate {
    fun evaluate(
        engineReady: Boolean,
        hasWriteChar: Boolean,
        modelKnown: Boolean,
        adapterSupportsRead: Boolean
    ): PollDecision = when {
        !engineReady -> PollDecision.BLOCKED_NOT_READY
        !hasWriteChar -> PollDecision.BLOCKED_NO_WRITE_CHAR
        !modelKnown -> PollDecision.BLOCKED_UNKNOWN_MODEL
        !adapterSupportsRead -> PollDecision.BLOCKED_UNSUPPORTED
        else -> PollDecision.ALLOWED
    }

    fun shouldPoll(
        engineReady: Boolean,
        hasWriteChar: Boolean,
        modelKnown: Boolean,
        adapterSupportsRead: Boolean
    ): Boolean = evaluate(engineReady, hasWriteChar, modelKnown, adapterSupportsRead) == PollDecision.ALLOWED

    fun reason(decision: PollDecision): String? = when (decision) {
        PollDecision.ALLOWED -> null
        PollDecision.BLOCKED_NOT_READY -> "Verbindung nicht bereit"
        PollDecision.BLOCKED_NO_WRITE_CHAR -> "kein Write-Characteristic"
        PollDecision.BLOCKED_UNKNOWN_MODEL -> "Modell unbekannt — kein Schreibbefehl"
        PollDecision.BLOCKED_UNSUPPORTED -> "Adapter unterstuetzt kein Lesen"
    }
}

/* ---------------------------------------------------------------------------
 *  TRIP-CLOCK (Fahrtzeit)
 * -------------------------------------------------------------------------*/

object TripClock {
    /** Formatiert Millisekunden als HH:MM:SS (negativ -> 00:00:00). */
    fun format(millis: Long): String {
        val total = (millis.coerceAtLeast(0L)) / 1000L
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }
}

/* ---------------------------------------------------------------------------
 *  DIAGNOSE-HISTORIE
 * -------------------------------------------------------------------------*/

data class DiagnosticHistoryEntry(
    val timestampMs: Long,
    val model: String,
    val supportStatus: String,
    val errors: Int,
    val warnings: Int,
    val summary: String
)

/**
 * Kodiert die Historie als zeilenbasierten Text (robust, ohne externe Lib).
 * Felder werden mit '|' getrennt; '|', '\\n' und '\\\\' werden escaped.
 */
object DiagnosticHistoryCodec {

    fun encode(entries: List<DiagnosticHistoryEntry>): String =
        entries.joinToString("\n") { e ->
            listOf(
                e.timestampMs.toString(),
                esc(e.model),
                esc(e.supportStatus),
                e.errors.toString(),
                e.warnings.toString(),
                esc(e.summary)
            ).joinToString("|")
        }

    fun decode(raw: String?): List<DiagnosticHistoryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val p = splitEscaped(line)
            if (p.size < 6) return@mapNotNull null
            val ts = p[0].toLongOrNull() ?: return@mapNotNull null
            val errors = p[3].toIntOrNull() ?: 0
            val warnings = p[4].toIntOrNull() ?: 0
            DiagnosticHistoryEntry(ts, unesc(p[1]), unesc(p[2]), errors, warnings, unesc(p[5]))
        }
    }

    /** Vergleicht zwei Berichte und liefert eine knappe, lesbare Entwicklung. */
    fun compare(old: DiagnosticHistoryEntry, new: DiagnosticHistoryEntry): String {
        val dErr = new.errors - old.errors
        val dWarn = new.warnings - old.warnings
        fun delta(v: Int) = when {
            v > 0 -> "+$v"
            v < 0 -> "$v"
            else -> "±0"
        }
        return buildString {
            append("Modell: ").append(old.model).append(" -> ").append(new.model).append('\n')
            append("Fehler: ").append(old.errors).append(" -> ").append(new.errors)
            append(" (").append(delta(dErr)).append(")\n")
            append("Warnungen: ").append(old.warnings).append(" -> ").append(new.warnings)
            append(" (").append(delta(dWarn)).append(")\n")
            append("Status: ").append(old.supportStatus).append(" -> ").append(new.supportStatus)
        }
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("|", "\\p").replace("\n", "\\n")

    private fun unesc(s: String): String =
        s.replace("\\n", "\n").replace("\\p", "|").replace("\\\\", "\\")

    private fun splitEscaped(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && i + 1 < line.length) {
                sb.append(c).append(line[i + 1]); i += 2; continue
            }
            if (c == '|') { out.add(sb.toString()); sb.clear() } else sb.append(c)
            i++
        }
        out.add(sb.toString())
        return out
    }
}

/* ---------------------------------------------------------------------------
 *  SYSTEM-STATUS
 * -------------------------------------------------------------------------*/

data class SystemStatusInput(
    val bluetoothAvailable: Boolean,
    val permissionsGranted: Boolean,
    val connected: Boolean,
    val gattServiceKnown: Boolean,
    val protocolVerified: Boolean,
    val telemetryAvailable: Boolean,
    val diagnosticsPossible: Boolean
)

data class SystemStatusRow(val label: String, val ok: Boolean, val detail: String) {
    /** Statuswort, damit die Information nicht allein an der Farbe haengt. */
    val stateWord: String get() = if (ok) "OK" else "OFFEN"
}

object SystemStatus {
    fun build(input: SystemStatusInput): List<SystemStatusRow> = listOf(
        SystemStatusRow("Bluetooth verfuegbar", input.bluetoothAvailable,
            if (input.bluetoothAvailable) "Adapter aktiv" else "kein BLE-Adapter"),
        SystemStatusRow("Berechtigungen", input.permissionsGranted,
            if (input.permissionsGranted) "erteilt" else "fehlen"),
        SystemStatusRow("Geraet verbunden", input.connected,
            if (input.connected) "verbunden" else "nicht verbunden"),
        SystemStatusRow("GATT-Service erkannt", input.gattServiceKnown,
            if (input.gattServiceKnown) "bekannt" else "unbekannt"),
        SystemStatusRow("Protokoll verifiziert", input.protocolVerified,
            if (input.protocolVerified) "bestaetigt" else "nicht verifiziert"),
        SystemStatusRow("Telemetrie verfuegbar", input.telemetryAvailable,
            if (input.telemetryAvailable) "liefert Daten" else "keine Daten"),
        SystemStatusRow("Diagnose moeglich", input.diagnosticsPossible,
            if (input.diagnosticsPossible) "bereit" else "eingeschraenkt")
    )
}
