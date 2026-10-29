package com.syrak.scooterlab

/* ============================================================================
 *  RECONNECT-POLICY  (reine Logik, ohne Android-Abhaengigkeiten)
 *  ---------------------------------------------------------------------------
 *  Die Entscheidung "darf jetzt ein Auto-Reconnect geplant werden?" lag bisher
 *  als if-Kette in [MainActivity]. Sie ist sicherheitsrelevant:
 *
 *    • nach einem BEWUSSTEN Trennen darf NIE automatisch verbunden werden
 *    • ist Auto-Reconnect in den Einstellungen aus, darf nichts passieren
 *    • nach [maxAttempts] Versuchen muss Schluss sein (keine Endlosschleife)
 *
 *  Deshalb steht sie hier als reine Funktion und ist ohne Android/Hardware
 *  testbar. [MainActivity] nutzt ausschliesslich diese Implementierung.
 * ==========================================================================*/
object ReconnectPolicy {

    /**
     * true, wenn ein Reconnect geplant werden darf.
     *
     * @param autoReconnect     Einstellung "Auto-Reconnect" aus der CONFIG.
     * @param userInitiated     true, wenn der Nutzer selbst getrennt hat.
     * @param attempts          bereits unternommene Reconnect-Versuche.
     * @param maxAttempts       Obergrenze (>= 1).
     */
    fun shouldSchedule(
        autoReconnect: Boolean,
        userInitiated: Boolean,
        attempts: Int,
        maxAttempts: Int
    ): Boolean {
        if (!autoReconnect) return false
        if (userInitiated) return false
        if (maxAttempts <= 0) return false
        return attempts < maxAttempts
    }

    /**
     * true, wenn ein bereits GEPLANTES Reconnect-Runnable beim Feuern noch
     * ausgefuehrt werden darf.
     *
     * Belegte Luecke (Phase 7): Zwischen dem Planen und dem Feuern liegt ein
     * Backoff-Fenster (bis zu 8 s). Wurde "Auto-Reconnect" in dieser Zeit in der
     * CONFIG abgeschaltet, feuerte das Runnable trotzdem und baute eine
     * Verbindung auf — entgegen der Einstellung. Zusaetzlich wird hier erneut
     * geprueft, ob der Nutzer inzwischen bewusst getrennt hat.
     */
    fun shouldFire(autoReconnect: Boolean, userInitiated: Boolean): Boolean =
        autoReconnect && !userInitiated

    /**
     * Wartezeit vor dem naechsten Versuch: exponentieller Backoff
     * (base, base*2, base*4, ...), hart begrenzt durch [maxDelayMillis].
     * Negative Eingaben werden auf 0 geklemmt, damit postDelayed nie negativ wird.
     */
    fun delayMillis(attempt: Int, baseMillis: Long, maxDelayMillis: Long): Long {
        if (baseMillis <= 0L) return 0L
        val shift = attempt.coerceIn(0, 20)
        val raw = baseMillis shl shift
        val capped = if (raw <= 0L) maxDelayMillis else minOf(raw, maxDelayMillis)
        return capped.coerceAtLeast(0L)
    }
}
