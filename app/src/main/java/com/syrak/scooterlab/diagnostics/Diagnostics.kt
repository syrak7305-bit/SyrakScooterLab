package com.syrak.scooterlab.diagnostics

import com.syrak.scooterlab.BleProtocol
import com.syrak.scooterlab.ScooterModel
import com.syrak.scooterlab.Telemetry
import kotlin.math.abs

/* ============================================================================
 *  PHASE 9 — DIAGNOSE-SCHICHT
 *  ----------------------------------------------------------------------------
 *  GRUNDREGEL: KEINE ERFUNDENEN DATEN.
 *
 *  Diese Datei ist bewusst REINES KOTLIN (keine Android-Abhaengigkeiten), damit
 *  jede Regel ohne Geraet unit-getestet werden kann. Sie beantwortet fuer jeden
 *  angezeigten Wert genau eine Frage: "Ist dieser Wert tatsaechlich empfangen,
 *  plausibel und aktuell?" — und liefert sonst einen expliziten Status
 *  ("Nicht verfuegbar", "Ungueltig", "Veraltet", "Protokoll unbekannt").
 *
 *  WICHTIG fuer die Multi-Brand-Vorbereitung:
 *  Der Status [DataStatus.UNKNOWN_PROTOCOL] wird NICHT durch Raten ersetzt.
 *  Ein unbekanntes Modell fuehrt dazu, dass modellabhaengige Plausibilitaets-
 *  grenzen (z. B. Packspannung) als "Protokoll unbekannt" gemeldet werden,
 *  statt einen 36-V-Default zu unterstellen.
 *
 *  Die Schicht ist READ-ONLY: sie erzeugt ausschliesslich Anzeige-Daten und
 *  sendet niemals Befehle. TuningSafetyGate / Not-Aus bleiben unberuehrt.
 * ==========================================================================*/

/** Verfuegbarkeit / Qualitaet EINES diagnostischen Wertes. */
enum class DataStatus(val label: String) {
    OK("OK"),
    NOT_AVAILABLE("Nicht verfügbar"),
    INVALID("Ungültig"),
    STALE("Veraltet"),
    UNKNOWN_PROTOCOL("Protokoll unbekannt");

    val isOk: Boolean get() = this == OK
}

/**
 * Ein einzelner diagnostischer Wert mit ehrlichem Status.
 *
 * [value] bleibt null, wenn nichts empfangen wurde. Bei [DataStatus.INVALID]
 * kann [value] dennoch gesetzt sein — dann ist der empfangene Wert sichtbar,
 * aber ausdruecklich als unplausibel markiert (nicht als Wahrheit).
 */
data class DiagnosticValue<T>(
    val value: T?,
    val status: DataStatus,
    val note: String? = null
) {
    companion object {
        fun <T> ok(v: T): DiagnosticValue<T> = DiagnosticValue(v, DataStatus.OK)
        fun <T> notAvailable(note: String? = null): DiagnosticValue<T> =
            DiagnosticValue(null, DataStatus.NOT_AVAILABLE, note)
        fun <T> invalid(v: T?, note: String? = null): DiagnosticValue<T> =
            DiagnosticValue(v, DataStatus.INVALID, note)
        fun <T> stale(v: T?, note: String? = null): DiagnosticValue<T> =
            DiagnosticValue(v, DataStatus.STALE, note)
    }
}

/** Abgeleiteter Ladezustand. Nur belegt, wenn er aus den Daten folgt. */
enum class ChargeState(val label: String) {
    CHARGING("Laden"),
    DISCHARGING("Entladen"),
    IDLE("Ruhe (kein Stromfluss)"),
    UNKNOWN("Unbekannt")
}

/* -------------------------------------------------------------------------- */
/*  STALE-GUARD                                                               */
/* -------------------------------------------------------------------------- */

/**
 * Erkennt veraltete Telemetrie anhand der MONOTONEN Uhr.
 *
 * Ein Wert, der vor 10 s empfangen wurde, darf nicht als "live" angezeigt
 * werden — sonst friert die Anzeige bei einem stillen BLE-Abbruch auf dem
 * letzten Stand ein. [isStale] liefert fuer "noch nie empfangen" bewusst true.
 */
object StaleGuard {

    /** Standard-Schwelle: 3 Poll-Zyklen (Poll-Intervall 1 s). */
    const val DEFAULT_STALE_MS = 3_000L

