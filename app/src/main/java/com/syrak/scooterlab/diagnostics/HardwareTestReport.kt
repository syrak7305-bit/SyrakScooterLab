package com.syrak.scooterlab.diagnostics

import com.syrak.scooterlab.recognition.RecognitionExport

/* ============================================================================
 *  PHASE 11 — HARDWARE-TEST- UND BLE-DIAGNOSE-EXPORT
 *  ---------------------------------------------------------------------------
 *  Ziel: Reale Tests an echten E-Scootern nachvollziehbar und exportierbar
 *  machen. Diese Datei ist bewusst REINES KOTLIN (keine Android-Abhaengigkeiten),
 *  damit sie vollstaendig unit-testbar ist.
 *
 *  WICHTIG — Ehrlichkeitsprinzip:
 *  Es werden ausschliesslich Daten erfasst, die tatsaechlich beobachtet wurden
 *  (Scan-Ergebnisse, GATT-Baum, empfangene/gesendete Frames, Fehler, Zeit-
 *  stempel). Es werden KEINE Protokolle, UUIDs, Kommandos oder Decoder-Offsets
 *  erfunden. Ein erkannter Hersteller bedeutet NICHT automatisch funktionierende
 *  Protokoll-Unterstuetzung — das wird im Feld `supportStatus` getrennt gefuehrt.
 * ==========================================================================*/

/** BLE-Characteristic-Properties, wie sie im GATT tatsaechlich gemeldet werden. */
enum class GattProperty {
    READ,
    WRITE,
    WRITE_NO_RESPONSE,
    NOTIFY,
    INDICATE,
    BROADCAST,
    SIGNED_WRITE,
    EXTENDED_PROPERTIES,
    UNKNOWN
}

/** Eine einzelne Characteristic inklusive der real gemeldeten Properties. */
data class GattCharacteristicRecord(
    val uuid: String,
    val properties: Set<GattProperty> = emptySet(),
    val userDescription: String? = null
) {
    val readable: Boolean get() = GattProperty.READ in properties
    val writable: Boolean
        get() = GattProperty.WRITE in properties || GattProperty.WRITE_NO_RESPONSE in properties
    val notifiable: Boolean
        get() = GattProperty.NOTIFY in properties || GattProperty.INDICATE in properties

    /** Stabil sortierte, lesbare Property-Liste (z. B. "READ|WRITE|NOTIFY"). */
    fun propertyLabel(): String =
        if (properties.isEmpty()) "—"
        else properties.sortedBy { it.ordinal }.joinToString("|") { it.name }

    /** Kurzform fuer die UI: R/W/N/I. */
    fun shortFlags(): String = buildString {
        if (readable) append('R') else append('-')
        if (writable) append('W') else append('-')
        if (GattProperty.NOTIFY in properties) append('N') else append('-')
        if (GattProperty.INDICATE in properties) append('I') else append('-')
    }
}

/** Ein GATT-Service mit seinen Characteristics. */
data class GattServiceRecord(
    val uuid: String,
    val label: String = "",
    val characteristics: List<GattCharacteristicRecord> = emptyList()
)

/** Ein beim Scan tatsaechlich gesehenes Geraet. */
data class ScanRecord(
    val name: String?,
    val address: String,
    val rssi: Int,
    val brand: String,
    val detectedModel: String,
    val confidence: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val seenCount: Int
) {
    val durationMs: Long get() = (lastSeenMs - firstSeenMs).coerceAtLeast(0L)
}

enum class FrameDirection { TX, RX }

/** Ein gesendeter oder empfangener Frame mit Zeitstempel. */
data class FrameRecord(
    val direction: FrameDirection,
    val hex: String,
    val atMs: Long,
    val label: String = "",
    /**
     * PHASE 16.3.4: UUID der Characteristic, auf der ein RX-Frame empfangen
     * wurde (nur UUID, keine MAC, kein Roh-Frame). null bei TX oder wenn die
     * Quelle unbekannt ist. Belegt, welcher Kanal tatsaechlich Daten liefert.
     */
    val characteristicUuid: String? = null
)

enum class HardwareErrorKind {
    CONNECT_TIMEOUT,
    DISCOVERY_TIMEOUT,
    DISCONNECT,
    WRITE_FAILED,
    NOTIFY_FAILED,
    PARSE_ERROR,
    PERMISSION,
    OTHER
}

/** Ein beobachteter Fehler / Timeout / Disconnect. */
data class HardwareErrorRecord(
    val kind: HardwareErrorKind,
    val message: String,
    val atMs: Long
)

/**
 * PHASE 16: Ein tatsaechlich durchgefuehrter Characteristic-Read.
 * `success` ist NUR true, wenn ein echter Callback mit GATT_SUCCESS und
 * mindestens einem Byte vorlag. Eine vorhandene Read-Property ist KEIN Read.
 */
data class ReadRecord(
    val uuid: String,
    val status: String,
    val success: Boolean,
    val byteCount: Int,
    val durationMs: Long,
    val atMs: Long,
    val detail: String = ""
)

/** PHASE 16: Ein einzelner RSSI-Messwert der Verbindung. */
data class RssiSample(
    val rssi: Int,
    val atMs: Long
)

