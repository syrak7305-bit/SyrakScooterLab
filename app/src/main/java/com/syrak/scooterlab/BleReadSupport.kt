package com.syrak.scooterlab

/**
 * PHASE 16 – Read-Unterstuetzung.
 *
 * Reine JVM-Logik rund um den Android-`BluetoothGatt`-Read-Flow. Bewusst frei von
 * Android-Abhaengigkeiten, damit Statusauswertung, Timeout- und Abort-Verhalten
 * deterministisch per Unit-Test pruefbar sind.
 *
 * Grundregel (Phase 16 §3): Ein Read gilt NUR dann als erfolgreich, wenn ein
 * echter erfolgreicher Callback (`onCharacteristicRead`, Status GATT_SUCCESS)
 * mit gueltigen Bytes vorliegt. Eine vorhandene Read-Property im GATT-Baum ist
 * KEIN erfolgreicher Read.
 */

/** Ergebnis des Read-Requests (also des Aufrufs, bevor der Callback kommt). */
enum class ReadRequestResult {
    STARTED,
    NOT_CONNECTED,
    NOT_READABLE,
    NO_CHARACTERISTIC,
    REJECTED
}

/** Ergebnis eines tatsaechlichen Read-Vorgangs (Callback / Timeout / Abort). */
enum class ReadStatus {
    SUCCESS,
    EMPTY_VALUE,
    GATT_ERROR,
    TIMEOUT,
    DISCONNECTED,
    ABORTED;

    val isSuccess: Boolean get() = this == SUCCESS

    fun label(language: AppLanguage = AppLanguage.DE): String = when (this) {
        SUCCESS -> if (language == AppLanguage.DE) "Erfolgreich" else "Success"
        EMPTY_VALUE -> if (language == AppLanguage.DE) "Leere Antwort" else "Empty value"
        GATT_ERROR -> if (language == AppLanguage.DE) "GATT-Fehler" else "GATT error"
        TIMEOUT -> if (language == AppLanguage.DE) "Timeout" else "Timeout"
        DISCONNECTED -> if (language == AppLanguage.DE) "Getrennt" else "Disconnected"
        ABORTED -> if (language == AppLanguage.DE) "Abgebrochen" else "Aborted"
    }
}

/**
 * Ein konkreter Read-Ausgang. Enthaelt nur technische Diagnosedaten, keine
 * personenbezogenen Informationen.
 */
data class ReadOutcome(
    val uuid: String,
    val status: ReadStatus,
    val rawStatus: Int = -1,
    val bytesHex: String = "",
    val byteCount: Int = 0,
    val durationMs: Long = 0L,
    val message: String = ""
) {
    val success: Boolean get() = status.isSuccess

    fun summary(language: AppLanguage = AppLanguage.DE): String {
        val head = "${status.label(language)} · $uuid"
        val parts = mutableListOf(head)
        if (rawStatus >= 0) parts.add("status=$rawStatus")
        if (byteCount > 0) parts.add("${byteCount}B")
        if (durationMs > 0) parts.add("${durationMs}ms")
        if (message.isNotBlank()) parts.add(message)
        return parts.joinToString(" · ")
    }
}

/**
 * Klassifiziert Callback-Status und Request-Ergebnisse. Reine Funktionen, keine
 * Seiteneffekte.
 */
object ReadClassifier {

    /**
     * Wertet einen `onCharacteristicRead`-Callback aus.
     *
     * @param rawStatus Statuscode aus dem Callback (z. B. [android.bluetooth.BluetoothGatt.GATT_SUCCESS]).
     * @param gattSuccess Erwarteter Erfolgscode (GATT_SUCCESS = 0). Als Parameter, damit die
     *                    Logik ohne Android-Konstanten testbar bleibt.
     * @param byteCount Anzahl tatsaechlich gelieferter Bytes.
     */
    fun fromCallback(rawStatus: Int, gattSuccess: Int, byteCount: Int): ReadStatus {
        if (rawStatus != gattSuccess) return ReadStatus.GATT_ERROR
        return if (byteCount <= 0) ReadStatus.EMPTY_VALUE else ReadStatus.SUCCESS
    }

    /** Wertet den Read-Request (vor dem Callback) aus. */
    fun fromRequest(
        connected: Boolean,
        characteristicPresent: Boolean,
        readable: Boolean,
        acceptedByStack: Boolean
    ): ReadRequestResult = when {
        !connected -> ReadRequestResult.NOT_CONNECTED
        !characteristicPresent -> ReadRequestResult.NO_CHARACTERISTIC
        !readable -> ReadRequestResult.NOT_READABLE
        !acceptedByStack -> ReadRequestResult.REJECTED
        else -> ReadRequestResult.STARTED
    }

