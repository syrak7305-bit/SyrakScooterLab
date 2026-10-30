package com.syrak.scooterlab

import android.graphics.Color

/* ---------------------------------------------------------------------------
 *  DESIGN TOKENS — PHASE 14 "CLEAN PREMIUM"
 *  ---------------------------------------------------------------------------
 *  Richtung: minimalistische, hochwertige Automotive-Diagnose.
 *
 *    Hintergrund      Schwarz / sehr dunkles Anthrazit
 *    Oberflaechen     wenige abgestufte Grautoene
 *    Haupttext        Weiss
 *    Sekundaertext    dezentes Grau
 *    Markenakzent     zurueckhaltendes SYRAK-Rot (C1121F)
 *    Gruen            NUR echte Erfolgsmeldungen
 *    Rot (DANGER)     NUR echte Warnungen / gefaehrliche Aktionen
 *
 *  Bewusst entfernt: Cyan-/Blau-Akzente und Neonfarben. Die alten Namen
 *  (ACCENT_BLUE, ACCENT_ORANGE, ...) bleiben als Kompatibilitaets-Aliase
 *  erhalten, damit keine Aufrufstelle bricht — ihre Bedeutung ist jetzt aber
 *  neutral bzw. semantisch eindeutig.
 * ------------------------------------------------------------------------- */

object Palette {
    // ---- Flaechen ------------------------------------------------------------
    val BG = Color.rgb(10, 12, 15)             // App-Hintergrund (fast schwarz)
    val SURFACE = Color.rgb(20, 23, 28)        // Standardkarte
    val SURFACE_ALT = Color.rgb(25, 29, 35)    // Karte, leicht heller
    val SURFACE_DEEP = Color.rgb(6, 8, 10)     // Systemleiste / tiefste Ebene
    val SURFACE_HIGH = Color.rgb(34, 39, 47)   // hervorgehobene Karte

    // ---- Marke ---------------------------------------------------------------
    /** Zurueckhaltendes SYRAK-Rot — einziger Markenakzent. */
    val BRAND = Color.rgb(193, 18, 31)

    // ---- Semantik ------------------------------------------------------------
    /** Hauptakzent = Markenrot. Fuer normale, aktive Aktionen. */
    val ACCENT = BRAND
    val ACCENT_CYAN = BRAND
    /** Neutraler Stahlton fuer sekundaere/technische Aktionen (kein Blau mehr). */
    val ACCENT_BLUE = Color.rgb(139, 147, 161)

    /** Rot NUR fuer Gefahr / Fehler / Notaus. */
    val DANGER = Color.rgb(229, 72, 77)
    val ACCENT_RED = DANGER

    /** Gruen NUR fuer bestaetigte Erfolge. */
    val SUCCESS = Color.rgb(63, 185, 80)
    val ACCENT_GREEN = SUCCESS

    /** Orange nur fuer echte Warnungen / eingeschraenkte Zustaende. */
    val WARNING = Color.rgb(210, 153, 34)
    val ACCENT_ORANGE = WARNING
    val ACCENT_YELLOW = WARNING

    // ---- Linien / Text -------------------------------------------------------
    val GRID_LINE = Color.rgb(35, 40, 48)
    val OUTLINE = Color.rgb(42, 48, 56)        // dezente Kartenkontur
    val TEXT_PRIMARY = Color.rgb(242, 244, 247)
    val TEXT_SECONDARY = Color.rgb(162, 170, 180)
    val TEXT_MUTED = Color.rgb(111, 119, 129)
    val DIVIDER = Color.rgb(31, 36, 43)

    // ---- Abmessungen ---------------------------------------------------------
    const val RADIUS_DP = 14                   // einheitlicher Kartenradius
    const val BUTTON_HEIGHT_DP = 48            // grosszuegige Touch-Flaeche
    const val SPACE_SM = 8
    const val SPACE_MD = 16
    const val SPACE_LG = 24
}
