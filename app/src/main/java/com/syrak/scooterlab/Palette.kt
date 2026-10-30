package com.syrak.scooterlab

import android.graphics.Color

/* ---------------------------------------------------------------------------
 *  DESIGN TOKENS — PHASE 12 PROFESSIONAL REDESIGN
 *  ---------------------------------------------------------------------------
 *  Richtung: professionelles Motorsport-/Telemetrie-/Diagnose-Instrument.
 *  Anthrazit-Hintergrund, leicht hellere Karten, kuehler Cyan-/Teal-Akzent.
 *  Rot NUR fuer Fehler, Gefahr und Notaus. Gruen fuer bestaetigt-positive
 *  Zustaende. Orange fuer Warnungen. Keine bunten Flaeche ohne Bedeutung.
 *
 *  Die alten Namen (ACCENT_RED, ACCENT_BLUE, ...) bleiben erhalten, damit
 *  bestehende Aufrufstellen weiter funktionieren; ihre Bedeutung ist jetzt
 *  aber semantisch eindeutig:
 *    ACCENT_RED    -> DANGER  (Fehler/Gefahr/Notaus)
 *    ACCENT_GREEN  -> SUCCESS (bestaetigt positiv)
 *    ACCENT_ORANGE -> WARNING
 *    ACCENT_BLUE   -> ACCENT  (Cyan, Hauptakzent)
 *    ACCENT_CYAN   -> ACCENT  (Hauptakzent)
 * ------------------------------------------------------------------------- */
object Palette {
    // ---- Flaechen (Anthrazit) ------------------------------------------------
    val BG = Color.rgb(16, 18, 22)            // App-Hintergrund
    val SURFACE = Color.rgb(26, 30, 36)       // Standardkarte
    val SURFACE_ALT = Color.rgb(30, 35, 42)   // Karte, leicht heller
    val SURFACE_DEEP = Color.rgb(12, 14, 18)  // Systemleiste / tiefste Ebene
    val SURFACE_HIGH = Color.rgb(38, 44, 52)  // hervorgehobene Karte

    // ---- Akzente (semantisch) -----------------------------------------------
    val ACCENT = Color.rgb(0, 188, 212)       // Cyan/Teal — Hauptakzent
    val ACCENT_CYAN = ACCENT
    val ACCENT_BLUE = ACCENT                  // Alt-Alias -> Cyan
    val DANGER = Color.rgb(229, 57, 53)       // Rot — NUR Gefahr/Fehler/Notaus
    val ACCENT_RED = DANGER                   // Alt-Alias -> Danger
    val SUCCESS = Color.rgb(46, 204, 113)     // Gruen — bestaetigt positiv
    val ACCENT_GREEN = SUCCESS                // Alt-Alias -> Success
    val WARNING = Color.rgb(243, 156, 18)     // Orange — Warnung
    val ACCENT_ORANGE = WARNING               // Alt-Alias -> Warning
    val ACCENT_YELLOW = Color.rgb(241, 196, 15)

    // ---- Linien / Text -------------------------------------------------------
    val GRID_LINE = Color.rgb(48, 56, 66)
    val OUTLINE = Color.rgb(58, 66, 78)       // dezente Kartenkontur
    val TEXT_PRIMARY = Color.rgb(236, 240, 244)
    val TEXT_SECONDARY = Color.rgb(158, 168, 180)
    val TEXT_MUTED = Color.rgb(120, 130, 142)
    val DIVIDER = Color.rgb(42, 49, 58)

    // ---- Abmessungen ---------------------------------------------------------
    const val RADIUS_DP = 12                  // einheitlicher Kartenradius
    const val BUTTON_HEIGHT_DP = 44           // 40–48 dp
    const val SPACE_SM = 8
    const val SPACE_MD = 16
    const val SPACE_LG = 24
}
