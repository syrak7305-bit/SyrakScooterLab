package com.syrak.scooterlab

import java.util.Locale

/* ============================================================================
 *  PHASE 16.3.2 — SCAN-BEOBACHTUNG (reine Logik, ohne Android-Abhaengigkeit)
 *  ---------------------------------------------------------------------------
 *  WARUM DIESE DATEI EXISTIERT (belegter Defekt):
 *
 *  Bis Phase 16.3.1 wurde ein Scan-Treffer sofort zu einem [ScooterDevice]
 *  verengt:
 *
 *      val name = device.name ?: scanRecord?.deviceName ?: "Unknown Device"
 *      foundDevices[address] = ScooterDevice(device, name, ...)
 *
 *  Zwei Folgen, beide im Ist-Zustand-Bericht belegt (R3/R4):
 *
 *   1. Der Literal-String "Unknown Device" wurde als ECHTER Name gespeichert.
 *      Danach war nirgends mehr unterscheidbar, ob ein Geraet keinen Namen
 *      hatte oder tatsaechlich so heisst.
 *   2. Ein spaeterer Callback ohne Namen ueberschrieb einen bereits bekannten
 *      guten Namen wieder mit "Unknown Device" (Namens-Regression).
 *
 *  Zusaetzlich wurden Advertisement-Daten NIE ausgewertet. Ein `grep` ueber
 *  das gesamte app/src nach manufacturerData / getServiceData / serviceUuids
 *  ergab NULL Treffer. Reale E-Scooter senden im Advertisement regelmaessig
 *  keinen Namen — damit konnten sie nie Kandidat werden.
 *
 *  WAS HIER PASSIERT:
 *   Diese Datei haelt die ROHE Beobachtung eines einzelnen Advertisement-
 *   Pakets fest, ohne sie zu interpretieren. Interpretation passiert in
 *   [DeviceNameResolver] (Name) und [AdvertisementAnalyzer] (Hinweise).
 *
 *  WAS HIER AUSDRUECKLICH NICHT PASSIERT:
 *   • Es wird keine UUID und keine Company-ID erfunden.
 *   • Es wird kein Name aus einer MAC-Adresse abgeleitet.
 *   • Es wird nichts als "Scooter" behauptet — das entscheidet allein
 *     [ScanClassifier] auf Basis der hier transportierten, tatsaechlich
 *     empfangenen Felder.
 * ==========================================================================*/

/**
 * Herkunft eines angezeigten Geraetenamens.
 *
 * Die Reihenfolge der Enum-Werte ist gleichzeitig die Prioritaet:
 * ein echter GATT-Geraetename ist staerker als ein Advertisement-Name.
 */
enum class DeviceNameSource {
    /** Kein Name verfuegbar — weder GATT noch Advertisement. */
    NONE,

    /** Name stammt aus dem Advertisement (`ScanRecord.deviceName`). */
    ADVERTISEMENT,

    /** Name stammt aus dem GATT-Geraetenamen (`BluetoothDevice.name`). */
    GATT_DEVICE_NAME;

    /** true, wenn dies ein tatsaechlich empfangener Name ist. */
    val isReal: Boolean get() = this != NONE

    /** Hoeher = verlaesslicher. Wird von [ScanAggregator] genutzt. */
    val priority: Int get() = ordinal
}

/**
 * Eine einzelne, unveraenderte Advertisement-Beobachtung.
 *
 * Alle Felder sind optional bzw. koennen leer sein — genau das ist der Punkt:
 * "nicht vorhanden" ist ein legitimer Zustand und wird nicht durch einen
 * Platzhalter-String ersetzt.
 *
 * @param address           Geraeteadresse (nur als Schluessel, nie als
 *                          Hersteller-Hinweis verwendet).
 * @param gattDeviceName    `BluetoothDevice.name` — auf Android 12+ nur mit
 *                          BLUETOOTH_CONNECT lesbar, daher oft null.
 * @param advertisedName    `ScanRecord.deviceName` aus dem Advertisement.
 * @param serviceUuids      beworbene Service-UUIDs, bereits normalisiert
 *                          (klein, 128-Bit-Form) durch [normalizeUuid].
 * @param manufacturerIds   Company Identifiers aus Manufacturer Specific Data
 *                          (0xFF). Leer, wenn das Geraet keine sendet.
 * @param serviceDataUuids  Service-UUIDs, zu denen Service Data (0x16) vorliegt.
 * @param rssi              Empfangspegel in dBm.
 * @param timestampMs       Zeitpunkt der Beobachtung (Monotonic Clock).
 */
data class ScanObservation(
    val address: String,
    val gattDeviceName: String?,
    val advertisedName: String?,
    val serviceUuids: List<String> = emptyList(),
    val manufacturerIds: List<Int> = emptyList(),
    val serviceDataUuids: List<String> = emptyList(),
    val rssi: Int,
    val timestampMs: Long
) {
    /** Aufgeloester Name inklusive Herkunft. */
    val resolvedName: ResolvedName get() = DeviceNameResolver.resolve(this)

    /** true, wenn das Advertisement ueberhaupt Zusatzdaten enthaelt. */
    val hasAdvertisementPayload: Boolean
        get() = serviceUuids.isNotEmpty() ||
            manufacturerIds.isNotEmpty() ||
            serviceDataUuids.isNotEmpty()

    companion object {
        /**
         * Normalisiert eine UUID auf die kanonische 128-Bit-Kleinschreibung.
         *
         * Hintergrund: Android liefert 16-Bit-UUIDs je nach Pfad als
         * "0000e003-0000-1000-8000-00805f9b34fb" oder bereits verkuerzt.
         * Ein Vergleich ohne Normalisierung wuerde Treffer verpassen.
         *
         * Unparsebare Eingaben werden unveraendert (getrimmt, klein) zurueck-
         * gegeben — es wird nichts geraten.
         */
        fun normalizeUuid(raw: String?): String? {
            val t = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
            if (t.isEmpty()) return null
            // Bereits vollstaendig?
            if (t.length == 36 && t.count { it == '-' } == 4) return t
            // 16-Bit-Kurzform, z. B. "e003" oder "0xe003".
            val hex = t.removePrefix("0x")
            if (hex.length == 4 && hex.all { it.isDigit() || it in 'a'..'f' }) {
                return "0000$hex-0000-1000-8000-00805f9b34fb"
            }
            // 32-Bit-Kurzform.
            if (hex.length == 8 && hex.all { it.isDigit() || it in 'a'..'f' }) {
                return "$hex-0000-1000-8000-00805f9b34fb"
            }
            return t
        }
    }
}

/**
 * Ergebnis der Namensaufloesung.
 *
 * [name] ist null, wenn kein echter Name vorliegt. Es gibt bewusst KEINEN
 * Platzhalter-String mehr im Datenmodell — die Anzeige entscheidet selbst,
 * wie sie "kein Name" darstellt (siehe [display]).
 */
data class ResolvedName(
    val name: String?,
    val source: DeviceNameSource
) {
    val hasName: Boolean get() = !name.isNullOrBlank()

    /**
     * Anzeigetext. [fallback] wird nur benutzt, wenn kein Name vorliegt —
     * damit ist in der UI sofort erkennbar, dass es sich um KEINEN Namen
     * handelt.
     */
    fun display(fallback: String = "Name nicht verfuegbar"): String =
        name?.takeIf { it.isNotBlank() } ?: fallback
}
