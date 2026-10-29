package com.syrak.scooterlab

/* ============================================================================
 *  GATT-CHARACTERISTIC-AUSWAHL (reine Logik, ohne Android-Abhaengigkeit)
 *  ---------------------------------------------------------------------------
 *  Hintergrund (belegter Defekt in der BleEngine):
 *  Bisher wurde das Notify-Characteristic einfach auf das Write-Characteristic
 *  gesetzt (`notifyChar = writeChar`). Bei Brightway/Navee ist FFE1 aber das
 *  Notify- und FFE2 das Write-Characteristic. Waehlt die Engine FFE2 als
 *  schreibbares Characteristic, hat FFE2 in der Regel KEINE NOTIFY-Property ->
 *  Notifications bleiben aus -> keine Telemetrie und kein echtes ACK.
 *
 *  Diese Klasse kapselt die Auswahl deshalb als reine Funktion, damit sie ohne
 *  echte Hardware unit-testbar ist. Sie erfindet KEINE UUIDs: die bevorzugten
 *  UUIDs kommen ausschliesslich aus den bereits im Projekt dokumentierten
 *  Konstanten (NaveeFrames.CHAR_UUID / CHAR_WRITE_UUID, BleEngine-FFF1/FFF2).
 * ==========================================================================*/
object GattCharacteristicSelector {

    /**
     * Abstraktion eines GATT-Characteristics fuer die Auswahl.
     * @param uuid       UUID als Text (case-insensitive Vergleich).
     * @param writable   PROPERTY_WRITE oder PROPERTY_WRITE_NO_RESPONSE gesetzt.
     * @param notifiable PROPERTY_NOTIFY oder PROPERTY_INDICATE gesetzt.
     */
    data class Candidate(
        val uuid: String,
        val writable: Boolean,
        val notifiable: Boolean,
    )

    /**
     * Waehlt das Notify-Characteristic.
     *
     * Reihenfolge:
     *  1. Ein notify-/indicate-faehiges Characteristic, dessen UUID in
     *     [preferredUuids] steht (in der Reihenfolge der Liste).
     *  2. Sonst das erste notify-/indicate-faehige Characteristic ueberhaupt.
     *  3. Sonst null (Aufrufer faellt auf das Write-Characteristic zurueck).
     *
     * Ein Characteristic, das nicht notifizieren kann, wird NIE gewaehlt — auch
     * nicht, wenn seine UUID bevorzugt ist.
     */
    fun pickNotify(
        candidates: List<Candidate>,
        preferredUuids: List<String>,
    ): Candidate? {
        val notifiable = candidates.filter { it.notifiable }
        if (notifiable.isEmpty()) return null
        for (preferred in preferredUuids) {
            notifiable.firstOrNull { it.uuid.equals(preferred, ignoreCase = true) }
                ?.let { return it }
        }
        return notifiable.first()
    }

    /**
     * Waehlt das Write-Characteristic.
     *
     * Reihenfolge:
     *  1. Ein schreibbares Characteristic mit bevorzugter UUID.
     *  2. Sonst das erste schreibbare Characteristic.
     *  3. Sonst null.
     */
    fun pickWrite(
        candidates: List<Candidate>,
        preferredUuids: List<String>,
    ): Candidate? {
        val writable = candidates.filter { it.writable }
        if (writable.isEmpty()) return null
        for (preferred in preferredUuids) {
            writable.firstOrNull { it.uuid.equals(preferred, ignoreCase = true) }
                ?.let { return it }
        }
        return writable.first()
    }
}