    /** Alter in ms; null, wenn noch nie ein Wert empfangen wurde. */
    fun ageMillis(lastAtMillis: Long?, nowMillis: Long): Long? {
        if (lastAtMillis == null || lastAtMillis <= 0L) return null
        val age = nowMillis - lastAtMillis
        // Rueckwaerts laufende Uhr (sollte mit elapsedRealtime nicht passieren)
        // wird als "gerade eben" behandelt, niemals als negativ.
        return if (age < 0L) 0L else age
    }

    fun isStale(
        lastAtMillis: Long?,
        nowMillis: Long,
        thresholdMillis: Long = DEFAULT_STALE_MS
    ): Boolean {
        val age = ageMillis(lastAtMillis, nowMillis) ?: return true
        return age > thresholdMillis
    }
}

/* -------------------------------------------------------------------------- */
/*  PLAUSIBILITAET                                                            */
/* -------------------------------------------------------------------------- */

/**
 * Plausibilitaetsregeln. Alle Grenzen sind physikalisch begruendet und
 * dokumentiert; es werden KEINE geraetespezifischen Registerwerte geraten.
 */
object Plausibility {

    /** Li-Ion-Zelle: 1.5 V ist tiefentladen, 4.5 V ist absolute Obergrenze. */
    const val CELL_MIN_V = 1.5f
    const val CELL_MAX_V = 4.5f

    /** Elektronik-/Motor-Temperaturbereich. */
    const val TEMP_MIN_C = -40f
    const val TEMP_MAX_C = 125f

    /** Zell-Drift-Warnschwelle. */
    const val MAX_CELL_DELTA_MV = 100f

    /** SoH-Warnschwelle. */
    const val MIN_SOH_PERCENT = 80f

    fun batteryPercent(v: Int?): DataStatus = when {
        v == null -> DataStatus.NOT_AVAILABLE
        v !in 0..100 -> DataStatus.INVALID
        else -> DataStatus.OK
    }

    fun cellVoltage(v: Float?): DataStatus = when {
        v == null -> DataStatus.NOT_AVAILABLE
        v < CELL_MIN_V || v > CELL_MAX_V -> DataStatus.INVALID
        else -> DataStatus.OK
    }

    fun temperature(v: Float?): DataStatus = when {
        v == null -> DataStatus.NOT_AVAILABLE
        v < TEMP_MIN_C || v > TEMP_MAX_C -> DataStatus.INVALID
        else -> DataStatus.OK
    }

    fun speedKmh(v: Float?): DataStatus = when {
        v == null -> DataStatus.NOT_AVAILABLE
        v < 0f || v > 200f -> DataStatus.INVALID
        else -> DataStatus.OK
    }

    /**
     * Plausibler Pack-Spannungsbereich fuer ein BEKANNTES Modell:
     * Zellzahl * 2.5 V (tiefentladen) bis Zellzahl * 4.3 V (voll).
     *
     * Fuer [ScooterModel.UNKNOWN] gibt es keine belegte Zellzahl — dann wird
     * bewusst [DataStatus.UNKNOWN_PROTOCOL] gemeldet statt zu raten.
     */
    fun packVoltage(v: Float?, model: ScooterModel): DataStatus {
        if (v == null) return DataStatus.NOT_AVAILABLE
        if (!model.isKnown || model.maxCells <= 0) return DataStatus.UNKNOWN_PROTOCOL
        val low = model.maxCells * 2.5f
        val high = model.maxCells * 4.3f
        return if (v < low || v > high) DataStatus.INVALID else DataStatus.OK
    }

    /** SoH in % aus Voll-/Designkapazitaet; null, wenn nicht berechenbar. */
    fun sohPercent(fullChargeMah: Int?, designMah: Int?): Float? {
        if (fullChargeMah == null || designMah == null) return null
        if (designMah <= 0 || fullChargeMah <= 0) return null
        return fullChargeMah.toFloat() / designMah.toFloat() * 100f
    }

    /** Zell-Delta in mV; null, wenn ein Wert fehlt oder max < min. */
    fun cellDeltaMv(minV: Float?, maxV: Float?): Float? {
        if (minV == null || maxV == null) return null
        if (maxV < minV) return null
        return (maxV - minV) * 1000f
    }
}

/* -------------------------------------------------------------------------- */
/*  DIAGNOSE-EVENT-LOG                                                        */
/* -------------------------------------------------------------------------- */

enum class DiagnosticSeverity(val label: String) {
    INFO("INFO"),
    WARNING("WARNUNG"),
    ERROR("FEHLER")
}

