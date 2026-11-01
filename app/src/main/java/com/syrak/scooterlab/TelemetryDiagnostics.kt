package com.syrak.scooterlab

/* ============================================================================
 *  PHASE 16.2 / 16.3 — TELEMETRIE-DIAGNOSE
 *  ---------------------------------------------------------------------------
 *  Zaehlt ausschliesslich tatsaechlich beobachtete Empfangs-Ereignisse und
 *  liefert bei fehlender Telemetrie eine EHRLICHE Ursachen-Hypothese.
 *
 *  PHASE 16.3 erweitert die Diagnose um die Notify-Aktivierungs-Kette, damit
 *  exakt zwischen folgenden Zustaenden unterschieden werden kann:
 *    1. Keine Notify-/Indicate-Characteristic gefunden
 *    2. Notify-Characteristic gefunden, aber CCCD (0x2902) fehlt
 *    3. CCCD-Aktivierung (writeDescriptor) fehlgeschlagen / nicht bestaetigt
 *    4. Notify aktiviert, aber keine Daten empfangen
 *    5. Daten empfangen, aber Frames ungueltig (kein Header / falsche Laenge)
 *    6. Frames erkannt, aber Decoder fehlgeschlagen
 *  (plus: Telemetrie dekodiert — Werte weiterhin UNVERIFIED)
 *
 *  Es werden KEINE empfindlichen Daten gespeichert: keine MAC-Adresse, kein
 *  Geraetename, keine Roh-Frames — nur Zaehler, Byte-Zahlen, Zeitstempel,
 *  UUIDs der beteiligten Characteristics und der aktive Adapter-Name.
 *
 *  Reines Kotlin, thread-sicher (BLE-Callback-Thread + UI-Thread), unit-testbar.
 * ==========================================================================*/

/**
 * Unveraenderlicher Schnappschuss der Telemetrie-Diagnose.
 *
 * Die Felder bis [lastError] stammen aus Phase 16.2 und behalten ihre
 * Reihenfolge/Default-Werte (Rueckwaertskompatibilitaet). Die Phase-16.3-Felder
 * sind am Ende angehaengt und beschreiben die Notify-Aktivierungs-Kette.
 *
 * @param lastRxAtMs monotone Uhr (SystemClock.elapsedRealtime) des letzten
 *   empfangenen Chunks; null = noch nie etwas empfangen.
 * @param adapterConfirmed true, wenn der Adapter aus einem BEKANNTEN Service
 *   (FFE0/E003) abgeleitet wurde; false = nur der Default-Annahme-Adapter
 *   (kein Beleg fuer das Protokoll).
 * @param discoveryDone true, sobald die Service-Discovery abgeschlossen ist.
 * @param notifyCharFound true, wenn im GATT-Baum eine Characteristic mit
 *   NOTIFY- oder INDICATE-Property existiert.
 * @param notifyCharUuid UUID der ausgewaehlten Notify-Characteristic (nur UUID,
 *   keine sensiblen Daten). Leer, wenn keine gefunden wurde.
 * @param cccdPresent true, wenn die Notify-Characteristic einen CCCD-Deskriptor
 *   (0x2902) besitzt.
 * @param cccdWriteRequested true, wenn writeDescriptor(CCCD) aufgerufen wurde.
 * @param cccdWriteAccepted true, wenn writeDescriptor(CCCD) vom Stack
 *   angenommen wurde (Rueckgabe true). Die eigentliche Bestaetigung kommt
 *   asynchron ueber onDescriptorWrite ([cccdWriteStatus]).
 * @param cccdWriteStatus Status aus onDescriptorWrite; null = noch kein
 *   Callback erhalten. 0 = GATT_SUCCESS.
 * @param notifySetupIssue optionale Klartext-Erklaerung, warum Notify nicht
 *   aktiviert werden konnte (z. B. "kein Notify/Indicate-Property").
 */
