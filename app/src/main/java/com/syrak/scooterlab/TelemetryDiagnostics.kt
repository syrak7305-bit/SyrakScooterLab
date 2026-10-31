package com.syrak.scooterlab

/* ============================================================================
 *  PHASE 16.2 — TELEMETRIE-DIAGNOSE
 *  ---------------------------------------------------------------------------
 *  Zaehlt ausschliesslich tatsaechlich beobachtete Empfangs-Ereignisse und
 *  liefert bei fehlender Telemetrie eine EHRLICHE Ursachen-Hypothese.
 *
 *  Es werden KEINE empfindlichen Daten gespeichert: keine MAC-Adresse, kein
 *  Geraetename, keine Roh-Frames — nur Zaehler, Byte-Zahlen, Zeitstempel und
 *  der aktive Adapter-Name. Damit ist der Zustand gefahrlos logbar/exportierbar.
 *
 *  Reines Kotlin, thread-sicher (BLE-Callback-Thread + UI-Thread), unit-testbar.
 * ==========================================================================*/

/**
 * Unveraenderlicher Schnappschuss der Telemetrie-Diagnose.
 *
 * @param lastRxAtMs monotone Uhr (SystemClock.elapsedRealtime) des letzten
 *   empfangenen Chunks; null = noch nie etwas empfangen.
 */
data class TelemetryDiagnosticsSnapshot(
    val notifications: Long = 0,
    val bytes: Long = 0,
    val lastRxAtMs: Long? = null,
    val framesDetected: Long = 0,
    val invalidFrames: Long = 0,
    val decodeSuccess: Long = 0,
    val decodeFailure: Long = 0,
    val bufferedBytes: Int = 0,
    val activeAdapter: String = "",
    val notifyActive: Boolean = false,
    val connected: Boolean = false,
    val lastError: String? = null
) {
    /** Ehrliche Ursache, wenn (noch) keine nutzbare Telemetrie vorliegt. */
    val missingCause: String get() = TelemetryDiagnosis.cause(this)

    /** Kurze, logbare Zeile. */
    fun summary(): String = buildString {
        append("rx=").append(notifications).append(" Notify/").append(bytes).append(" B")
        append(" · Frames=").append(framesDetected)
        append(" (ungueltig ").append(invalidFrames).append(")")
        append(" · Decode ok=").append(decodeSuccess).append(" fehler=").append(decodeFailure)
        if (bufferedBytes > 0) append(" · Puffer=").append(bufferedBytes).append(" B")
        append(" · Adapter=").append(activeAdapter.ifBlank { "—" })
        append(" · Notify=").append(if (notifyActive) "aktiv" else "inaktiv")
    }
}

/** Reine Ursachen-Logik (keine Seiteneffekte, voll testbar). */
object TelemetryDiagnosis {

    /**
     * Liefert eine ehrliche, priorisierte Ursache. Es wird NIE behauptet, dass
     * Telemetrie funktioniert — nur der naechste konkrete Pruefpunkt genannt.
     */
    fun cause(s: TelemetryDiagnosticsSnapshot): String {
        if (!s.connected) {
            return "Nicht verbunden — keine Telemetrie moeglich."
        }
        if (s.notifications == 0L) {
            return "Verbunden, aber keine Notification empfangen. " +
                "Pruefen: CCCD/Notify aktiv? richtiges Notify-Characteristic? " +
                "sendet der Controller nur auf Status-Request?"
        }
        if (s.framesDetected == 0L) {
            return "Notifications empfangen, aber kein gueltiger Frame-Header erkannt " +
                "(AA 55 / 55 AA / 5A A5). Falsches Notify-Characteristic oder anderes Framing."
        }
        if (s.decodeSuccess == 0L && s.decodeFailure > 0L) {
            return "Frames erkannt, aber Dekodierung fehlgeschlagen. " +
                "Frame-Format/Byte-Offsets sind nicht hardware-bestaetigt (UNVERIFIED)."
        }
        if (s.decodeSuccess > 0L) {
            return "Telemetrie dekodiert — Werte auf Plausibilitaet pruefen. " +
                "Byte-Offsets sind weiterhin UNVERIFIED (kein echter Mitschnitt)."
        }
        return "—"
    }
}

/**
 * Thread-sicherer Zaehler. Wird vom BleEngine pro Notification/Frames/Decode
 * aktualisiert und von der UI gelesen.
 */
class TelemetryDiagnostics {

    private var notifications = 0L
    private var bytes = 0L
    private var lastRxAtMs: Long? = null
    private var framesDetected = 0L
    private var invalidFrames = 0L
    private var decodeSuccess = 0L
    private var decodeFailure = 0L
    private var lastError: String? = null

    @Synchronized
    fun recordChunk(byteCount: Int, atMs: Long) {
        notifications += 1
        if (byteCount > 0) bytes += byteCount
        lastRxAtMs = atMs
    }

    @Synchronized
    fun recordFrame(structurallyValid: Boolean) {
        framesDetected += 1
        if (!structurallyValid) invalidFrames += 1
    }

    @Synchronized
    fun recordDecode(success: Boolean, error: String? = null) {
        if (success) {
            decodeSuccess += 1
        } else {
            decodeFailure += 1
            if (error != null) lastError = error
        }
    }

    @Synchronized
    fun reset() {
        notifications = 0L
        bytes = 0L
        lastRxAtMs = null
        framesDetected = 0L
        invalidFrames = 0L
        decodeSuccess = 0L
        decodeFailure = 0L
        lastError = null
    }

    @Synchronized
    fun snapshot(
        activeAdapter: String,
        bufferedBytes: Int,
        notifyActive: Boolean,
        connected: Boolean
    ): TelemetryDiagnosticsSnapshot = TelemetryDiagnosticsSnapshot(
        notifications = notifications,
        bytes = bytes,
        lastRxAtMs = lastRxAtMs,
        framesDetected = framesDetected,
        invalidFrames = invalidFrames,
        decodeSuccess = decodeSuccess,
        decodeFailure = decodeFailure,
        bufferedBytes = bufferedBytes,
        activeAdapter = activeAdapter,
        notifyActive = notifyActive,
        connected = connected,
        lastError = lastError
    )
}
