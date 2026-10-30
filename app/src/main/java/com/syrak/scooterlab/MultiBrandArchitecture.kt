package com.syrak.scooterlab

/* ============================================================================
 *  PHASE 10 — MULTI-BRAND ARCHITEKTUR
 *  ----------------------------------------------------------------------------
 *  Dieses Modul trennt DREI Dinge, die vorher vermischt waren:
 *
 *    1. HERSTELLER-ERKENNUNG   (reine Namensheuristik, siehe ScooterManufacturer)
 *    2. PROTOKOLL-FAEHIGKEIT   (welcher Adapter kann welches Modell wirklich)
 *    3. SUPPORT-STATUS         (was ist fuer ein Modell tatsaechlich belegt)
 *
 *  Grundregel: Ein Adapter wird NIEMALS allein deshalb fuer ein Modell benutzt,
 *  weil der Hersteller passt. Er muss das Modell EXPLIZIT in
 *  [AdapterCapabilities.supportedModels] fuehren. Alles andere bleibt ehrlich
 *  "IDENTIFIED" (nur der Name ist bekannt) und niemals "TUNING_SUPPORTED".
 *
 *  WICHTIG: Diese Datei enthaelt KEINE erfundenen UUIDs, Register, Offsets oder
 *  Firmware-Versionen. Sie beschreibt ausschliesslich, was im Projekt bereits
 *  implementiert ist, und macht den Reifegrad sichtbar.
 * ==========================================================================*/

/**
 * Reifegrad eines Modells. Die Reihenfolge ist bewusst monoton: jeder Status
 * setzt den vorherigen voraus. [rank] erlaubt Vergleiche ohne when-Ketten.
 *
 * Der Status wird NIEMALS aus der Hersteller-Erkennung abgeleitet. Er entsteht
 * ausschliesslich aus (a) vorhandenem Adapter, (b) Laufzeit-Beobachtung und
 * (c) ausdruecklicher Sicherheitsfreigabe.
 */
enum class SupportStatus(val label: String, val rank: Int) {
    UNKNOWN("Unbekannt", 0),
    IDENTIFIED("Identifiziert", 1),
    DISCOVERED("GATT entdeckt", 2),
    READ_SUPPORTED("Lesen unterstützt", 3),
    DIAGNOSTICS_SUPPORTED("Diagnose unterstützt", 4),
    TUNING_SUPPORTED("Tuning unterstützt", 5);

    fun atLeast(other: SupportStatus): Boolean = rank >= other.rank
}

/** Lese-Operationen, die ein Adapter deklarieren kann. */
enum class ReadOperation(val label: String) {
    STATUS_TELEMETRY("Status-Telemetrie"),
    BATTERY_BMS("Akku / BMS"),
    MOTOR_STATUS("Motorstatus"),
    FIRMWARE_INFO("Firmware-Info")
}

/** Schreib-Operationen, die ein Adapter deklarieren kann. */
enum class WriteOperation(val label: String) {
    SPEED_CEILING("Max-Speed"),
    ZERO_START("Zero-Start"),
    KERS("KERS"),
    CRUISE("Cruise-Control"),
    LOCK("Sperren / Entsperren"),
    REGION("Region"),
    POLICE_MODE("Polizei-Modus"),
    TEMP_GUARD("Motor-Temp-Schutz"),
    EMERGENCY_CUT("Not-Aus"),
    MAX_POWER("Max-Power"),
    GERMAN_MANEUVER("German-Maneuver"),
    OVERSPEED_MUTE("Überspeed-Alarm"),
    REBOOT("Reboot")
}

/**
 * Vollstaendige, selbsterklaerende Beschreibung eines Protokoll-Adapters.
 *
 * Jeder Adapter MUSS hier angeben:
 *  - welche Modelle er wirklich fuehrt,
 *  - welche BLE-Services/Characteristics er erwartet,
 *  - welche Lese-/Schreiboperationen er kennt,
 *  - ob Telemetrie/Deko und Diagnose implementiert sind,
 *  - ob er standardmaessig read-only ist und ob Schreiben eine
 *    Hardware-Verifikation voraussetzen.
 */
data class AdapterCapabilities(
    val adapterId: String,
    val displayName: String,
    val protocol: BleProtocol,
    val supportedModels: Set<ScooterModel>,
    val gattServiceUuids: List<String>,
    val gattWriteCharUuids: List<String>,
    val gattNotifyCharUuids: List<String>,
    val readOperations: Set<ReadOperation>,
    val writeOperations: Set<WriteOperation>,
    val telemetryDecoding: Boolean,
    val diagnostics: Boolean,
    val readOnlyByDefault: Boolean,
    val requiresHardwareVerificationForWrites: Boolean = true
) {
    /** Nur Modelle, die hier EXPLIZIT stehen, gelten als adapter-gedeckt. */
    fun supportsModel(model: ScooterModel): Boolean = model in supportedModels

    fun supportsRead(op: ReadOperation): Boolean = op in readOperations

    fun supportsWrite(op: WriteOperation): Boolean =
        !readOnlyByDefault && op in writeOperations

    fun supportsAnyWrite(): Boolean =
        !readOnlyByDefault && writeOperations.isNotEmpty()
}

/**
 * Ergebnis der Adapter-Auswahl fuer ein Modell.
 *
 * [exact] == true bedeutet: der Adapter fuehrt dieses Modell ausdruecklich.
 * [exact] == false bedeutet: es gibt KEINEN modellspezifischen Adapter. Der
 * Aufrufer darf dann hoechstens einen Transport-Fallback verwenden, aber
 * niemals Tuning freigeben.
 */