data class TelemetryDiagnosticsSnapshot(
    // ---- Phase 16.2 (Reihenfolge unveraendert) ----
    val notifications: Long = 0,
    val bytes: Long = 0,
    val lastRxAtMs: Long? = null,
    /**
     * PHASE 16.3.4: Empfangszähler je Characteristic-UUID. Leer, solange keine
     * Notification mit bekannter UUID empfangen wurde. Belegt, welcher Kanal
     * tatsaechlich Daten liefert — ohne eine Rolle zu unterstellen.
     */
    val notificationsByUuid: Map<String, Long> = emptyMap(),
    val framesDetected: Long = 0,
    val invalidFrames: Long = 0,
    val decodeSuccess: Long = 0,
    val decodeFailure: Long = 0,
    val bufferedBytes: Int = 0,
    val activeAdapter: String = "",
    val notifyActive: Boolean = false,
    val connected: Boolean = false,
    val lastError: String? = null,
    // ---- Phase 16.3 (Notify-Aktivierungs-Kette) ----
    val adapterConfirmed: Boolean = false,
    val discoveryDone: Boolean = false,
    val notifyCharFound: Boolean = false,
    val notifyCharUuid: String = "",
    val cccdPresent: Boolean = false,
    val cccdWriteRequested: Boolean = false,
    val cccdWriteAccepted: Boolean = false,
    val cccdWriteStatus: Int? = null,
    val notifySetupIssue: String? = null,
    // ---- Phase 16.3.1: lokale Registrierung + Descriptor-Callback ----
    /** true, wenn setCharacteristicNotification() ueberhaupt aufgerufen wurde. */
    val localNotifyRegistrationAttempted: Boolean = false,
    /** Rueckgabe von setCharacteristicNotification(); false = lokal abgelehnt. */
    val localNotifyRegistered: Boolean = false,
    /** true, wenn IRGENDEINE Characteristic im GATT-Baum notifiable ist. */
    val anyNotifiablePresent: Boolean = false,
    /** true, sobald onDescriptorWrite fuer den CCCD eingetroffen ist. */
    val descriptorCallbackReceived: Boolean = false,
    /** true, wenn onDescriptorWrite GATT_SUCCESS meldete. */
    val descriptorCallbackSuccess: Boolean = false
) {
    /** Ehrliche Ursache, wenn (noch) keine nutzbare Telemetrie vorliegt. */
    val missingCause: String get() = TelemetryDiagnosis.cause(this)

    /**
     * true NUR, wenn die komplette Notify-Kette belegt ist: lokale Registrierung
     * erfolgreich, CCCD-Write vom Stack angenommen und Descriptor-Callback mit
     * GATT_SUCCESS. Einzelne Schritte allein reichen NICHT.
     */
    val notifyChainComplete: Boolean
        get() = localNotifyRegistered && cccdWriteAccepted && descriptorCallbackSuccess

    /** Kurze, logbare Zeile. */
    fun summary(): String = buildString {
        append("rx=").append(notifications).append(" Notify/").append(bytes).append(" B")
        append(" \u00b7 Frames=").append(framesDetected)
        append(" (ungueltig ").append(invalidFrames).append(")")
        append(" \u00b7 Decode ok=").append(decodeSuccess).append(" fehler=").append(decodeFailure)
        if (bufferedBytes > 0) append(" \u00b7 Puffer=").append(bufferedBytes).append(" B")
        append(" \u00b7 Adapter=").append(activeAdapter.ifBlank { "\u2014" })
        if (activeAdapter.isNotBlank() && !adapterConfirmed) append(" (angenommen)")
        append(" \u00b7 Notify=").append(if (notifyActive) "aktiv" else "inaktiv")
        if (notifyCharUuid.isNotBlank()) append(" \u00b7 NotifyChar=").append(notifyCharUuid)
        // PHASE 16.3.4: belegbarer Empfang je Characteristic-UUID.
        if (notificationsByUuid.isNotEmpty()) {
            append(" \u00b7 rx/Char=")
            append(
                notificationsByUuid.entries.joinToString(", ") { "${it.key}=${it.value}" }
            )
        }
        append(" \u00b7 CCCD=").append(
            when {
                cccdWriteStatus != null && cccdWriteStatus != TelemetryDiagnostics.GATT_SUCCESS ->
                    "write-status=$cccdWriteStatus"
                notifyActive -> "aktiv"
                localNotifyRegistrationAttempted && !localNotifyRegistered -> "lokal abgelehnt"
                cccdWriteRequested && !cccdWriteAccepted -> "write abgelehnt"
                cccdWriteRequested -> "write angefordert"
                cccdPresent -> "vorhanden"
                discoveryDone -> "fehlt"
                else -> "\u2014"
            }
        )
    }
}

