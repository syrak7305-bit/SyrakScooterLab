package com.syrak.scooterlab

/* ============================================================================
 *  PHASE 16.3.2 — SCAN-AGGREGATION (reine Logik, ohne Android)
 *  ---------------------------------------------------------------------------
 *  Belegte Defekte:
 *
 *   R3 (Namens-Regression): In MainActivity wurde pro Scan-Callback
 *      `foundDevices[address] = ScooterDevice(device, name, ...)` gesetzt.
 *      Ein spaeterer Callback ohne Namen ueberschrieb damit einen bereits
 *      bekannten guten Namen mit "Unknown Device".
 *
 *   R5 (Bericht): Der Hardware-Testbericht aggregiert zwar bereits korrekt
 *      (HardwareTestReport.recordScan zaehlt seenCount hoch und behaelt
 *      firstSeenMs) — ABER er bekam als `name` den Literal-String
 *      "Unknown Device" uebergeben. Weil dieser nicht null ist, konnte die
 *      Aggregation nicht auf den vorherigen echten Namen zurueckfallen.
 *      Der Bericht war dadurch an dieser Stelle irrefuehrend.
 *
 *  Diese Klasse fuehrt pro Adresse genau EINEN Zustand:
 *   • seenCount        — Anzahl der Beobachtungen
 *   • firstSeenMs      — Zeitpunkt der ERSTEN Beobachtung (unveraenderlich)
 *   • lastSeenMs       — Zeitpunkt der LETZTEN Beobachtung
 *   • rssi             — immer der NEUESTE Wert (Funkpegel aendert sich)
 *   • name/nameSource  — monoton: ein bekannter Name wird nie durch "keinen"
 *                        Namen ersetzt
 *   • evidence         — der STAERKSTE je gesehene Hinweis bleibt erhalten
 *
 *  Damit ist die Liste stabil, auch wenn ein Geraet zwischenzeitlich ohne
 *  Namen oder ohne Advertisement-Daten antwortet.
 * ==========================================================================*/

/**
 * Aggregierter Zustand eines Geraets ueber mehrere Scan-Callbacks.
 */
data class AggregatedScan(
    val address: String,
    val name: String?,
    val nameSource: DeviceNameSource,
    val rssi: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val seenCount: Int,
    val serviceUuids: List<String>,
    val manufacturerIds: List<Int>,
    val evidence: AdvertisementEvidence
) {
    /** Anzeigename mit ehrlichem Fallback. */
    fun displayName(fallback: String = "Name nicht verfuegbar"): String =
        name?.takeIf { it.isNotBlank() } ?: fallback

    /** Beobachtungsdauer in Millisekunden. */
    val durationMs: Long get() = (lastSeenMs - firstSeenMs).coerceAtLeast(0L)

    /** true, wenn der Name aus einer echten Quelle stammt. */
    val hasRealName: Boolean get() = nameSource.isReal && !name.isNullOrBlank()
}

/**
 * Fuehrt Scan-Beobachtungen pro Adresse zusammen.
 *
 * Nicht threadsicher — der Aufrufer (MainActivity) nutzt sie ausschliesslich
 * auf dem UI-Thread.
 */
class ScanAggregator {

    private val entries = LinkedHashMap<String, AggregatedScan>()

    /**
     * Verbucht eine Beobachtung und liefert den aktualisierten Zustand.
     *
     * @param evidence bereits berechnete Advertisement-Evidenz. Wird als
     *        Parameter uebergeben, damit die Analyse testbar bleibt und nicht
     *        versteckt in der Aggregation passiert.
     */
    fun observe(
        observation: ScanObservation,
        evidence: AdvertisementEvidence = AdvertisementEvidence.NONE
    ): AggregatedScan {
        val key = observation.address
        val resolved = DeviceNameResolver.resolve(observation)
        val existing = entries[key]

        val merged = if (existing == null) {
            AggregatedScan(
                address = key,
                name = resolved.name,
                nameSource = resolved.source,
                rssi = observation.rssi,
                firstSeenMs = observation.timestampMs,
                lastSeenMs = observation.timestampMs,
                seenCount = 1,
                serviceUuids = observation.serviceUuids,
                manufacturerIds = observation.manufacturerIds,
                evidence = evidence
            )
        } else {
            val preferredName = DeviceNameResolver.prefer(
                ResolvedName(existing.name, existing.nameSource),
                resolved
            )
            existing.copy(
                name = preferredName.name,
                nameSource = preferredName.source,
                // Funkpegel: immer der neueste Messwert.
                rssi = observation.rssi,
                // firstSeenMs bleibt unveraendert (copy behaelt den Wert).
                lastSeenMs = observation.timestampMs,
                seenCount = existing.seenCount + 1,
                // Zusatzdaten monoton vereinigen: einmal gesehen bleibt sichtbar.
                serviceUuids = (existing.serviceUuids + observation.serviceUuids).distinct(),
                manufacturerIds = (existing.manufacturerIds + observation.manufacturerIds).distinct(),
                // Staerkerer Hinweis gewinnt; ein schwacher ueberschreibt nie einen starken.
                evidence = strongerEvidence(existing.evidence, evidence)
            )
        }
        entries[key] = merged
        return merged
    }

    /**
     * Waehlt den aussagekraeftigeren Hinweis.
     *
     * Reihenfolge: kein Hinweis < WEAK < STRONG. Bei Gleichstand bleibt der
     * bestehende Hinweis erhalten (er wurde zuerst beobachtet).
     */
    private fun strongerEvidence(
        existing: AdvertisementEvidence,
        incoming: AdvertisementEvidence
    ): AdvertisementEvidence {
        if (!incoming.hasHint) return existing
        if (!existing.hasHint) return incoming
        val existingRank = if (existing.isStrong) 2 else 1
        val incomingRank = if (incoming.isStrong) 2 else 1
        return if (incomingRank > existingRank) incoming else existing
    }

    /** Alle aggregierten Geraete (Einfuegereihenfolge). */
    fun snapshot(): List<AggregatedScan> = entries.values.toList()

    /** Zustand einer Adresse, oder null. */
    fun get(address: String): AggregatedScan? = entries[address]

    /** Anzahl bekannter Geraete. */
    fun size(): Int = entries.size

    /** Setzt alles zurueck (beim Start eines neuen Scans). */
    fun clear() = entries.clear()
}
