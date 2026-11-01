package com.syrak.scooterlab.recognition

import com.syrak.scooterlab.ScanObservation
import com.syrak.scooterlab.diagnostics.GattProperty
import com.syrak.scooterlab.diagnostics.GattServiceRecord

/* ============================================================================
 *  PHASE 17.1 — GATT-FINGERPRINT (reine Logik)
 *  ---------------------------------------------------------------------------
 *  Strukturierte Erfassung dessen, was GATT tatsaechlich gemeldet hat.
 *
 *  BINDEND (aus dem Auftrag):
 *   • Properties beschreiben ausschliesslich technische Faehigkeiten
 *     (READ/WRITE/NOTIFY/INDICATE), NIEMALS Protokollrollen.
 *   • Eine NOTIFY-Characteristic ist NICHT automatisch eine Speed-/Battery-/
 *     Telemetrie-Characteristic.
 *   • Es werden keine unbekannten BLE-Schreibbefehle abgeleitet.
 *   • Unvollstaendige Discovery wird als solche markiert, nicht als leer
 *     interpretiert.
 * ==========================================================================*/

/** Ergebnis der GATT-Service-Discovery. */
enum class GattDiscoveryStatus {
    /** Noch keine Verbindung / keine Discovery versucht. */
    NOT_ATTEMPTED,

    /** Discovery erfolgreich abgeschlossen. */
    COMPLETE,

    /** Discovery lief, lieferte aber unvollstaendige Daten. */
    INCOMPLETE,

    /** Discovery ist fehlgeschlagen. */
    FAILED;

    val displayName: String
        get() = when (this) {
            NOT_ATTEMPTED -> "nicht versucht"
            COMPLETE -> "vollständig"
            INCOMPLETE -> "unvollständig"
            FAILED -> "fehlgeschlagen"
        }
}

/** Eine Characteristic mit ihren real gemeldeten Properties. */
data class GattCharacteristicFeature(
    val uuid: String,
    val properties: Set<GattProperty> = emptySet(),
    val userDescription: String? = null
) {
    val readable: Boolean get() = GattProperty.READ in properties
    val writable: Boolean
        get() = GattProperty.WRITE in properties || GattProperty.WRITE_NO_RESPONSE in properties
    val notifiable: Boolean
        get() = GattProperty.NOTIFY in properties || GattProperty.INDICATE in properties

    /** Stabil sortierte Property-Liste, z. B. "READ|NOTIFY". */
    fun propertyLabel(): String =
        if (properties.isEmpty()) "—"
        else properties.sortedBy { it.ordinal }.joinToString("|") { it.name }
}

/** Ein Service mit seinen Characteristics. */
data class GattServiceFeature(
    val uuid: String,
    val label: String = "",
    val characteristics: List<GattCharacteristicFeature> = emptyList()
)

/**
 * Gesamter GATT-Fingerprint eines Geraets.
 *
 * @param discoveryStatus echter Discovery-Status; [GattDiscoveryStatus.NOT_ATTEMPTED]
 *   bedeutet ausdruecklich "keine Daten", nicht "keine Services vorhanden".
 * @param errors waehrend der Discovery aufgetretene Fehler (Rohtext).
 */
data class GattFeatures(
    val services: List<GattServiceFeature> = emptyList(),
    val discoveryStatus: GattDiscoveryStatus = GattDiscoveryStatus.NOT_ATTEMPTED,
    val errors: List<String> = emptyList()
) {
    /** Alle Service-UUIDs (normalisiert). */
    val serviceUuids: List<String>
        get() = services.map { it.uuid }.distinct()

    /** Alle Characteristic-UUIDs (normalisiert). */
    val characteristicUuids: List<String>
        get() = services.flatMap { s -> s.characteristics.map { it.uuid } }.distinct()

    /** Anzahl aller Characteristics. */
    val characteristicCount: Int get() = services.sumOf { it.characteristics.size }

    /** true, wenn tatsaechlich GATT-Daten vorliegen. */
    val hasAnyData: Boolean get() = services.isNotEmpty()

    /** true, wenn die Discovery unvollstaendig oder fehlgeschlagen ist. */
    val isIncomplete: Boolean
        get() = discoveryStatus == GattDiscoveryStatus.INCOMPLETE ||
            discoveryStatus == GattDiscoveryStatus.FAILED

    /** Nicht-generische Service-UUIDs. */
    val specificServiceUuids: List<String>
        get() = serviceUuids.filterNot { GenericUuids.isGeneric(it) }

    /** Sucht eine Characteristic per UUID (normalisiert). */
    fun characteristic(uuid: String): GattCharacteristicFeature? {
        val target = ScanObservation.normalizeUuid(uuid) ?: uuid
        return services.flatMap { it.characteristics }.firstOrNull { it.uuid == target }
    }

    companion object {
        /** Baut den Fingerprint aus echten GATT-Records. */
        fun from(
            records: List<GattServiceRecord>,
            discoveryStatus: GattDiscoveryStatus = GattDiscoveryStatus.COMPLETE,
            errors: List<String> = emptyList()
        ): GattFeatures = GattFeatures(
            services = records.map { service ->
                GattServiceFeature(
                    uuid = ScanObservation.normalizeUuid(service.uuid) ?: service.uuid,
                    label = service.label,
                    characteristics = service.characteristics.map { ch ->
                        GattCharacteristicFeature(
                            uuid = ScanObservation.normalizeUuid(ch.uuid) ?: ch.uuid,
                            properties = ch.properties,
                            userDescription = ch.userDescription
                        )
                    }
                )
            },
            discoveryStatus = discoveryStatus,
            errors = errors.distinct()
        )

        /** Explizit "keine Discovery versucht". */
        fun notAttempted(): GattFeatures = GattFeatures()

        /** Explizit fehlgeschlagene Discovery mit Fehlertext. */
        fun failed(error: String): GattFeatures = GattFeatures(
            discoveryStatus = GattDiscoveryStatus.FAILED,
            errors = listOf(error)
        )
    }
}