data class AdapterResolution(
    val adapter: ScooterProtocolAdapter?,
    val exact: Boolean,
    val reason: String
)

/**
 * Laufzeit-Beobachtungen, die in den Support-Status einfliessen.
 * Default = "noch nichts beobachtet" -> ehrlich niedriger Status.
 */
data class SupportRuntime(
    val gattDiscovered: Boolean = false,
    val telemetryReceived: Boolean = false,
    val hardwareVerified: Boolean = false,
    val tuningGateOpen: Boolean = false
)

/** Fertig ausgewerteter Support-Status fuer die UI. */
data class SupportSnapshot(
    val model: ScooterModel,
    val manufacturer: ScooterManufacturer,
    val status: SupportStatus,
    val protocol: BleProtocol?,
    val protocolKnown: Boolean,
    val adapterId: String?,
    val modelExplicitlySupported: Boolean,
    val readSupported: Boolean,
    val diagnosticsSupported: Boolean,
    val tuningSupported: Boolean,
    val unavailable: List<String>,
    val note: String
)

/**
 * Reine Logik zur Berechnung des Support-Status. Keine Android-Abhaengigkeiten,
 * damit sie vollstaendig unit-testbar ist.
 */
object SupportMatrix {

    /**
     * Berechnet den Status. Wichtig: ohne Laufzeit-Evidenz ist selbst ein
     * vorhandener Adapter nur "IDENTIFIED" — ein Adapter im Quellcode beweist
     * noch keine Funktion am realen Geraet.
     */
    fun evaluate(
        model: ScooterModel,
        adapter: ScooterProtocolAdapter?,
        runtime: SupportRuntime
    ): SupportStatus {
        if (model == ScooterModel.UNKNOWN) return SupportStatus.UNKNOWN
        val caps = adapter?.capabilities ?: return SupportStatus.IDENTIFIED
        if (!caps.supportsModel(model)) return SupportStatus.IDENTIFIED
        if (!runtime.gattDiscovered) return SupportStatus.IDENTIFIED
        if (!runtime.telemetryReceived) return SupportStatus.DISCOVERED
        if (caps.readOperations.isEmpty() || !caps.telemetryDecoding) {
            return SupportStatus.DISCOVERED
        }
        if (!caps.diagnostics) return SupportStatus.READ_SUPPORTED
        if (caps.supportsAnyWrite() &&
            runtime.hardwareVerified &&
            runtime.tuningGateOpen
        ) {
            return SupportStatus.TUNING_SUPPORTED
        }
        return SupportStatus.DIAGNOSTICS_SUPPORTED
    }

    /** Baut einen erklaerenden Snapshot fuer die UI. */
    fun snapshot(
        model: ScooterModel,
        adapter: ScooterProtocolAdapter?,
        runtime: SupportRuntime = SupportRuntime(),
        manufacturerOverride: ScooterManufacturer? = null
    ): SupportSnapshot {
        val caps = adapter?.capabilities
        val status = evaluate(model, adapter, runtime)
        val exact = caps?.supportsModel(model) == true
        val manufacturer = manufacturerOverride
            ?: if (model.manufacturer != ScooterManufacturer.UNKNOWN) model.manufacturer
            else ScooterManufacturer.UNKNOWN

        val readSupported = status.atLeast(SupportStatus.READ_SUPPORTED)
        val diagnosticsSupported = status.atLeast(SupportStatus.DIAGNOSTICS_SUPPORTED)
        val tuningSupported = status.atLeast(SupportStatus.TUNING_SUPPORTED)

        val unavailable = ArrayList<String>(4)
        if (model == ScooterModel.UNKNOWN) {
            unavailable.add("Modell")
        }
        if (caps == null) {
            unavailable.add("Protokoll")
        } else if (!exact) {
            unavailable.add("modellspezifischer Adapter")
        }
        if (!readSupported) unavailable.add("Live-Telemetrie")
        if (!diagnosticsSupported) unavailable.add("Diagnose")
        if (!tuningSupported) unavailable.add("Tuning")

        val note = when {
            model == ScooterModel.UNKNOWN ->
                "Kein belastbares Modellmerkmal — es wird bewusst nichts angenommen."
            caps == null ->
                "Für dieses Modell ist kein Protokoll-Adapter hinterlegt."
            !exact ->
                "Hersteller/Modell erkannt, aber kein Adapter führt genau dieses Modell. " +
                    "Kein Tuning, keine Kompatibilitätsbehauptung."
            !runtime.gattDiscovered ->
                "Adapter vorhanden, aber am Gerät noch kein passender GATT-Service entdeckt."
            !runtime.telemetryReceived ->
                "GATT entdeckt, aber noch keine gültige Telemetrie empfangen."
            tuningSupported ->
                "Tuning ist für diese Sitzung freigegeben (Hardware bestätigt)."
            else ->
                "Lesen/Diagnose belegt; Tuning bleibt gesperrt, bis Hardware bestätigt ist."
        }

        return SupportSnapshot(
            model = model,
            manufacturer = manufacturer,
            status = status,
            protocol = caps?.protocol,
            protocolKnown = caps != null,
            adapterId = caps?.adapterId,
            modelExplicitlySupported = exact,
            readSupported = readSupported,
            diagnosticsSupported = diagnosticsSupported,
            tuningSupported = tuningSupported,
            unavailable = unavailable,
            note = note
        )
    }

    /** Statischer Snapshot ohne Laufzeitdaten (fuer Listen/Vorschau). */
    fun staticSnapshot(model: ScooterModel): SupportSnapshot =
        snapshot(model, ProtocolAdapters.forModel(model), SupportRuntime())
}