/** Reine Ursachen-Logik (keine Seiteneffekte, voll testbar). */
object TelemetryDiagnosis {

    /**
     * Liefert eine ehrliche, priorisierte Ursache. Es wird NIE behauptet, dass
     * Telemetrie funktioniert — nur der naechste konkrete Pruefpunkt genannt.
     *
     * Reihenfolge: Verbindung -> Notify-Aktivierung (nur wenn Discovery fertig)
     * -> Empfangszähler -> Decode. Die Phase-16.3-Zweige greifen bewusst erst
     * bei [TelemetryDiagnosticsSnapshot.discoveryDone], damit ein reiner
     * Default-Schnappschuss (ohne Discovery) weiterhin die Phase-16.2-Texte
     * liefert.
     */
    fun cause(s: TelemetryDiagnosticsSnapshot): String {
        if (!s.connected) {
            return "Nicht verbunden \u2014 keine Telemetrie moeglich."
        }

        // ---- PHASE 16.3: praezise Notify-Aktivierungs-Kette ----
        if (s.discoveryDone) {
            if (!s.notifyCharFound) {
                return "Keine Notify-/Indicate-Characteristic im GATT-Baum gefunden. " +
                    "Das Geraet bietet an den bekannten Services keine Notify-Property " +
                    "(oder nutzt ein anderes Service-Layout). Kein Raten von UUIDs."
            }
            if (s.notifyCharUuid.isBlank()) {
                return "Notify-faehige Characteristic vorhanden, aber keine ausgewaehlt " +
                    "(kein passendes Write-/Notify-Paar). Notify bleibt inaktiv."
            }
            if (!s.cccdPresent) {
                return "Notify-Characteristic ${s.notifyCharUuid.ifBlank { "?" }} gefunden, " +
                    "aber CCCD-Deskriptor (0x2902) fehlt. Notify kann nicht aktiviert werden."
            }
            if (s.localNotifyRegistrationAttempted && !s.localNotifyRegistered) {
                return "Lokale Notify-Registrierung fehlgeschlagen " +
                    "(setCharacteristicNotification lieferte false fuer " +
                    "${s.notifyCharUuid.ifBlank { "?" }}). Notify bleibt inaktiv."
            }
            if (s.cccdWriteRequested && !s.cccdWriteAccepted) {
                return "CCCD-Aktivierung (writeDescriptor) wurde vom Stack abgelehnt " +
                    "(${s.notifyCharUuid.ifBlank { "?" }}). Notify bleibt inaktiv."
            }
            if (s.cccdWriteStatus != null &&
                s.cccdWriteStatus != TelemetryDiagnostics.GATT_SUCCESS
            ) {
                return "CCCD-Aktivierung fehlgeschlagen (onDescriptorWrite status=" +
                    "${s.cccdWriteStatus}). Notify bleibt inaktiv."
            }
            if (s.cccdWriteRequested && !s.notifyActive) {
                val cb = if (s.descriptorCallbackReceived)
                    "(onDescriptorWrite empfangen, aber kein Erfolg)"
                else "(kein onDescriptorWrite)"
                return "CCCD-Aktivierung angefordert, aber noch nicht bestaetigt " +
                    "$cb. Notify-Status unklar."
            }
            if (s.notifySetupIssue != null) {
                return "Notify konnte nicht aktiviert werden: ${s.notifySetupIssue}"
            }
        }

        // ---- PHASE 16.2: Empfangs-/Decode-Zaehler ----
        if (s.notifications == 0L) {
            return if (s.notifyActive) {
                "Notify aktiviert, aber keine Daten empfangen (keine Notification). " +
                    "Pruefen: sendet der Controller nur auf Status-Request? " +
                    "richtiges Notify-Characteristic?"
            } else {
                "Verbunden, aber keine Notification empfangen. " +
                    "Pruefen: CCCD/Notify aktiv? richtiges Notify-Characteristic? " +
                    "sendet der Controller nur auf Status-Request?"
            }
        }
        if (s.framesDetected == 0L) {
            return "Notifications empfangen, aber kein gueltiger Frame-Header erkannt " +
                "(AA 55 / 55 AA / 5A A5). Falsches Notify-Characteristic oder anderes Framing."
        }
        if (s.invalidFrames > 0L && s.invalidFrames == s.framesDetected && s.decodeSuccess == 0L) {
            return "Daten empfangen, aber alle Frames ungueltig (Laenge/Struktur). " +
                "Framing passt nicht zum erwarteten Format."
        }
        if (s.decodeSuccess == 0L && s.decodeFailure > 0L) {
            return "Frames erkannt, aber Dekodierung fehlgeschlagen. " +
                "Frame-Format/Byte-Offsets sind nicht hardware-bestaetigt (UNVERIFIED)."
        }
        if (s.decodeSuccess > 0L) {
            return "Telemetrie dekodiert \u2014 Werte auf Plausibilitaet pruefen. " +
                "Byte-Offsets sind weiterhin UNVERIFIED (kein echter Mitschnitt)."
        }
        return "\u2014"
    }
}

