package com.syrak.scooterlab

/* ============================================================================
 *  PHASE 16.3.4: GERAETENAME vs. ERKANNTES MODELL (reine Anzeige-Logik)
 *  ---------------------------------------------------------------------------
 *  Belegter Defekt (Phase-16.3.3-Audit): Die aktive Ansicht zeigte nur das
 *  erkannte MODELL und fiel bei fehlendem Namen auf die MAC-Adresse zurueck.
 *  Geraetename und Modell sind aber zwei verschiedene Dinge:
 *
 *    - Geraetename  = was das Geraet im Scan/GATT tatsaechlich meldet.
 *    - Modell       = eine reine Namens-Heuristik (ScooterModel.detectDetailed).
 *
 *  Diese Objekt kapselt die Formatierung als reine, testbare Funktionen. Es
 *  wird NIE die MAC-Adresse als "Name" ausgegeben und NIE ein Modell
 *  behauptet, das nicht erkannt wurde.
 * ==========================================================================*/
object DeviceDisplayLabels {

    /** Ehrlicher Fallback, wenn kein echter Geraetename empfangen wurde. */
    const val NO_NAME = "unbekannt (kein Name empfangen)"

    /** Anzeigename des Geraets. Leere/blanke Namen gelten als "kein Name". */
    fun deviceName(name: String?): String =
        name?.trim()?.takeIf { it.isNotEmpty() } ?: NO_NAME

    /**
     * Anzeige des erkannten Modells inkl. Erkennungssicherheit. Bei unbekanntem
     * Modell wird "Modell unbekannt" ausgegeben — kein Raten.
     */
    fun modelLabel(model: ScooterModel, confidence: DetectionConfidence): String =
        if (model.isKnown) {
            "${model.displayName} [${confidence.label}]"
        } else {
            "Modell unbekannt [${confidence.label}]"
        }
}
