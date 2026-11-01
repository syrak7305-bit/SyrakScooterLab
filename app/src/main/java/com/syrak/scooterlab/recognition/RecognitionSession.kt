package com.syrak.scooterlab.recognition

/* ============================================================================
 *  PHASE 17.1 / 17.2 — ERKENNUNGS-SITZUNG
 *  ---------------------------------------------------------------------------
 *  Haelt Erkennungsergebnisse getrennt pro Bluetooth-Adresse.
 *
 *  PHASE 17.2 (behobener Defekt):
 *   In 17.1 gab es nur EINEN Slot. Bei mehreren sichtbaren Geraeten hat der
 *   jeweils letzte Scan-Callback das Ergebnis des vorherigen Geraets
 *   ueberschrieben. Damit konnte ein fremdes Geraet die aktive
 *   Hardware-Erkennung verdraengen.
 *
 *  Neue Struktur:
 *   • scanResults  — ein Ergebnis je Adresse (Scan-Ebene, ueberschreibt
 *                    niemals die aktive Verbindung).
 *   • activeResult — Ergebnis des AKTIV VERBUNDENEN Geraets
 *                    (Advertisement + GATT zusammengefuehrt).
 *   • current      — zuletzt verfolgtes Scan-Ergebnis (Kompatibilitaet).
 *
 *  Regeln:
 *   • Scan-Ergebnisse verschiedener Adressen bleiben getrennt.
 *   • Ein Scan-Ergebnis einer anderen Adresse beruehrt activeResult NICHT.
 *   • bindActive akzeptiert nur Daten der uebergebenen Adresse.
 *   • clearActive verwirft das aktive Ergebnis, behaelt aber die Scan-Historie.
 *   • reset verwirft alles (z. B. bei bewusstem Verlassen).
 *
 *  Absichtlich frei von Android-Abhaengigkeiten und damit vollstaendig
 *  unit-testbar.
 * ==========================================================================*/

class RecognitionSession {

    /** Letztes Scan-Ergebnis je Bluetooth-Adresse. */
    private val scanResults = LinkedHashMap<String, RecognitionResult>()

    /** Aktuell verfolgte Adresse (letzter Scan-Callback). */
    var currentAddress: String? = null
        private set

    /** Aktuell verfolgtes Scan-Ergebnis (Kompatibilitaet zu Phase 17.1). */
    var current: RecognitionResult? = null
        private set

    /** Adresse der aktiven Verbindung (null = keine). */
    var activeAddress: String? = null
        private set

    /** Ergebnis der aktiven Verbindung (Advertisement + GATT). */
    var activeResult: RecognitionResult? = null
        private set

    /** Adresse der zuletzt beendeten Verbindung (getrennte Historie). */
    var lastActiveAddress: String? = null
        private set

    /**
     * Ergebnis der zuletzt beendeten Verbindung. Bewusst GETRENNT vom aktiven
     * Slot: ein Discovery-Fehlschlag bleibt so sichtbar, ohne als aktive
     * Verbindung zu gelten.
     */
    var lastActiveResult: RecognitionResult? = null
        private set

    /**
     * Uebernimmt ein Scan-Ergebnis fuer [address].
     *
     * Kompatibilitaetsverhalten zu Phase 17.1: [current]/[currentAddress]
     * beschreiben weiterhin genau das zuletzt beobachtete Geraet. Zusaetzlich
     * wird das Ergebnis pro Adresse abgelegt, sodass andere Geraete es nicht
     * mehr verdraengen.
     */
    fun observe(address: String, result: RecognitionResult): RecognitionResult {
        currentAddress = address
        current = result
        scanResults[address] = result
        return result
    }

    /**
     * PHASE 17.2: reines Scan-Ergebnis ablegen, OHNE die aktive Verbindung
     * anzutasten. Das ist der Pfad, den der Scan-Callback benutzt.
     */
    fun observeScan(address: String, result: RecognitionResult): RecognitionResult {
        scanResults[address] = result
        currentAddress = address
        current = result
        return result
    }