/**
 * Thread-sicherer Zaehler. Wird vom BleEngine pro Notification/Frames/Decode
 * sowie bei der Notify-Aktivierung aktualisiert und von der UI gelesen.
 */
class TelemetryDiagnostics {

    companion object {
        /** BluetoothGatt.GATT_SUCCESS == 0 (ohne Android-Abhaengigkeit). */
        const val GATT_SUCCESS = 0
    }

    private var notifications = 0L
    private var bytes = 0L
    private var lastRxAtMs: Long? = null

    /**
     * PHASE 16.3.4: Empfangszähler JE Characteristic-UUID.
     *
     * Damit ist belegbar, WELCHE Characteristic die Daten tatsaechlich liefert.
     * Es wird ausschliesslich die UUID gespeichert (kein Roh-Frame, keine MAC).
     */
    private val notificationsByUuid = LinkedHashMap<String, Long>()
    private var framesDetected = 0L
    private var invalidFrames = 0L
    private var decodeSuccess = 0L
    private var decodeFailure = 0L
    private var lastError: String? = null

    // ---- Phase 16.3: Notify-Aktivierungs-Kette ----
    private var adapterConfirmed = false
    private var discoveryDone = false
    private var notifyCharFound = false
    private var notifyCharUuid = ""
    private var cccdPresent = false
    private var cccdWriteRequested = false
    private var cccdWriteAccepted = false
    private var cccdWriteStatus: Int? = null
    private var notifySetupIssue: String? = null
    private var localNotifyRegistrationAttempted = false
    private var localNotifyRegistered = false
    private var anyNotifiablePresent = false
    private var descriptorCallbackReceived = false
    private var descriptorCallbackSuccess = false