    fun requestMessage(result: ReadRequestResult, language: AppLanguage = AppLanguage.DE): String =
        when (result) {
            ReadRequestResult.STARTED ->
                if (language == AppLanguage.DE) "Read angefordert" else "Read requested"
            ReadRequestResult.NOT_CONNECTED ->
                if (language == AppLanguage.DE) "Nicht verbunden" else "Not connected"
            ReadRequestResult.NO_CHARACTERISTIC ->
                if (language == AppLanguage.DE) "Characteristic nicht vorhanden" else "Characteristic missing"
            ReadRequestResult.NOT_READABLE ->
                if (language == AppLanguage.DE) "Characteristic nicht lesbar" else "Characteristic not readable"
            ReadRequestResult.REJECTED ->
                if (language == AppLanguage.DE) "Vom BLE-Stack abgelehnt" else "Rejected by BLE stack"
        }
}

/**
 * Policy fuer automatische Reads.
 *
 * Phase 16 §3: Keine automatischen Reads an unbekannten oder nicht
 * hardware-verifizierten Geraeten. Ein Auto-Read ist nur erlaubt, wenn das
 * Modell bekannt UND die Hardware verifiziert ist.
 */
object ReadPolicy {

    fun mayAutoRead(modelKnown: Boolean, hardwareVerified: Boolean): Boolean =
        modelKnown && hardwareVerified

    fun autoReadBlockReason(
        modelKnown: Boolean,
        hardwareVerified: Boolean,
        language: AppLanguage = AppLanguage.DE
    ): String? {
        if (mayAutoRead(modelKnown, hardwareVerified)) return null
        return when {
            !modelKnown ->
                if (language == AppLanguage.DE) "Unbekanntes Modell – nur manueller Read"
                else "Unknown model – manual read only"
            else ->
                if (language == AppLanguage.DE) "Hardware nicht verifiziert – nur manueller Read"
                else "Hardware not verified – manual read only"
        }
    }

    /** Unbekannte Modelle erfordern eine bewusste manuelle Bestaetigung. */
    fun requiresManualConfirmation(modelKnown: Boolean): Boolean = !modelKnown
}

/** Ein einzelner, gerade laufender Read. */
data class PendingRead(
    val uuid: String,
    val generation: Int,
    val startedAtMs: Long,
    val label: String = ""
) {
    fun elapsedMs(nowMs: Long): Long = (nowMs - startedAtMs).coerceAtLeast(0L)
}

/**
 * Verfolgt GENAU EINEN laufenden Read. Android erlaubt pro GATT-Verbindung nur
 * eine ausstehende Operation; parallele Reads werden hier hart abgewiesen.
 */
class ReadTracker {

    private var pending: PendingRead? = null

    fun begin(uuid: String, generation: Int, nowMs: Long, label: String = ""): Boolean {
        if (pending != null) return false
        pending = PendingRead(uuid, generation, nowMs, label)
        return true
    }

    fun isPending(): Boolean = pending != null

    fun pendingUuid(): String? = pending?.uuid

    fun pendingRead(): PendingRead? = pending

    /** Prueft, ob ein Callback zu dem laufenden Read (inkl. Verbindungsgeneration) passt. */
    fun matches(uuid: String, generation: Int): Boolean {
        val p = pending ?: return false
        return p.uuid.equals(uuid, ignoreCase = true) && p.generation == generation
    }

    fun complete(): PendingRead? {
        val p = pending
        pending = null
        return p
    }

    /** Liefert den Read zurueck, wenn er die Timeout-Grenze ueberschritten hat. */
    fun timeout(nowMs: Long, timeoutMs: Long): PendingRead? {
        val p = pending ?: return null
        return if (p.elapsedMs(nowMs) >= timeoutMs) {
            pending = null
            p
        } else {
            null
        }
    }

    fun abort(): PendingRead? {
        val p = pending
        pending = null
        return p
    }

    fun clear() {
        pending = null
    }
}

/**
 * Waehlt eine lesbare Characteristic aus bekannten GATT-Fakten.
 * Bevorzugt die explizit gewuenschte UUID, sonst die erste lesbare.
 */
object ReadSelector {

    fun readableUuids(facts: List<CharacteristicFacts>): List<String> =
        facts.filter { it.readable }.map { it.uuid }

    fun pick(facts: List<CharacteristicFacts>, preferredUuid: String? = null): String? {
        if (preferredUuid != null) {
            val match = facts.firstOrNull {
                it.readable && it.uuid.equals(preferredUuid, ignoreCase = true)
            }
            if (match != null) return match.uuid
        }
        return facts.firstOrNull { it.readable }?.uuid
    }
}