    /** Scan-Ergebnis einer beliebigen Adresse (auch nicht-aktiv). */
    fun scanResultFor(address: String): RecognitionResult? = scanResults[address]

    fun hasScanResultFor(address: String): Boolean = scanResults.containsKey(address)

    /** Alle Adressen mit Scan-Ergebnis (Einfuegereihenfolge). */
    fun scannedAddresses(): List<String> = scanResults.keys.toList()

    /**
     * PHASE 17.2: Ergebnis der aktiven Verbindung setzen. Nur Daten der
     * uebergebenen Adresse werden akzeptiert; ein Ergebnis einer anderen
     * Adresse wird verworfen und aendert nichts.
     */
    fun bindActive(address: String, result: RecognitionResult): Boolean {
        if (result.address.isNotBlank() && !result.address.equals(address, ignoreCase = true)) {
            return false
        }
        activeAddress = address
        activeResult = result
        return true
    }

    /** true, wenn ein aktives Ergebnis genau fuer [address] vorliegt. */
    fun hasActiveResultFor(address: String): Boolean =
        activeAddress != null &&
            activeAddress.equals(address, ignoreCase = true) &&
            activeResult != null

    /** Aktives Ergebnis, aber nur wenn es zur Adresse passt. */
    fun activeResultFor(address: String): RecognitionResult? =
        if (hasActiveResultFor(address)) activeResult else null

    /**
     * PHASE 17.2: Verbindung beendet. Das aktive Ergebnis wird verworfen,
     * die Scan-Historie bleibt erhalten (getrennte Historie).
     */
    fun clearActive() {
        activeAddress = null
        activeResult = null
    }

    /**
     * PHASE 17.2: Beendet die aktive Verbindung und legt deren Ergebnis in
     * den getrennten Historie-Slot. Damit bleibt z. B. eine fehlgeschlagene
     * Service-Discovery nachvollziehbar, ohne als aktive Verbindung zu gelten.
     */
    fun archiveActive(): RecognitionResult? {
        val archived = activeResult
        lastActiveAddress = activeAddress
        lastActiveResult = archived
        activeAddress = null
        activeResult = null
        return archived
    }

    /** Verwirft die getrennte Historie. */
    fun clearHistory() {
        lastActiveAddress = null
        lastActiveResult = null
    }

    /**
     * true, wenn ein Ergebnis genau fuer [address] das aktuell verfolgte ist.
     * (Bedeutung unveraendert zu Phase 17.1.)
     */
    fun hasResultFor(address: String): Boolean =
        currentAddress == address && current != null

    /** Liefert das verfolgte Ergebnis nur, wenn es zur Adresse passt. */
    fun resultFor(address: String): RecognitionResult? =
        if (hasResultFor(address)) current else null

    /**
     * Das fuer die Anzeige/den Export massgebliche Ergebnis:
     * aktive Verbindung, sonst das Ergebnis der zuletzt beendeten Verbindung
     * (getrennte Historie), sonst das zuletzt beobachtete Scan-Ergebnis.
     *
     * PHASE 17.2: Die Historie wird bewusst vor dem reinen Scan-Ergebnis
     * verwendet, damit ein Export nach dem Trennen weiterhin die echte
     * GATT-Erkenntnis (inkl. Discovery-Status) enthaelt und nicht still auf
     * das Advertisement-Ergebnis zurueckfaellt.
     */
    fun displayResult(): RecognitionResult? = activeResult ?: lastActiveResult ?: current

    /** Verwirft alles (aktive Verbindung UND Scan-Historie). */
    fun reset() {
        scanResults.clear()
        current = null
        currentAddress = null
        activeResult = null
        activeAddress = null
        lastActiveResult = null
        lastActiveAddress = null
    }
}