    /**
     * PHASE 16.3.4: [characteristicUuid] ist die UUID der Characteristic, auf der
     * die Notification tatsaechlich ankam. Sie wird pro UUID mitgezaehlt, damit
     * der Bericht belegen kann, welcher Kanal Daten liefert. Der Parameter ist
     * optional, damit bestehende Aufrufer/Tests unveraendert bleiben.
     */
    @Synchronized
    fun recordChunk(byteCount: Int, atMs: Long, characteristicUuid: String? = null) {
        notifications += 1
        if (byteCount > 0) bytes += byteCount
        lastRxAtMs = atMs
        val uuid = characteristicUuid?.trim()
        if (!uuid.isNullOrEmpty()) {
            notificationsByUuid[uuid] = (notificationsByUuid[uuid] ?: 0L) + 1L
        }
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

    /**
     * Haelt fest, dass die Service-Discovery abgeschlossen ist und ob der
     * aktive Adapter aus einem bekannten Service abgeleitet wurde.
     */
    @Synchronized
    fun recordDiscovery(adapterConfirmed: Boolean) {
        discoveryDone = true
        this.adapterConfirmed = adapterConfirmed
    }

    /**
     * Haelt das Ergebnis der Notify-Auswahl + CCCD-Vorbereitung fest.
     * Wird aus afterMtu() aufgerufen, BEVOR writeDescriptor() laeuft.
     */
    @Synchronized
    fun recordNotifySetup(
        notifyCharFound: Boolean,
        notifyCharUuid: String,
        cccdPresent: Boolean,
        cccdWriteRequested: Boolean,
        cccdWriteAccepted: Boolean,
        issue: String?,
        localNotifyRegistrationAttempted: Boolean = false,
        localNotifyRegistered: Boolean = false,
        anyNotifiablePresent: Boolean = notifyCharFound
    ) {
        this.notifyCharFound = notifyCharFound
        this.notifyCharUuid = notifyCharUuid
        this.cccdPresent = cccdPresent
        this.cccdWriteRequested = cccdWriteRequested
        this.cccdWriteAccepted = cccdWriteAccepted
        this.notifySetupIssue = issue
        this.localNotifyRegistrationAttempted = localNotifyRegistrationAttempted
        this.localNotifyRegistered = localNotifyRegistered
        this.anyNotifiablePresent = anyNotifiablePresent
    }

    /** Haelt den Status aus onDescriptorWrite fest (0 == GATT_SUCCESS). */
    @Synchronized
    fun recordCccdResult(status: Int) {
        cccdWriteStatus = status
        descriptorCallbackReceived = true
        descriptorCallbackSuccess = status == GATT_SUCCESS
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
        notificationsByUuid.clear()
        adapterConfirmed = false
        discoveryDone = false
        notifyCharFound = false
        notifyCharUuid = ""
        cccdPresent = false
        cccdWriteRequested = false
        cccdWriteAccepted = false
        cccdWriteStatus = null
        notifySetupIssue = null
        localNotifyRegistrationAttempted = false
        localNotifyRegistered = false
        anyNotifiablePresent = false
        descriptorCallbackReceived = false
        descriptorCallbackSuccess = false
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
        notificationsByUuid = LinkedHashMap(notificationsByUuid),
        framesDetected = framesDetected,
        invalidFrames = invalidFrames,
        decodeSuccess = decodeSuccess,
        decodeFailure = decodeFailure,
        bufferedBytes = bufferedBytes,
        activeAdapter = activeAdapter,
        notifyActive = notifyActive,
        connected = connected,
        lastError = lastError,
        adapterConfirmed = adapterConfirmed,
        discoveryDone = discoveryDone,
        notifyCharFound = notifyCharFound,
        notifyCharUuid = notifyCharUuid,
        cccdPresent = cccdPresent,
        cccdWriteRequested = cccdWriteRequested,
        cccdWriteAccepted = cccdWriteAccepted,
        cccdWriteStatus = cccdWriteStatus,
        notifySetupIssue = notifySetupIssue,
        localNotifyRegistrationAttempted = localNotifyRegistrationAttempted,
        localNotifyRegistered = localNotifyRegistered,
        anyNotifiablePresent = anyNotifiablePresent,
        descriptorCallbackReceived = descriptorCallbackReceived,
        descriptorCallbackSuccess = descriptorCallbackSuccess
    )
}
