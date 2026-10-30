package com.syrak.scooterlab

/* ============================================================================
 *  PHASE 13 — PREMIUM BRANDING, SPLASH & GATE-LOGIK
 *  ----------------------------------------------------------------------------
 *  Diese Datei enthaelt ausschliesslich plattformunabhaengige Logik (keine
 *  Android-Abhaengigkeiten), damit sie ohne Geraet/Emulator unit-getestet
 *  werden kann:
 *
 *    • CountdownGate   — monotone, nicht umgehbare Wartezeit (Disclaimer/Land)
 *    • ScrollGate      — "bis zum Ende gelesen"-Pruefung
 *    • SplashSequence  — echte Startschritte inkl. Fortschrittsberechnung
 *
 *  GRUNDREGEL: Es werden KEINE Hardwarepruefungen, Verbindungen oder
 *  Gesetzesangaben erfunden. Die Splash-Texte beschreiben ausschliesslich
 *  Vorgaenge, die die App tatsaechlich ausfuehrt.
 * ==========================================================================*/

/* ---------------------------------------------------------------------------
 *  COUNTDOWN-GATE
 * -------------------------------------------------------------------------*/

/**
 * Momentaufnahme eines Countdowns. Alle Werte werden aus einer monotonen
 * Zeitbasis ([SystemClock.elapsedRealtime] beim Aufrufer) berechnet, damit
 * ein Wechsel der Systemuhr den Countdown nicht verkuerzen kann.
 */
data class CountdownState(
    val requiredMs: Long,
    val elapsedMs: Long
) {
    /** Restzeit in Millisekunden, nie negativ. */
    val remainingMs: Long get() = (requiredMs - elapsedMs).coerceAtLeast(0L)

    /** Restzeit in ganzen Sekunden, aufgerundet (sichtbarer Countdown). */
    val remainingSeconds: Int get() = ((remainingMs + 999L) / 1000L).toInt()

    /** true, sobald die Wartezeit vollstaendig abgelaufen ist. */
    val finished: Boolean get() = remainingMs == 0L

    /** Fortschritt 0..1 (fuer Fortschrittsbalken). */
    val progress: Float
        get() = if (requiredMs <= 0L) 1f
        else (elapsedMs.toFloat() / requiredMs.toFloat()).coerceIn(0f, 1f)
}

object CountdownGate {

    /**
     * Berechnet den Zustand aus Start- und Jetzt-Zeit. Negative Werte
     * (Zeitsprung nach hinten) werden als "noch nicht begonnen" behandelt,
     * damit niemals ein falscher Freigabe-Zustand entsteht.
     */
    fun state(requiredMs: Long, startedAtElapsedMs: Long, nowElapsedMs: Long): CountdownState {
        val elapsed = (nowElapsedMs - startedAtElapsedMs).coerceAtLeast(0L)
        return CountdownState(requiredMs.coerceAtLeast(0L), elapsed)
    }

    /**
     * Liefert den Startzeitpunkt fuer einen neu beginnenden Countdown.
     * Bewusst die aktuelle monotone Zeit — nie 0, damit ein vergessener
     * Startwert nicht sofort "abgelaufen" bedeutet.
     */
    fun start(nowElapsedMs: Long): Long = nowElapsedMs

    /**
     * Stellt einen gespeicherten Startzeitpunkt wieder her.
     *
     * • null / 0            -> neuer Start (kein Zustand vorhanden)
     * • Start in der Zukunft -> neuer Start (Reboot / Zeitsprung)
     * • sonst                -> gespeicherter Start (echte Wartezeit zaehlt)
     *
     * Ein bereits abgelaufener Countdown bleibt abgelaufen: die Wartezeit ist
     * tatsaechlich vergangen, es entsteht kein "falscher" Freigabe-Zustand.
     */
    fun restore(savedStartElapsedMs: Long?, nowElapsedMs: Long): Long {
        if (savedStartElapsedMs == null || savedStartElapsedMs <= 0L) return nowElapsedMs
        if (savedStartElapsedMs > nowElapsedMs) return nowElapsedMs
        return savedStartElapsedMs
    }
}

/* ---------------------------------------------------------------------------
 *  SCROLL-GATE ("bis zum Ende gelesen")
 * -------------------------------------------------------------------------*/

object ScrollGate {

    /**
     * true, wenn der Inhalt vollstaendig sichtbar ist ODER bis zum Ende
     * gescrollt wurde (mit Toleranz fuer Rundungs-/Padding-Unterschiede).
     */
    fun isAtBottom(
        scrollY: Int,
        contentHeight: Int,
        viewportHeight: Int,
        tolerancePx: Int
    ): Boolean {
        if (contentHeight <= 0 || viewportHeight <= 0) return false
        if (contentHeight <= viewportHeight + tolerancePx) return true
        val maxScroll = contentHeight - viewportHeight
        return scrollY >= maxScroll - tolerancePx
    }
}

/* ---------------------------------------------------------------------------
 *  SPLASH-SEQUENZ
 * -------------------------------------------------------------------------*/

/**
 * Ein echter Startschritt. [detail] wird erst gesetzt, wenn der Schritt
 * tatsaechlich ausgefuehrt wurde — die UI zeigt nie einen erfundenen Zustand.
 */
data class SplashStage(
    val label: String,
    val detail: String = ""
)

object SplashSequence {

    /** Anzahl der realen Startschritte (ohne "SYSTEM READY"). */
    const val WORK_STAGES = 4

    fun initializing() = SplashStage("SYRAK SYSTEM INITIALIZING")
    fun modules() = SplashStage("LOADING DIAGNOSTIC MODULES")
    fun bluetooth() = SplashStage("CHECKING BLUETOOTH SERVICES")
    fun dashboard() = SplashStage("PREPARING DASHBOARD")
    fun ready() = SplashStage("SYSTEM READY")

    /**
     * Gesamtfortschritt 0..1 aus Schrittindex und Teilfortschritt des
     * aktuellen Schritts. Der letzte Schritt ("SYSTEM READY") ist 1.0.
     */
    fun progress(stageIndex: Int, stageFraction: Float = 0f): Float {
        val total = WORK_STAGES + 1
        val idx = stageIndex.coerceIn(0, total - 1)
        // Der letzte Schritt ist "SYSTEM READY" und damit immer 100 %.
        if (idx == total - 1) return 1f
        val frac = stageFraction.coerceIn(0f, 1f)
        return ((idx + frac) / total.toFloat()).coerceIn(0f, 1f)
    }

    /** Prozentanzeige fuer die UI (0..100, nie 100 vor "SYSTEM READY"). */
    fun percent(stageIndex: Int, stageFraction: Float = 0f): Int =
        (progress(stageIndex, stageFraction) * 100f).toInt().coerceIn(0, 100)
}