/**
 * Ein einzelnes Diagnose-Ereignis mit Zeitstempel und stabilem Code.
 * [timestampMillis] ist die monotone Uhr (SystemClock.elapsedRealtime).
 */
data class DiagnosticEvent(
    val timestampMillis: Long,
    val severity: DiagnosticSeverity,
    val code: String,
    val message: String
)

/**
 * Ringpuffer fuer Diagnose-Ereignisse. Thread-sicher, weil BLE-Callbacks und
 * UI-Thread gleichzeitig schreiben/lesen koennen. Begrenzte Kapazitaet haelt
 * den Speicherverbrauch konstant (kein unbegrenztes Wachstum im Dauerbetrieb).
 */
class DiagnosticEventLog(private val capacity: Int = 200) {

    private val events = ArrayDeque<DiagnosticEvent>()

    init {
        require(capacity > 0) { "capacity muss > 0 sein" }
    }

    @Synchronized
    fun add(
        timestampMillis: Long,
        severity: DiagnosticSeverity,
        code: String,
        message: String
    ) {
        events.addLast(DiagnosticEvent(timestampMillis, severity, code, message))
        while (events.size > capacity) events.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<DiagnosticEvent> = events.toList()

    @Synchronized
    fun errorCount(): Int = events.count { it.severity == DiagnosticSeverity.ERROR }

    @Synchronized
    fun warningCount(): Int = events.count { it.severity == DiagnosticSeverity.WARNING }

    @Synchronized
    fun last(severity: DiagnosticSeverity? = null): DiagnosticEvent? =
        events.lastOrNull { severity == null || it.severity == severity }

    @Synchronized
    fun clear() = events.clear()

    @Synchronized
    fun size(): Int = events.size
}

/* -------------------------------------------------------------------------- */
/*  DIAGNOSE-MODELLE                                                          */
/* -------------------------------------------------------------------------- */

data class BatteryDiagnostics(
    val percent: DiagnosticValue<Int>,
    val voltage: DiagnosticValue<Float>,
    val cellMin: DiagnosticValue<Float>,
    val cellMax: DiagnosticValue<Float>,
    val cellDeltaMv: DiagnosticValue<Float>,
    val temperature: DiagnosticValue<Float>,
    val sohPercent: DiagnosticValue<Float>,
    val chargeState: ChargeState,
    val chargeStateNote: String,
    val warnings: List<String>
) {
    val hasWarning: Boolean get() = warnings.isNotEmpty()
}

data class MotorDiagnostics(
    val rpm: DiagnosticValue<Int>,
    val temperature: DiagnosticValue<Float>,
    val controllerStatus: DiagnosticValue<String>,
    val faults: List<String>,
    val notes: List<String>
)

data class ElectronicsDiagnostics(
    val connected: Boolean,
    val protocol: DiagnosticValue<BleProtocol>,
    val firmwareVersion: DiagnosticValue<String>,
    val controllerStatus: DiagnosticValue<String>,
    val commErrorCount: Int,
    val lastRxAgeMillis: Long?,
    val link: DiagnosticValue<String>
)

data class LiveDiagnostics(
    val speedKmh: DiagnosticValue<Float>,
    val voltageV: DiagnosticValue<Float>,
    val currentA: DiagnosticValue<Float>,
    val powerW: DiagnosticValue<Float>,
    val batteryPercent: DiagnosticValue<Int>,
    val updateAgeMillis: Long?,
    val freshness: DataStatus
)

data class DiagnosticsReport(
    val battery: BatteryDiagnostics,
    val motor: MotorDiagnostics,
    val electronics: ElectronicsDiagnostics,
    val live: LiveDiagnostics,
    val overall: DataStatus,
    val warnings: List<String>,
    val generatedAtMillis: Long
)

/** Eingabe fuer [DiagnosticsBuilder]. Alles, was die Schicht wissen darf. */
data class DiagnosticsInput(
    val telemetry: Telemetry?,
    val model: ScooterModel,
    val protocol: BleProtocol?,
    val connected: Boolean,
    val lastTelemetryAtMillis: Long?,
    val nowMillis: Long,
    val commErrorCount: Int = 0,
    val staleThresholdMillis: Long = StaleGuard.DEFAULT_STALE_MS,
    val isDemo: Boolean = false
)

/* -------------------------------------------------------------------------- */
/*  BUILDER                                                                   */
/* -------------------------------------------------------------------------- */

/**
 * Baut aus vorhandenen Daten einen [DiagnosticsReport]. Reine Funktion ohne
 * Seiteneffekte — dadurch vollstaendig unit-testbar.
 */
object DiagnosticsBuilder {

