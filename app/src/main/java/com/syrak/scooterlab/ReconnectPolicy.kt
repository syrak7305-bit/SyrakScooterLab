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
