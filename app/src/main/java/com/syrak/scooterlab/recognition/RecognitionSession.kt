package com.syrak.scooterlab.recognition

/* ============================================================================
 *  PHASE 17.1 — ERKENNUNGS-SITZUNG
 *  ---------------------------------------------------------------------------
 *  Haelt das Erkennungsergebnis genau eines Geraets. Wechselt die Adresse,
 *  werden alte Hardware-Erkennungsergebnisse VERWORFEN. Damit koennen
 *  Diagnosewerte eines Rollers nie einem anderen Roller zugeordnet werden.
 *
 *  Diese Klasse ist absichtlich frei von Android-Abhaengigkeiten und damit
 *  vollstaendig unit-testbar.
 * ==========================================================================*/

class RecognitionSession {

    /** Aktuelles Ergebnis oder null, wenn keines fuer das aktuelle Geraet vorliegt. */
    var current: RecognitionResult? = null
        private set

    /** Adresse, zu der [current] gehoert. */
    var currentAddress: String? = null
        private set

    /**
     * Uebernimmt ein Ergebnis fuer [address]. Bei Adresswechsel wird zuerst
     * zurueckgesetzt — alte Ergebnisse werden nie weitergetragen.
     *
     * @return das uebernommene Ergebnis.
     */
    fun observe(address: String, result: RecognitionResult): RecognitionResult {
        if (currentAddress != null && currentAddress != address) {
            reset()
        }
        currentAddress = address
        current = result
        return result
    }

    /** true, wenn ein Ergebnis genau fuer [address] vorliegt. */
    fun hasResultFor(address: String): Boolean =
        currentAddress == address && current != null

    /** Liefert das Ergebnis nur, wenn es zur Adresse passt. */
    fun resultFor(address: String): RecognitionResult? =
        if (hasResultFor(address)) current else null

    /** Verwirft das aktuelle Ergebnis (z. B. bei Trennung oder Geraetewechsel). */
    fun reset() {
        current = null
        currentAddress = null
    }
}