    fun build(input: DiagnosticsInput): DiagnosticsReport {
        val t = input.telemetry
        val age = StaleGuard.ageMillis(input.lastTelemetryAtMillis, input.nowMillis)
        val stale = StaleGuard.isStale(
            input.lastTelemetryAtMillis, input.nowMillis, input.staleThresholdMillis
        )
        val warnings = ArrayList<String>()

        if (input.isDemo) {
            warnings.add("DEMO-Daten (simuliert) — keine echten Gerätedaten.")
        }
        if (!input.model.isKnown) {
            warnings.add("Modell unbekannt — modellabhängige Grenzwerte nicht belegbar.")
        }
        if (t != null && stale) {
            warnings.add("Telemetrie veraltet (${age ?: 0} ms alt) — letzter Wert eingefroren?")
        }
        if (t != null && input.protocol != null && t.source != input.protocol) {
            warnings.add(
                "Datenquelle (${t.source.displayName}) passt nicht zum aktiven Protokoll " +
                    "(${input.protocol.displayName}) — Dekodierung prüfen."
            )
        }
        if (input.commErrorCount > 0) {
            warnings.add("${input.commErrorCount} Kommunikationsfehler seit Verbindungsaufbau.")
        }

        val battery = buildBattery(t, input.model, stale, warnings)
        val motor = buildMotor(t, stale)
        val electronics = buildElectronics(input, age)
        val live = buildLive(t, input.model, stale, age)

        val overall = when {
            !input.connected -> DataStatus.NOT_AVAILABLE
            t == null -> DataStatus.NOT_AVAILABLE
            stale -> DataStatus.STALE
            listOf(
                battery.percent.status, battery.voltage.status, battery.temperature.status,
                live.speedKmh.status, live.currentA.status
            ).any { it == DataStatus.INVALID } -> DataStatus.INVALID
            else -> DataStatus.OK
        }

        return DiagnosticsReport(
            battery = battery,
            motor = motor,
            electronics = electronics,
            live = live,
            overall = overall,
            warnings = warnings,
            generatedAtMillis = input.nowMillis
        )
    }

    /* ---------------------------- Akku ---------------------------- */

    private fun buildBattery(
        t: Telemetry?,
        model: ScooterModel,
        stale: Boolean,
        warnings: MutableList<String>
    ): BatteryDiagnostics {
        val percentStatus = Plausibility.batteryPercent(t?.batteryPercent)
        val voltageStatus = Plausibility.packVoltage(t?.voltageV, model)
        val cellMinStatus = Plausibility.cellVoltage(t?.cellMinV)
        val cellMaxStatus = Plausibility.cellVoltage(t?.cellMaxV)
        val tempStatus = Plausibility.temperature(t?.bmsTempC ?: t?.motorTempC)

        val delta = Plausibility.cellDeltaMv(t?.cellMinV, t?.cellMaxV)
        val soh = Plausibility.sohPercent(t?.fullChargeCapacityMah, t?.designCapacityMah)

        if (percentStatus == DataStatus.INVALID) {
            warnings.add("Akkustand unplausibel (${t?.batteryPercent} %) — verworfen.")
        }
        if (voltageStatus == DataStatus.INVALID) {
            warnings.add("Packspannung unplausibel für Modell (${t?.voltageV} V).")
        }
        if (delta != null && delta > Plausibility.MAX_CELL_DELTA_MV) {
            warnings.add("Zell-Delta über ${Plausibility.MAX_CELL_DELTA_MV.toInt()} mV (${delta.toInt()} mV).")
        }
        if (soh != null && soh < Plausibility.MIN_SOH_PERCENT) {
            warnings.add("SoH unter ${Plausibility.MIN_SOH_PERCENT.toInt()} % (${soh.toInt()} %).")
        }

        val (chargeState, chargeNote) = chargeStateOf(t?.currentA)

        return BatteryDiagnostics(
            percent = dv(t?.batteryPercent, percentStatus, stale),
            voltage = dv(t?.voltageV, voltageStatus, stale),
            cellMin = dv(t?.cellMinV, cellMinStatus, stale),
            cellMax = dv(t?.cellMaxV, cellMaxStatus, stale),
            cellDeltaMv = dv(delta, if (delta == null) DataStatus.NOT_AVAILABLE else DataStatus.OK, stale),
            temperature = dv(t?.bmsTempC ?: t?.motorTempC, tempStatus, stale,
                note = if (t?.bmsTempC == null && t?.motorTempC != null) "Ersatzwert: Motor-Temperatur" else null),
            sohPercent = dv(soh, if (soh == null) DataStatus.NOT_AVAILABLE else DataStatus.OK, stale),
            chargeState = chargeState,
            chargeStateNote = chargeNote,
            warnings = emptyList()
        )
    }