/** Verbindungsverlauf einer Sitzung. */
data class ConnectionRecord(
    val connectedAtMs: Long? = null,
    val readyAtMs: Long? = null,
    val disconnectedAtMs: Long? = null,
    val mtu: Int = 0,
    val protocol: String = "",
    val adapterId: String = "",
    val disconnectUnexpected: Boolean = false,
    /**
     * PHASE 16.3.4: GATT-Disconnect-Statuscode aus onConnectionStateChange
     * (0 = GATT_SUCCESS, sonst Fehlercode). null = kein Disconnect beobachtet.
     * Wird unveraendert gespeichert; keine Interpretation/Umdeutung.
     */
    val disconnectStatus: Int? = null
) {
    /** Zeit von Verbindungsaufbau bis "ready" (Service-Discovery + MTU). */
    val readyLatencyMs: Long?
        get() {
            val c = connectedAtMs ?: return null
            val r = readyAtMs ?: return null
            return if (r >= c) r - c else null
        }

    /** Sitzungsdauer bis Disconnect (oder null, wenn noch verbunden). */
    val sessionDurationMs: Long?
        get() {
            val c = connectedAtMs ?: return null
            val d = disconnectedAtMs ?: return null
            return if (d >= c) d - c else null
        }
}

/**
 * PHASE 16.3.1: strikt getrennte Geraeteidentitaet.
 *
 * [activeConnection] ist NUR true, wenn eine echte GATT-Verbindung besteht.
 * Ein Scan-Treffer oder ein zuletzt genutztes Geraet ist KEINE aktive
 * Verbindung. [lastUsed*] beschreibt ausschliesslich die letzte erfolgreiche
 * Sitzung und wird nie als aktive Identitaet ausgegeben.
 */
data class DeviceIdentityRecord(
    val activeConnection: Boolean = false,
    val activeName: String? = null,
    val activeAddress: String? = null,
    val lastUsedName: String? = null,
    val lastUsedAddress: String? = null
)

/**
 * PHASE 16.3.1: vollstaendige Notify-Aktivierungs-Kette einer Verbindung.
 *
 * Die sieben Schritte sind bewusst getrennt:
 *  1. lokale Registrierung versucht ([localRegistrationAttempted])
 *  2. lokale Registrierung erfolgreich ([localRegistrationSucceeded])
 *  3. CCCD vorhanden ([cccdPresent])
 *  4. CCCD-Write angefordert ([cccdWriteRequested])
 *  5. CCCD-Write lokal angenommen ([cccdWriteAccepted])
 *  6. Descriptor-Callback empfangen ([descriptorCallbackReceived])
 *  7. Descriptor-Callback erfolgreich ([descriptorCallbackSuccess])
 * plus die tatsaechlich empfangenen Notifications ([notificationsReceived]).
 */
data class NotifyDiagnosticsRecord(
    val selectedNotifyCharUuid: String = "",
    val selectedNotifyCharFound: Boolean = false,
    val anyNotifiablePresent: Boolean = false,
    val localRegistrationAttempted: Boolean = false,
    val localRegistrationSucceeded: Boolean = false,
    val cccdPresent: Boolean = false,
    val cccdWriteRequested: Boolean = false,
    val cccdWriteAccepted: Boolean = false,
    val descriptorCallbackReceived: Boolean = false,
    val descriptorCallbackSuccess: Boolean = false,
    val notificationsReceived: Long = 0,
    val notifyActive: Boolean = false,
    val issue: String? = null,
    /**
     * PHASE 16.3.4: Empfangszähler je Characteristic-UUID. Leer, solange keine
     * Notification mit bekannter UUID empfangen wurde. Belegt, WELCHER Kanal
     * tatsaechlich Daten liefert \u2014 ohne eine Rolle zu unterstellen.
     */
    val notificationsByUuid: Map<String, Long> = emptyMap()
) {
    /** true NUR, wenn lokale UND entfernte Aktivierung belegt sind. */
    val chainComplete: Boolean
        get() = localRegistrationSucceeded && cccdWriteAccepted && descriptorCallbackSuccess
}

/**
 * PHASE 16.3.1: reine, testbare Aufloesung der Geraeteidentitaet.
 *
 * Ein Scan-Treffer darf NIEMALS als aktive GATT-Verbindung erscheinen. Die
 * aktive Identitaet stammt ausschliesslich aus der realen Verbindung; fehlt
 * sie, wird sie als unbekannt ("—") gekennzeichnet. Der GATT-Schnappschuss
 * wird nur verwendet, wenn er zu DERSELBEN Adresse gehoert wie die aktive
 * Verbindung.
 */
object HardwareReportIdentity {

    const val UNKNOWN_ADDRESS = "—"

    data class Inputs(
        val activeAddress: String?,
        val activeName: String?,
        val connected: Boolean,
        val lastUsedAddress: String?,
        val lastUsedName: String?,
        val cachedGattAddress: String?,
        val hasCachedGatt: Boolean
    )

    data class Result(
        val deviceName: String?,
        val address: String,
        val identity: DeviceIdentityRecord,
        val useCachedGatt: Boolean
    )

