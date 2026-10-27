package com.syrak.scooterlab

/* ============================================================================
 *  BLE-LINK-ZUSTAND (reine Logik, ohne Android-Abhaengigkeiten)
 *  ---------------------------------------------------------------------------
 *  Die UI darf keinen Verbindungszustand "raten". Dieser kleine Zustandsautomat
 *  macht die erlaubten Uebergaenge explizit und damit testbar:
 *
 *    IDLE --CONNECT_REQUESTED--> CONNECTING --GATT_CONNECTED--> CONNECTED
 *    CONNECTED --READY--> READY
 *    {CONNECTING, CONNECTED, READY} --DISCONNECTED/CONNECT_TIMEOUT--> IDLE
 *
 *  Doppelte oder verspaetete Callbacks (z. B. GATT_CONNECTED nach DISCONNECTED)
 *  werden ignoriert, statt die Anzeige in einen falschen Zustand zu bringen.
 * ==========================================================================*/
enum class BleLinkState {
    IDLE,
    CONNECTING,
    CONNECTED,
    READY;

    /** true, wenn die Verbindung tatsaechlich nutzbar ist (Discovery + Notify fertig). */
    val isUsable: Boolean get() = this == READY

    /** true, solange ein Verbindungsaufbau laeuft. */
    val isBusy: Boolean get() = this == CONNECTING || this == CONNECTED

    /**
     * Berechnet den Folgezustand. Unerlaubte Uebergaenge liefern unveraendert
     * [this] zurueck (kein Werfen, damit Callbacks robust bleiben).
     */
    fun transition(event: BleLinkEvent): BleLinkState = when (event) {
        BleLinkEvent.CONNECT_REQUESTED ->
            if (this == IDLE) CONNECTING else this
        BleLinkEvent.GATT_CONNECTED ->
            if (this == CONNECTING || this == CONNECTED) CONNECTED else this
        BleLinkEvent.READY ->
            if (this == CONNECTED || this == CONNECTING) READY else this
        BleLinkEvent.DISCONNECTED, BleLinkEvent.CONNECT_TIMEOUT -> IDLE
    }

    companion object {
        fun label(state: BleLinkState): String = when (state) {
            IDLE -> "IDLE"
            CONNECTING -> "CONNECTING"
            CONNECTED -> "CONNECTED"
            READY -> "READY"
        }
    }
}

/** Ereignisse, die den Link-Zustand veraendern koennen. */
enum class BleLinkEvent {
    CONNECT_REQUESTED,
    GATT_CONNECTED,
    READY,
    DISCONNECTED,
    CONNECT_TIMEOUT
}
