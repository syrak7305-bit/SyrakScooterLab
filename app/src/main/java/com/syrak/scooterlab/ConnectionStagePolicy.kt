package com.syrak.scooterlab

/* ============================================================================
 *  PHASE 16.3.2 — VERBINDUNGSSTATUS (reine Logik, ohne Android)
 *  ---------------------------------------------------------------------------
 *  BELEGTER DEFEKT (Teilauftrag D):
 *
 *  Bis Phase 16.3.1 zeigte die Kopfzeile "Bereit: <Modell> ...", sobald der
 *  GATT-Link stand und `engineReady` true war — auch dann, wenn NIE eine
 *  einzige Notification eintraf. Ein erfolgreicher GATT-Connect wurde damit
 *  als erfolgreiche Telemetrieverbindung dargestellt. Genau das verbietet
 *  der Auftrag ("Ein erfolgreicher GATT-Connect darf NICHT als erfolgreiche
 *  Telemetrieverbindung praesentiert werden").
 *
 *  Diese Datei kapselt die Entscheidung in eine reine Funktion, damit sie
 *  ohne Android-Hardware unit-testbar ist. MainActivity bildet nur noch
 *  [ConnectionStage] auf Text/Farbe ab.
 *
 *  DREI GETRENNTE EBENEN:
 *    1. Verbindung  — der GATT-Link steht (linkState / engineReady)
 *    2. Dienst/Notify — ist die Notify-Kette vollstaendig?
 *    3. Telemetrie  — kommen tatsaechlich dekodierbare Frames?
 *
 *  "Diagnose bereit" erscheint AUSSCHLIESSLICH bei Ebene 3.
 * ==========================================================================*/

/** Grobklasse der Darstellung; MainActivity mappt sie auf Palette-Farben. */
enum class StageSeverity { SUCCESS, WARNING, ACCENT, DANGER, MUTED }

/**
 * Der konkrete Verbindungszustand fuer die Kopfzeile.
 *
 * [text] ist der angezeigte Text, [severity] die Farbklasse.
 */
enum class ConnectionStage(val text: String, val severity: StageSeverity) {
    /** Telemetrie fliesst nachweislich (mind. ein Frame dekodiert). */
    TELEMETRY_ACTIVE("Diagnose bereit \u00b7 Telemetrie aktiv", StageSeverity.SUCCESS),

    /** Daten kommen an, aber die Dekodierung ist (noch) nicht belegt. */
    DATA_RECEIVED_DECODE_UNCLEAR(
        "Verbunden \u00b7 Daten empfangen, Dekodierung unklar", StageSeverity.WARNING
    ),

    /** Notify-Kette vollstaendig, aber es kommt nichts an. */
    NOTIFY_ACTIVE_NO_TELEMETRY(
        "Verbunden \u00b7 Notify aktiv, keine Telemetrie", StageSeverity.WARNING
    ),

    /** Discovery fertig, Notify-Kette aber unvollstaendig. */
    NOTIFY_CHAIN_INCOMPLETE(
        "Verbunden \u00b7 Notify-Kette unvollst\u00e4ndig", StageSeverity.WARNING
    ),

    /** GATT steht (engineReady), aber es ist NICHT belegt, dass Daten fliessen. */
    CONNECTED_NO_TELEMETRY("Verbunden \u00b7 keine Telemetrie", StageSeverity.WARNING),

    /** GATT-Link steht, Service-Discovery laeuft noch. */
    SERVICES_INVESTIGATING("Services werden untersucht", StageSeverity.ACCENT),

    /** Verbindungsaufbau laeuft. */
    CONNECTING("Verbindung wird hergestellt", StageSeverity.ACCENT),

    /** Letzter Verbindungsversuch ist fehlgeschlagen. */
    CONNECT_FAILED("Verbindung fehlgeschlagen", StageSeverity.DANGER),

    /** Scan laeuft. */
    SCANNING("Suche l\u00e4uft", StageSeverity.ACCENT),

    /** Ein Geraet wurde gefunden, aber nicht verbunden. */
    DEVICE_FOUND("Ger\u00e4t erkannt", StageSeverity.ACCENT),

    /** Kein Geraet, keine Verbindung. */
    IDLE("Nicht verbunden", StageSeverity.MUTED)
}

/**
 * Entscheidet den Verbindungsstatus. Rein, ohne Seiteneffekte, voll testbar.
 *
 * Die Reihenfolge ist bewusst: erst die Telemetrie-Ebene (nur mit
 * [TelemetryDiagnosticsSnapshot]), dann die Verbindungs-Ebene, dann Scan/Idle.
 * Ein `engineReady` ohne dekodierte Frames fuehrt NIE zu SUCCESS.
 */
object ConnectionStagePolicy {

    fun evaluate(
        engineReady: Boolean,
        linkState: BleLinkState,
        lastConnectFailed: Boolean,
        isScanning: Boolean,
        deviceFound: Boolean,
        diagnostics: TelemetryDiagnosticsSnapshot?
    ): ConnectionStage {
        val telemetryFlowing = diagnostics != null && diagnostics.decodeSuccess > 0
        val notificationsSeen = diagnostics != null && diagnostics.notifications > 0
        val notifyChainOk = diagnostics?.notifyChainComplete == true

        return when {
            engineReady && telemetryFlowing -> ConnectionStage.TELEMETRY_ACTIVE
            engineReady && notificationsSeen -> ConnectionStage.DATA_RECEIVED_DECODE_UNCLEAR
            engineReady && notifyChainOk -> ConnectionStage.NOTIFY_ACTIVE_NO_TELEMETRY
            engineReady && diagnostics != null && diagnostics.discoveryDone && !notifyChainOk ->
                ConnectionStage.NOTIFY_CHAIN_INCOMPLETE
            engineReady -> ConnectionStage.CONNECTED_NO_TELEMETRY
            linkState == BleLinkState.CONNECTED -> ConnectionStage.SERVICES_INVESTIGATING
            linkState == BleLinkState.CONNECTING -> ConnectionStage.CONNECTING
            lastConnectFailed -> ConnectionStage.CONNECT_FAILED
            isScanning -> ConnectionStage.SCANNING
            deviceFound -> ConnectionStage.DEVICE_FOUND
            else -> ConnectionStage.IDLE
        }
    }
}