    fun resolve(i: Inputs): Result {
        val activeAddr = i.activeAddress?.takeIf { it.isNotBlank() && i.connected }
        val activeName = if (activeAddr != null) i.activeName?.takeIf { it.isNotBlank() } else null
        val identity = DeviceIdentityRecord(
            activeConnection = activeAddr != null,
            activeName = activeName,
            activeAddress = activeAddr,
            lastUsedName = i.lastUsedName?.takeIf { it.isNotBlank() },
            lastUsedAddress = i.lastUsedAddress?.takeIf { it.isNotBlank() }
        )
        val useGatt = activeAddr != null && i.hasCachedGatt &&
            i.cachedGattAddress != null && i.cachedGattAddress == activeAddr
        return Result(
            deviceName = activeName,
            address = activeAddr ?: UNKNOWN_ADDRESS,
            identity = identity,
            useCachedGatt = useGatt
        )
    }
}

/**
 * Vollstaendiger, exportierbarer Hardware-Testbericht einer Sitzung.
 *
 * @param readOnly true, wenn fuer dieses Geraet ausschliesslich gelesen wurde /
 *   gelesen werden durfte (unbekanntes Modell oder nicht verifizierte Hardware).
 */
data class HardwareTestReport(
    val schemaVersion: Int = SCHEMA_VERSION,
    val generatedAtEpochMs: Long,
    val appVersion: String,
    val deviceName: String?,
    val address: String,
    val manufacturer: String,
    val detectedModel: String,
    val confidence: String,
    val supportStatus: String,
    val readOnly: Boolean,
    val protocol: String,
    val adapterId: String,
    val connection: ConnectionRecord = ConnectionRecord(),
    /** PHASE 16.3.1: aktive vs. zuletzt genutzte Identitaet (strikt getrennt). */
    val identity: DeviceIdentityRecord? = null,
    /** PHASE 16.3.1: vollstaendige Notify-Diagnose der aktuellen Verbindung. */
    val notifyDiagnostics: NotifyDiagnosticsRecord? = null,
    val scanResults: List<ScanRecord> = emptyList(),
    val services: List<GattServiceRecord> = emptyList(),
    val frames: List<FrameRecord> = emptyList(),
    val errors: List<HardwareErrorRecord> = emptyList(),
    /** PHASE 16: durchgefuehrte Reads (Erfolg/Fehler/Timeout). */
    val reads: List<ReadRecord> = emptyList(),
    /** PHASE 16: RSSI-Zeitreihe waehrend der Sitzung. */
    val rssiSamples: List<RssiSample> = emptyList(),
    val notes: List<String> = emptyList(),
    /**
     * PHASE 17.1: getrennte Erkennungsdiagnose (Nutzerprofil vs.
     * Hardware-Erkennung, Advertisement, GATT, Begruendung). Additiv — der
     * bestehende `schemaVersion = 1` bleibt gueltig.
     */
    val recognition: RecognitionExport? = null
) {
    /** Anzahl erfolgreicher Reads (echter Callback mit Bytes). */
    val successfulReads: Int get() = reads.count { it.success }
    /** Anzahl fehlgeschlagener Reads (GATT-Fehler/leer). */
    val failedReads: Int get() = reads.count { !it.success && it.status != "TIMEOUT" }
    /** Anzahl Read-Timeouts. */
    val readTimeouts: Int get() = reads.count { it.status == "TIMEOUT" }

    /* ---------------------------- JSON ---------------------------- */

    fun toJson(): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": ").append(schemaVersion).append(",\n")
        append("  \"generatedAtEpochMs\": ").append(generatedAtEpochMs).append(",\n")
        append("  \"appVersion\": ").append(Json.quote(appVersion)).append(",\n")
        append("  \"device\": {\n")
        append("    \"name\": ").append(Json.quoteOrNull(deviceName)).append(",\n")
        append("    \"address\": ").append(Json.quote(address)).append(",\n")
        append("    \"manufacturer\": ").append(Json.quote(manufacturer)).append(",\n")
        append("    \"detectedModel\": ").append(Json.quote(detectedModel)).append(",\n")
        append("    \"confidence\": ").append(Json.quote(confidence)).append(",\n")
        append("    \"supportStatus\": ").append(Json.quote(supportStatus)).append(",\n")
        append("    \"readOnly\": ").append(readOnly).append(",\n")
        append("    \"protocol\": ").append(Json.quote(protocol)).append(",\n")
        append("    \"adapterId\": ").append(Json.quote(adapterId)).append("\n")
        append("  },\n")

        append("  \"connection\": {\n")
        append("    \"connectedAtMs\": ").append(connection.connectedAtMs ?: "null").append(",\n")
        append("    \"readyAtMs\": ").append(connection.readyAtMs ?: "null").append(",\n")
        append("    \"disconnectedAtMs\": ").append(connection.disconnectedAtMs ?: "null").append(",\n")
        append("    \"readyLatencyMs\": ").append(connection.readyLatencyMs ?: "null").append(",\n")
        append("    \"sessionDurationMs\": ").append(connection.sessionDurationMs ?: "null").append(",\n")
        append("    \"mtu\": ").append(connection.mtu).append(",\n")
        append("    \"protocol\": ").append(Json.quote(connection.protocol)).append(",\n")
        append("    \"adapterId\": ").append(Json.quote(connection.adapterId)).append(",\n")
        append("    \"disconnectUnexpected\": ").append(connection.disconnectUnexpected).append(",\n")
        append("    \"disconnectStatus\": ").append(connection.disconnectStatus ?: "null").append("\n")
        append("  },\n")

        append("  \"identity\": ")
        val id = identity
        if (id == null) {
            append("null,\n")
        } else {
            append("{\n")
            append("    \"activeConnection\": ").append(id.activeConnection).append(",\n")
            append("    \"activeName\": ").append(Json.quoteOrNull(id.activeName)).append(",\n")
            append("    \"activeAddress\": ").append(Json.quoteOrNull(id.activeAddress)).append(",\n")
            append("    \"lastUsedName\": ").append(Json.quoteOrNull(id.lastUsedName)).append(",\n")
            append("    \"lastUsedAddress\": ").append(Json.quoteOrNull(id.lastUsedAddress)).append("\n")
            append("  },\n")
        }

        append("  \"notifyDiagnostics\": ")
        val nd = notifyDiagnostics
        if (nd == null) {
            append("null,\n")
        } else {
            append("{\n")
            append("    \"selectedNotifyCharUuid\": ").append(Json.quote(nd.selectedNotifyCharUuid)).append(",\n")
            append("    \"selectedNotifyCharFound\": ").append(nd.selectedNotifyCharFound).append(",\n")
            append("    \"anyNotifiablePresent\": ").append(nd.anyNotifiablePresent).append(",\n")
            append("    \"localRegistrationAttempted\": ").append(nd.localRegistrationAttempted).append(",\n")
            append("    \"localRegistrationSucceeded\": ").append(nd.localRegistrationSucceeded).append(",\n")
            append("    \"cccdPresent\": ").append(nd.cccdPresent).append(",\n")
            append("    \"cccdWriteRequested\": ").append(nd.cccdWriteRequested).append(",\n")
            append("    \"cccdWriteAccepted\": ").append(nd.cccdWriteAccepted).append(",\n")
            append("    \"descriptorCallbackReceived\": ").append(nd.descriptorCallbackReceived).append(",\n")
            append("    \"descriptorCallbackSuccess\": ").append(nd.descriptorCallbackSuccess).append(",\n")
            append("    \"notificationsReceived\": ").append(nd.notificationsReceived).append(",\n")
            append("    \"notificationsByUuid\": {")
            nd.notificationsByUuid.entries.forEachIndexed { i, e ->
                if (i > 0) append(", ")
                append(Json.quote(e.key)).append(": ").append(e.value)
            }
            append("},\n")
            append("    \"notifyActive\": ").append(nd.notifyActive).append(",\n")
            append("    \"chainComplete\": ").append(nd.chainComplete).append(",\n")
            append("    \"issue\": ").append(Json.quoteOrNull(nd.issue)).append("\n")
            append("  },\n")
        }

        append("  \"scanResults\": [")
        scanResults.forEachIndexed { i, s ->
            append(if (i == 0) "\n" else ",\n")
            append("    {")
            append("\"name\": ").append(Json.quoteOrNull(s.name)).append(", ")
            append("\"address\": ").append(Json.quote(s.address)).append(", ")
            append("\"rssi\": ").append(s.rssi).append(", ")
            append("\"brand\": ").append(Json.quote(s.brand)).append(", ")
            append("\"detectedModel\": ").append(Json.quote(s.detectedModel)).append(", ")
            append("\"confidence\": ").append(Json.quote(s.confidence)).append(", ")
            append("\"firstSeenMs\": ").append(s.firstSeenMs).append(", ")
            append("\"lastSeenMs\": ").append(s.lastSeenMs).append(", ")
            append("\"seenCount\": ").append(s.seenCount)
            append("}")
        }
        append(if (scanResults.isEmpty()) "],\n" else "\n  ],\n")

        append("  \"services\": [")
        services.forEachIndexed { i, svc ->
            append(if (i == 0) "\n" else ",\n")
            append("    {\"uuid\": ").append(Json.quote(svc.uuid))
            append(", \"label\": ").append(Json.quote(svc.label))
            append(", \"characteristics\": [")
            svc.characteristics.forEachIndexed { j, c ->
                append(if (j == 0) "" else ", ")
                append("{\"uuid\": ").append(Json.quote(c.uuid))
                append(", \"properties\": ").append(Json.stringArray(c.properties.sortedBy { it.ordinal }.map { it.name }))
                append(", \"userDescription\": ").append(Json.quoteOrNull(c.userDescription))
                append("}")
            }
            append("]}")
        }
        append(if (services.isEmpty()) "],\n" else "\n  ],\n")

        append("  \"frames\": [")
        frames.forEachIndexed { i, f ->
            append(if (i == 0) "\n" else ",\n")
            append("    {\"direction\": ").append(Json.quote(f.direction.name))
            append(", \"hex\": ").append(Json.quote(f.hex))
            append(", \"atMs\": ").append(f.atMs)
            append(", \"label\": ").append(Json.quote(f.label))
            append(", \"characteristicUuid\": ").append(Json.quoteOrNull(f.characteristicUuid))
            append("}")
        }
        append(if (frames.isEmpty()) "],\n" else "\n  ],\n")

        append("  \"errors\": [")
        errors.forEachIndexed { i, e ->
            append(if (i == 0) "\n" else ",\n")
            append("    {\"kind\": ").append(Json.quote(e.kind.name))
            append(", \"message\": ").append(Json.quote(e.message))
            append(", \"atMs\": ").append(e.atMs)
            append("}")
        }
        append(if (errors.isEmpty()) "],\n" else "\n  ],\n")

        append("  \"reads\": [")
        reads.forEachIndexed { i, r ->
            append(if (i == 0) "\n" else ",\n")
            append("    {\"uuid\": ").append(Json.quote(r.uuid))
            append(", \"status\": ").append(Json.quote(r.status))
            append(", \"success\": ").append(r.success)
            append(", \"byteCount\": ").append(r.byteCount)
            append(", \"durationMs\": ").append(r.durationMs)
            append(", \"atMs\": ").append(r.atMs)
            append(", \"detail\": ").append(Json.quote(r.detail))
            append("}")
        }
        append(if (reads.isEmpty()) "],\n" else "\n  ],\n")

        append("  \"rssiSeries\": [")
        rssiSamples.forEachIndexed { i, r ->
            append(if (i == 0) "\n" else ",\n")
            append("    {\"rssi\": ").append(r.rssi)
            append(", \"atMs\": ").append(r.atMs)
            append("}")
        }
        append(if (rssiSamples.isEmpty()) "],\n" else "\n  ],\n")

        append("  \"notes\": ").append(Json.stringArray(notes)).append(",\n")

        append("  \"recognition\": ")
        val rec = recognition
        if (rec == null) {
            append("null\n")
        } else {
            append("{\n")
            append("    \"userProfile\": {\n")
            append("      \"brand\": ").append(Json.quote(rec.userProfileBrand)).append(",\n")
            append("      \"model\": ").append(Json.quote(rec.userProfileModel)).append(",\n")
            append("      \"modelCode\": ").append(Json.quote(rec.userProfileModelCode)).append(",\n")
            append("      \"modelNotListed\": ").append(rec.userProfileModelNotListed).append(",\n")
            append("      \"identificationConfidence\": ")
                .append(Json.quote(rec.userProfileIdentificationConfidence)).append("\n")
            append("    },\n")
            append("    \"hardwareDetection\": {\n")
            append("      \"deviceName\": ").append(Json.quoteOrNull(rec.deviceName)).append(",\n")
            append("      \"deviceAddressMasked\": ").append(Json.quote(rec.deviceAddressMasked)).append(",\n")
            append("      \"detectedBrand\": ").append(Json.quote(rec.detectedBrand)).append(",\n")
            append("      \"detectedBrandFamily\": ").append(Json.quote(rec.detectedBrandFamily)).append(",\n")
            append("      \"detectedModelFamily\": ").append(Json.quoteOrNull(rec.detectedModelFamily)).append(",\n")
            append("      \"confirmedModel\": ").append(Json.quoteOrNull(rec.confirmedModel)).append(",\n")
            append("      \"probableModel\": ").append(Json.quoteOrNull(rec.probableModel)).append(",\n")
            append("      \"confidence\": ").append(Json.quote(rec.confidence)).append(",\n")
            append("      \"matchedProfileId\": ").append(Json.quoteOrNull(rec.matchedProfileId)).append("\n")
            append("    },\n")
            append("    \"advertisement\": {\n")
            append("      \"serviceUuids\": ").append(Json.stringArray(rec.advertisementServiceUuids)).append(",\n")
            append("      \"manufacturerIds\": ").append(Json.stringArray(rec.advertisementManufacturerIds)).append(",\n")
            append("      \"serviceDataUuids\": ").append(Json.stringArray(rec.advertisementServiceDataUuids)).append(",\n")
            append("      \"rssi\": ").append(rec.advertisementRssi ?: "null").append("\n")
            append("    },\n")
            append("    \"gatt\": {\n")
            append("      \"discoveryStatus\": ").append(Json.quote(rec.gattDiscoveryStatus)).append(",\n")
            append("      \"serviceUuids\": ").append(Json.stringArray(rec.gattServiceUuids)).append(",\n")
            append("      \"characteristicCount\": ").append(rec.gattCharacteristicCount).append(",\n")
            append("      \"characteristics\": ").append(Json.stringArray(rec.gattCharacteristics)).append("\n")
            append("    },\n")
            append("    \"reasoning\": {\n")
            append("      \"reasons\": ").append(Json.stringArray(rec.reasons)).append(",\n")
            append("      \"matchedFeatures\": ").append(Json.stringArray(rec.matchedFeatures)).append(",\n")
            append("      \"missingFeatures\": ").append(Json.stringArray(rec.missingFeatures)).append(",\n")
            append("      \"contradictoryFeatures\": ").append(Json.stringArray(rec.contradictoryFeatures)).append(",\n")
            append("      \"unknownFeatures\": ").append(Json.stringArray(rec.unknownFeatures)).append(",\n")
            append("      \"errors\": ").append(Json.stringArray(rec.errors)).append("\n")
            append("    },\n")
            // PHASE 17.2: getrennter Vergleich Scan -> GATT.
            append("    \"comparison\": {\n")
            append("      \"scanConfidence\": ").append(Json.quoteOrNull(rec.scanConfidence)).append(",\n")
            append("      \"finalConfidence\": ").append(Json.quoteOrNull(rec.finalConfidence)).append(",\n")
            append("      \"gattDiscoveryStatus\": ").append(Json.quote(rec.gattDiscoveryStatus)).append(",\n")
            append("      \"gattEvidenceAdded\": ").append(Json.stringArray(rec.gattEvidenceAdded)).append(",\n")
            append("      \"stillMissing\": ").append(Json.stringArray(rec.stillMissing)).append(",\n")
            append("      \"scanObservedAtMs\": ").append(rec.scanObservedAtMs ?: "null").append(",\n")
            append("      \"gattObservedAtMs\": ").append(rec.gattObservedAtMs ?: "null").append("\n")
            append("    }\n")
            append("  }\n")
        }
        append("}\n")
    }

    /* ---------------------------- CSV ---------------------------- */

    /**
     * Flache, maschinenlesbare CSV mit den Spalten `section,field,value`.
     * Bewusst flach gehalten, damit sie ohne Spezialparser (csvkit, Excel,
     * pandas) verarbeitet werden kann.
     */
    fun toCsv(): String = buildString {
        append("section,field,value\n")
        fun row(section: String, field: String, value: Any?) {
            append(Json.csv(section)).append(',')
            append(Json.csv(field)).append(',')
            append(Json.csv(value?.toString() ?: "")).append('\n')
        }
        row("meta", "schemaVersion", schemaVersion)
        row("meta", "generatedAtEpochMs", generatedAtEpochMs)
        row("meta", "appVersion", appVersion)
        row("device", "name", deviceName)
        row("device", "address", address)
        row("device", "manufacturer", manufacturer)
        row("device", "detectedModel", detectedModel)
        row("device", "confidence", confidence)
        row("device", "supportStatus", supportStatus)
        row("device", "readOnly", readOnly)
        row("device", "protocol", protocol)
        row("device", "adapterId", adapterId)
        recognition?.let { rec ->
            row("recognition_user", "brand", rec.userProfileBrand)
            row("recognition_user", "model", rec.userProfileModel)
            row("recognition_user", "modelCode", rec.userProfileModelCode)
            row("recognition_user", "modelNotListed", rec.userProfileModelNotListed)
            row("recognition_user", "identificationConfidence", rec.userProfileIdentificationConfidence)
            row("recognition_hw", "deviceName", rec.deviceName)
            row("recognition_hw", "deviceAddressMasked", rec.deviceAddressMasked)
            row("recognition_hw", "detectedBrand", rec.detectedBrand)
            row("recognition_hw", "detectedBrandFamily", rec.detectedBrandFamily)
            row("recognition_hw", "detectedModelFamily", rec.detectedModelFamily)
            row("recognition_hw", "confirmedModel", rec.confirmedModel)
            row("recognition_hw", "probableModel", rec.probableModel)
            row("recognition_hw", "confidence", rec.confidence)
            row("recognition_hw", "matchedProfileId", rec.matchedProfileId)
            row("recognition_adv", "serviceUuids", rec.advertisementServiceUuids.joinToString(";"))
            row("recognition_adv", "manufacturerIds", rec.advertisementManufacturerIds.joinToString(";"))
            row("recognition_adv", "serviceDataUuids", rec.advertisementServiceDataUuids.joinToString(";"))
            row("recognition_adv", "rssi", rec.advertisementRssi)
            row("recognition_gatt", "discoveryStatus", rec.gattDiscoveryStatus)
            row("recognition_gatt", "serviceUuids", rec.gattServiceUuids.joinToString(";"))
            row("recognition_gatt", "characteristicCount", rec.gattCharacteristicCount)
            row("recognition_gatt", "characteristics", rec.gattCharacteristics.joinToString(";"))
            row("recognition_reason", "reasons", rec.reasons.joinToString(";"))
            row("recognition_reason", "matchedFeatures", rec.matchedFeatures.joinToString(";"))
            row("recognition_reason", "missingFeatures", rec.missingFeatures.joinToString(";"))
            row("recognition_reason", "contradictoryFeatures", rec.contradictoryFeatures.joinToString(";"))
            row("recognition_reason", "unknownFeatures", rec.unknownFeatures.joinToString(";"))
            row("recognition_reason", "errors", rec.errors.joinToString(";"))
            // PHASE 17.2: Vergleich Scan -> GATT.
            row("recognition_compare", "scanConfidence", rec.scanConfidence)
            row("recognition_compare", "finalConfidence", rec.finalConfidence)
            row("recognition_compare", "gattDiscoveryStatus", rec.gattDiscoveryStatus)
            row("recognition_compare", "gattEvidenceAdded", rec.gattEvidenceAdded.joinToString(";"))
            row("recognition_compare", "stillMissing", rec.stillMissing.joinToString(";"))
            row("recognition_compare", "scanObservedAtMs", rec.scanObservedAtMs)
            row("recognition_compare", "gattObservedAtMs", rec.gattObservedAtMs)
        }
        row("connection", "connectedAtMs", connection.connectedAtMs)
        row("connection", "readyAtMs", connection.readyAtMs)
        row("connection", "disconnectedAtMs", connection.disconnectedAtMs)
        row("connection", "readyLatencyMs", connection.readyLatencyMs)
        row("connection", "sessionDurationMs", connection.sessionDurationMs)
        row("connection", "mtu", connection.mtu)
        row("connection", "disconnectUnexpected", connection.disconnectUnexpected)
        row("connection", "disconnectStatus", connection.disconnectStatus)
        identity?.let { id ->
            row("identity", "activeConnection", id.activeConnection)
            row("identity", "activeName", id.activeName)
            row("identity", "activeAddress", id.activeAddress)
            row("identity", "lastUsedName", id.lastUsedName)
            row("identity", "lastUsedAddress", id.lastUsedAddress)
        }
        notifyDiagnostics?.let { nd ->
            row("notify", "selectedNotifyCharUuid", nd.selectedNotifyCharUuid)
            row("notify", "selectedNotifyCharFound", nd.selectedNotifyCharFound)
            row("notify", "anyNotifiablePresent", nd.anyNotifiablePresent)
            row("notify", "localRegistrationAttempted", nd.localRegistrationAttempted)
            row("notify", "localRegistrationSucceeded", nd.localRegistrationSucceeded)
            row("notify", "cccdPresent", nd.cccdPresent)
            row("notify", "cccdWriteRequested", nd.cccdWriteRequested)
            row("notify", "cccdWriteAccepted", nd.cccdWriteAccepted)
            row("notify", "descriptorCallbackReceived", nd.descriptorCallbackReceived)
            row("notify", "descriptorCallbackSuccess", nd.descriptorCallbackSuccess)
            row("notify", "notificationsReceived", nd.notificationsReceived)
            nd.notificationsByUuid.forEach { (uuid, count) ->
                row("notify", "notificationsByUuid:$uuid", count)
            }
            row("notify", "notifyActive", nd.notifyActive)
            row("notify", "chainComplete", nd.chainComplete)
            row("notify", "issue", nd.issue)
        }
        scanResults.forEach { s ->
            row("scan", "device", "${s.address}|${s.name ?: ""}|rssi=${s.rssi}|brand=${s.brand}|model=${s.detectedModel}|conf=${s.confidence}|seen=${s.seenCount}")
        }
        services.forEach { svc ->
            row("gatt", "service", "${svc.uuid}|${svc.label}")
            svc.characteristics.forEach { c ->
                row("gatt", "characteristic", "${svc.uuid}|${c.uuid}|${c.propertyLabel()}")
            }
        }
        frames.forEach { f ->
            row("frame", f.direction.name, "${f.atMs}|${f.hex}|${f.label}|${f.characteristicUuid ?: ""}")
        }
        errors.forEach { e ->
            row("error", e.kind.name, "${e.atMs}|${e.message}")
        }
        reads.forEach { r ->
            row("read", r.uuid, "${r.atMs}|${r.status}|success=${r.success}|bytes=${r.byteCount}|dur=${r.durationMs}|${r.detail}")
        }
        rssiSamples.forEach { r ->
            row("rssi", "sample", "${r.atMs}|${r.rssi}")
        }
        notes.forEachIndexed { i, n -> row("note", "note[$i]", n) }
    }

    /* -------------------------- Summary -------------------------- */

    /** Menschenlesbare Zusammenfassung (deutsch) fuer Report/Log. */
    fun toSummary(): String = buildString {
        appendLine("SYRAK SCOOTERLAB — HARDWARE-TESTBERICHT")
        appendLine("Erzeugt: $generatedAtEpochMs (App $appVersion, Schema v$schemaVersion)")
        appendLine("Gerät: ${deviceName ?: "—"} (${address})")
        appendLine("Hersteller: $manufacturer · Modell: $detectedModel · Sicherheit: $confidence")
        appendLine("Support-Status: $supportStatus · Protokoll: $protocol · Adapter: $adapterId")
        appendLine("Modus: ${if (readOnly) "NUR LESEN (read-only)" else "Schreiben freigegeben"}")
        appendLine(
            "Verbindung: MTU ${connection.mtu} · ready-Latenz " +
                "${connection.readyLatencyMs?.let { "$it ms" } ?: "—"} · " +
                "Dauer ${connection.sessionDurationMs?.let { "$it ms" } ?: "—"}" +
                (if (connection.disconnectUnexpected) " · UNERWARTETER DISCONNECT" else "") +
                (connection.disconnectStatus?.let { " · Status=$it" } ?: "")
        )
        identity?.let { id ->
            appendLine(
                "Identitaet: aktiv=${if (id.activeConnection) (id.activeAddress ?: "\u2014") else "unbekannt"}" +
                    " \u00b7 zuletzt=${id.lastUsedAddress ?: "\u2014"}"
            )
        }
        notifyDiagnostics?.let { nd ->
            appendLine(
                "Notify: lokal=${if (nd.localRegistrationSucceeded) "ok" else if (nd.localRegistrationAttempted) "FEHLER" else "\u2014"}" +
                    " \u00b7 CCCD=${if (nd.cccdPresent) "vorhanden" else "fehlt"}" +
                    " \u00b7 write=${if (nd.cccdWriteAccepted) "ok" else if (nd.cccdWriteRequested) "abgelehnt" else "\u2014"}" +
                    " \u00b7 callback=${if (nd.descriptorCallbackSuccess) "ok" else if (nd.descriptorCallbackReceived) "FEHLER" else "\u2014"}" +
                    " \u00b7 rx=${nd.notificationsReceived} \u00b7 Kette=${if (nd.chainComplete) "vollstaendig" else "unvollstaendig"}"
            )
            if (nd.notificationsByUuid.isNotEmpty()) {
                appendLine(
                    "Notify je UUID: " + nd.notificationsByUuid.entries.joinToString(", ") {
                        "${it.key}=${it.value}"
                    }
                )
            }
        }
        appendLine("Scan-Treffer: ${scanResults.size}")
        appendLine("GATT: ${services.size} Services / ${services.sumOf { it.characteristics.size }} Characteristics")
        appendLine("Frames: ${frames.size} (TX ${frames.count { it.direction == FrameDirection.TX }}, RX ${frames.count { it.direction == FrameDirection.RX }})")
        appendLine("Fehler: ${errors.size}")
        appendLine(
            "Reads: ${reads.size} (ok ${successfulReads}, fehlgeschlagen ${failedReads}, " +
                "Timeout $readTimeouts) \u00b7 RSSI-Messwerte: ${rssiSamples.size}"
        )
        if (notes.isNotEmpty()) {
            appendLine("Hinweise:")
            notes.forEach { appendLine("  - $it") }
        }
        append("Hinweis: Ein erkannter Hersteller bedeutet NICHT automatisch validierte Protokoll-Unterstützung.")
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/**
 * Sammelt waehrend einer Sitzung Frames, Fehler und Scan-Ergebnisse mit
 * fester Obergrenze (kein unbegrenztes Wachstum im Speicher).
 */
class HardwareTestCollector(
    private val maxFrames: Int = 2000,
    private val maxErrors: Int = 200,
    private val maxReads: Int = 500,
    private val maxRssi: Int = 2000
) {
    private val frames = ArrayDeque<FrameRecord>()
    private val errors = ArrayDeque<HardwareErrorRecord>()
    private val scan = LinkedHashMap<String, ScanRecord>()
    private val reads = ArrayDeque<ReadRecord>()
    private val rssi = ArrayDeque<RssiSample>()

    fun recordFrame(
        direction: FrameDirection,
        hex: String,
        atMs: Long,
        label: String = "",
        characteristicUuid: String? = null
    ) {
        if (frames.size >= maxFrames) frames.removeFirst()
        frames.addLast(FrameRecord(direction, hex, atMs, label, characteristicUuid))
    }

    fun recordError(kind: HardwareErrorKind, message: String, atMs: Long) {
        if (errors.size >= maxErrors) errors.removeFirst()
        errors.addLast(HardwareErrorRecord(kind, message, atMs))
    }

    /** PHASE 16: erfasst einen durchgefuehrten Read (Erfolg/Fehler/Timeout). */
    fun recordRead(record: ReadRecord) {
        if (reads.size >= maxReads) reads.removeFirst()
        reads.addLast(record)
    }

    /** PHASE 16: erfasst einen RSSI-Messwert. */
    fun recordRssi(value: Int, atMs: Long) {
        if (rssi.size >= maxRssi) rssi.removeFirst()
        rssi.addLast(RssiSample(value, atMs))
    }

    /** Fuehrt ein Scan-Ergebnis zusammen (gleiche Adresse = gleicher Eintrag). */
    fun recordScan(record: ScanRecord) {
        val existing = scan[record.address]
        scan[record.address] = if (existing == null) record else existing.copy(
            name = record.name ?: existing.name,
            rssi = record.rssi,
            lastSeenMs = record.lastSeenMs,
            seenCount = existing.seenCount + 1,
            brand = if (record.brand.isNotBlank()) record.brand else existing.brand,
            detectedModel = if (record.detectedModel.isNotBlank()) record.detectedModel else existing.detectedModel,
            confidence = if (record.confidence.isNotBlank()) record.confidence else existing.confidence
        )
    }

    fun frames(): List<FrameRecord> = frames.toList()
    fun errors(): List<HardwareErrorRecord> = errors.toList()
    fun scanResults(): List<ScanRecord> = scan.values.toList()
    fun reads(): List<ReadRecord> = reads.toList()
    fun rssiSamples(): List<RssiSample> = rssi.toList()

    fun clear() {
        frames.clear()
        errors.clear()
        scan.clear()
        reads.clear()
        rssi.clear()
    }

    fun frameCount(): Int = frames.size
    fun errorCount(): Int = errors.size
    fun readCount(): Int = reads.size
    fun rssiCount(): Int = rssi.size
}

/** Kleine, abhaengigkeitsfreie JSON-/CSV-Hilfe mit korrektem Escaping. */
internal object Json {

    fun quote(s: String): String = buildString {
        append('"')
        for (ch in s) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    fun quoteOrNull(s: String?): String = if (s == null) "null" else quote(s)

    fun stringArray(items: List<String>): String =
        items.joinToString(prefix = "[", postfix = "]") { quote(it) }

    /** RFC-4180-konformes CSV-Feld-Escaping. */
    fun csv(s: String): String {
        val needs = s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needs) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}