    private fun chargeStateOf(currentA: Float?): Pair<ChargeState, String> = when {
        currentA == null ->
            ChargeState.UNKNOWN to "Strom nicht verfügbar"
        abs(currentA) < 0.05f ->
            ChargeState.IDLE to "kein messbarer Stromfluss"
        else ->
            ChargeState.UNKNOWN to "Lade-/Entladerichtung nicht belegt (Vorzeichen unbelegt)"
    }

    /* ---------------------------- Motor ---------------------------- */

    private fun buildMotor(t: Telemetry?, stale: Boolean): MotorDiagnostics {
        val tempStatus = Plausibility.temperature(t?.motorTempC)
        return MotorDiagnostics(
            // Drehzahl ist im aktuellen Decoder NICHT belegt -> ehrlich "Nicht verfügbar".
            rpm = DiagnosticValue.notAvailable(
                "Drehzahl wird vom aktuellen Decoder nicht gelesen (kein belegtes Feld)."
            ),
            temperature = dv(t?.motorTempC, tempStatus, stale),
            controllerStatus = DiagnosticValue.notAvailable(
                "Kein Controller-Statusfeld im belegten Frame."
            ),
            faults = emptyList(),
            notes = listOf(
                "Fehlercodes werden nicht dekodiert (keine belegte Kodierung).",
                "Es werden keine erfundenen Fehlercodes angezeigt."
            )
        )
    }

    /* ---------------------------- Elektronik ---------------------------- */

    private fun buildElectronics(input: DiagnosticsInput, age: Long?): ElectronicsDiagnostics =
        ElectronicsDiagnostics(
            connected = input.connected,
            protocol = if (input.protocol == null) {
                DiagnosticValue.notAvailable("Kein Protokoll aktiv.")
            } else {
                DiagnosticValue.ok(input.protocol)
            },
            firmwareVersion = DiagnosticValue.notAvailable(
                "Firmware-Version wird nicht aus dem Notify-Frame gelesen."
            ),
            controllerStatus = DiagnosticValue.notAvailable(
                "Kein Controller-Statusfeld im belegten Frame."
            ),
            commErrorCount = input.commErrorCount,
            lastRxAgeMillis = age,
            link = if (input.connected) {
                DiagnosticValue.ok("verbunden")
            } else {
                DiagnosticValue.notAvailable("nicht verbunden")
            }
        )

    /* ---------------------------- Live ---------------------------- */

    private fun buildLive(
        t: Telemetry?,
        model: ScooterModel,
        stale: Boolean,
        age: Long?
    ): LiveDiagnostics {
        val power = if (t?.voltageV != null && t.currentA != null) t.voltageV * t.currentA else null
        return LiveDiagnostics(
            speedKmh = dv(t?.speedKmh, Plausibility.speedKmh(t?.speedKmh), stale),
            voltageV = dv(t?.voltageV, Plausibility.packVoltage(t?.voltageV, model), stale),
            currentA = dv(t?.currentA, if (t?.currentA == null) DataStatus.NOT_AVAILABLE else DataStatus.OK, stale),
            powerW = dv(power, if (power == null) DataStatus.NOT_AVAILABLE else DataStatus.OK, stale),
            batteryPercent = dv(t?.batteryPercent, Plausibility.batteryPercent(t?.batteryPercent), stale),
            updateAgeMillis = age,
            freshness = when {
                t == null -> DataStatus.NOT_AVAILABLE
                stale -> DataStatus.STALE
                else -> DataStatus.OK
            }
        )
    }

    /**
     * Wendet den Stale-Status nur auf Werte an, die an sich gueltig sind.
     * Ein bereits ungueltiger Wert bleibt [DataStatus.INVALID] (wichtiger als
     * "veraltet"), damit die Ursache sichtbar bleibt.
     */
    private fun <T> dv(
        value: T?,
        status: DataStatus,
        stale: Boolean,
        note: String? = null
    ): DiagnosticValue<T> {
        val effective = when {
            value == null -> status
            stale && status == DataStatus.OK -> DataStatus.STALE
            else -> status
        }
        return DiagnosticValue(value, effective, note)
    }
}
