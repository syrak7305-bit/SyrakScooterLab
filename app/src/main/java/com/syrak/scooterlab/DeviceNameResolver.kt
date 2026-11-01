package com.syrak.scooterlab

import java.util.Locale

/* ============================================================================
 *  PHASE 16.3.2 — NAMENSAUFLOESUNG (reine Logik, ohne Android-Abhaengigkeit)
 *  ---------------------------------------------------------------------------
 *  Belegter Defekt (R4): Der Name eines Scan-Treffers wurde als
 *  nicht-null String gespeichert, mit dem Literal "Unknown Device" als
 *  Ersatzwert. Damit war "kein Name vorhanden" strukturell nicht mehr
 *  darstellbar.
 *
 *  Diese Klasse loest den Namen aus einer [ScanObservation] auf und liefert
 *  IMMER die Herkunft mit. Regeln:
 *
 *   1. `gattDeviceName` gewinnt, wenn nicht leer und nicht der Platzhalter.
 *   2. sonst `advertisedName`, wenn nicht leer und nicht der Platzhalter.
 *   3. sonst (null, NONE) — es wird NICHTS erfunden.
 *
 *  Der historische Platzhalter-String wird aktiv verworfen, weil er in alten
 *  Persistenzdaten (repository.lastDeviceName) vorkommen kann und sonst als
 *  echter Name weiterleben wuerde.
 * ==========================================================================*/
object DeviceNameResolver {

    /**
     * Historischer Platzhalter aus frueheren Projektstaenden. Wird bewusst
     * NICHT als Name akzeptiert, damit alte gespeicherte Werte nicht als
     * echter Geraetename durchgehen.
     */
    const val LEGACY_PLACEHOLDER = "Unknown Device"

    /** Weitere Platzhalter, die nie als echter Name gelten duerfen. */
    private val PLACEHOLDERS = setOf(
        LEGACY_PLACEHOLDER.lowercase(Locale.ROOT),
        "unknown",
        "null",
        "n/a",
        "unbekannt",
        "name nicht verfuegbar"
    )

    /**
     * Normalisiert einen Roh-Namen.
     *
     * @return getrimmter Name, oder null wenn leer/Platzhalter.
     */
    fun normalize(raw: String?): String? {
        val t = raw?.trim() ?: return null
        if (t.isEmpty()) return null
        if (t.lowercase(Locale.ROOT) in PLACEHOLDERS) return null
        return t
    }

    /**
     * Loest den Namen einer Beobachtung auf.
     *
     * Die Reihenfolge ist fest und deterministisch. Ein Advertisement-Name
     * ueberschreibt NIE einen vorhandenen GATT-Namen.
     */
    fun resolve(observation: ScanObservation): ResolvedName {
        normalize(observation.gattDeviceName)?.let {
            return ResolvedName(it, DeviceNameSource.GATT_DEVICE_NAME)
        }
        normalize(observation.advertisedName)?.let {
            return ResolvedName(it, DeviceNameSource.ADVERTISEMENT)
        }
        return ResolvedName(null, DeviceNameSource.NONE)
    }

    /**
     * Bevorzugt zwischen einem bereits bekannten und einem neu gelesenen
     * Namen. Wird von [ScanAggregator] genutzt, um die Namens-Regression
     * (R3) zu verhindern.
     *
     * Ein neuer Name gewinnt nur, wenn er eine HOEHERWERTIGE Quelle hat oder
     * die Quelle gleich ist und der neue Name nicht leer ist. Ein leerer
     * neuer Name kann einen bekannten Namen nie verdraengen.
     */
    fun prefer(existing: ResolvedName?, incoming: ResolvedName): ResolvedName {
        if (existing == null || !existing.hasName) return incoming
        if (!incoming.hasName) return existing
        return if (incoming.source.priority >= existing.source.priority) incoming else existing
    }
}
