package com.syrak.scooterlab.recognition

import com.syrak.scooterlab.ScanObservation
import java.util.Locale

/* ============================================================================
 *  PHASE 17.1 — ADVERTISEMENT-MERKMALE (reine Logik)
 *  ---------------------------------------------------------------------------
 *  Strukturierte, verlustfreie Sicht auf die tatsaechlich empfangenen
 *  Advertisement-Felder. Es wird NICHTS interpretiert und NICHTS erfunden.
 *
 *  Wichtig:
 *   • Fehlende Felder sind ein legitimer Zustand (leer), kein Fehler.
 *   • Unvollstaendige Advertisements werden verarbeitet, nicht verworfen.
 *   • RSSI wird getragen, aber NIEMALS zur Modellbestimmung verwendet.
 * ==========================================================================*/

/**
 * Generische Bluetooth-SIG-UUIDs. Diese belegen hoechstens "irgendein
 * BLE-Geraet", niemals eine Marke oder ein Modell. Sie werden von der
 * Erkennung vollstaendig ignoriert.
 */
object GenericUuids {
    private val GENERIC = setOf(
        // Generic Access / Attribute
        "00001800-0000-1000-8000-00805f9b34fb",
        "00001801-0000-1000-8000-00805f9b34fb",
        // Device Information
        "0000180a-0000-1000-8000-00805f9b34fb",
        // Battery Service
        "0000180f-0000-1000-8000-00805f9b34fb",
        // HID / HID over GATT
        "00001812-0000-1000-8000-00805f9b34fb",
        // Heart Rate / Health Thermometer / Blood Pressure / Glucose
        "0000180d-0000-1000-8000-00805f9b34fb",
        "00001809-0000-1000-8000-00805f9b34fb",
        "00001810-0000-1000-8000-00805f9b34fb",
        "00001808-0000-1000-8000-00805f9b34fb",
        // Running Speed and Cadence / Cycling Speed and Cadence
        "00001814-0000-1000-8000-00805f9b34fb",
        "00001816-0000-1000-8000-00805f9b34fb",
        // Environmental Sensing / User Data / Weight Scale
        "0000181a-0000-1000-8000-00805f9b34fb",
        "0000181c-0000-1000-8000-00805f9b34fb",
        "0000181d-0000-1000-8000-00805f9b34fb",
        // CCCD (Descriptor, aber taucht gelegentlich in Listen auf)
        "00002902-0000-1000-8000-00805f9b34fb"
    )

    /** true, wenn die UUID generisch ist und daher kein Modellbeweis sein darf. */
    fun isGeneric(uuid: String): Boolean =
        GENERIC.contains(normalize(uuid))

    private fun normalize(raw: String): String =
        ScanObservation.normalizeUuid(raw) ?: raw.trim().lowercase(Locale.ROOT)
}

/**
 * Die tatsaechlich verfuegbaren Advertisement-Merkmale eines Geraets.
 *
 * @param deviceName aufgeloester, echter Name (null = kein Name vorhanden).
 * @param rssi Empfangspegel. Wird nur angezeigt, nie bewertet.
 * @param serviceUuids vollstaendige UND unvollstaendige Service-UUID-Listen,
 *   normalisiert und dedupliziert.
 * @param manufacturerIds Company Identifiers aus Manufacturer Specific Data.
 * @param serviceDataUuids Service-UUIDs, zu denen Service Data vorliegt.
 */
data class AdvertisementFeatures(
    val address: String,
    val deviceName: String?,
    val rssi: Int?,
    val serviceUuids: List<String> = emptyList(),
    val manufacturerIds: List<Int> = emptyList(),
    val serviceDataUuids: List<String> = emptyList()
) {
    /** true, wenn ueberhaupt ein echter Name vorliegt. */
    val hasName: Boolean get() = !deviceName.isNullOrBlank()

    /** true, wenn Manufacturer Specific Data vorhanden ist. */
    val hasManufacturerData: Boolean get() = manufacturerIds.isNotEmpty()

    /** true, wenn Service Data vorhanden ist. */
    val hasServiceData: Boolean get() = serviceDataUuids.isNotEmpty()

    /** true, wenn irgendein Advertisement-Payload vorhanden ist. */
    val hasAnyPayload: Boolean
        get() = serviceUuids.isNotEmpty() || manufacturerIds.isNotEmpty() || serviceDataUuids.isNotEmpty()

    /** true, wenn ausser Name und RSSI nichts vorhanden ist. */
    val isNameOnly: Boolean get() = hasName && !hasAnyPayload

    /** true, wenn gar keine verwertbaren Merkmale vorliegen. */
    val isEmpty: Boolean get() = !hasName && !hasAnyPayload

    /** Nicht-generische Service-UUIDs (die einzigen, die zaehlen duerfen). */
    val specificServiceUuids: List<String>
        get() = serviceUuids.filterNot { GenericUuids.isGeneric(it) }

    /** Nicht-generische Service-Data-UUIDs. */
    val specificServiceDataUuids: List<String>
        get() = serviceDataUuids.filterNot { GenericUuids.isGeneric(it) }

    /** Menschenlesbare Kurzfassung fuer die UI/den Export. */
    fun summary(): String = buildString {
        append(if (hasName) "Name vorhanden" else "kein Name")
        append(" · Services: ").append(serviceUuids.size)
        append(" · Manufacturer-IDs: ").append(manufacturerIds.size)
        append(" · Service-Data: ").append(serviceDataUuids.size)
    }

    companion object {
        /** Baut die Merkmale aus einer rohen [ScanObservation]. */
        fun from(observation: ScanObservation): AdvertisementFeatures =
            AdvertisementFeatures(
                address = observation.address,
                deviceName = observation.resolvedName.name,
                rssi = observation.rssi,
                serviceUuids = normalizeList(observation.serviceUuids),
                manufacturerIds = observation.manufacturerIds.distinct().sorted(),
                serviceDataUuids = normalizeList(observation.serviceDataUuids)
            )

        /** Direktkonstruktion fuer Tests und UI-Pfade ohne ScanObservation. */
        fun of(
            address: String,
            deviceName: String?,
            rssi: Int? = null,
            serviceUuids: List<String> = emptyList(),
            manufacturerIds: List<Int> = emptyList(),
            serviceDataUuids: List<String> = emptyList()
        ): AdvertisementFeatures = AdvertisementFeatures(
            address = address,
            deviceName = deviceName?.takeIf { it.isNotBlank() },
            rssi = rssi,
            serviceUuids = normalizeList(serviceUuids),
            manufacturerIds = manufacturerIds.distinct().sorted(),
            serviceDataUuids = normalizeList(serviceDataUuids)
        )

        /** Normalisiert, entfernt Leere und dedupliziert unter Beibehaltung der Reihenfolge. */
        fun normalizeList(raw: List<String>): List<String> =
            raw.mapNotNull { ScanObservation.normalizeUuid(it) }.distinct()
    }
}
