package com.syrak.scooterlab

/**
 * Reine, Android-freie Rechenlogik fuer den Trip-Computer und die
 * Reichweitenschaetzung. Bewusst ausgelagert, damit die Regeln ohne
 * Geraet/BLE per Unit-Test geprueft werden koennen.
 */
object TripAccumulator {

    /**
     * Oberhalb dieser Luecke wird NICHT integriert. Telemetrie kommt im
     * Sekundentakt; eine Luecke von mehr als 60 s bedeutet Verbindungsverlust,
     * App-Pause oder Standby. Wuerde man sie mitintegrieren, wuerde die
     * zurueckgelegte Strecke (und die Energie) kuenstlich aufgeblaeht.
     */
    const val MAX_GAP_MILLIS: Long = 60_000L

    /**
     * Liefert das Zeitdelta in Stunden aus zwei Werten einer MONOTONEN Uhr
     * ([android.os.SystemClock.elapsedRealtime]). Ein Wanduhr-Sprung
     * (Zeitzone, NTP, Sommerzeit) kann so niemals Strecke verfaelschen.
     *
     * Rueckgabe 0.0 bei:
     *  - noch keinem Vorgaengerwert ([previousElapsedMillis] <= 0)
     *  - nicht-positivem Delta (Uhr lief rueckwaerts / gleicher Tick)
     *  - zu grosser Luecke (> [MAX_GAP_MILLIS])
     */
    fun deltaHours(previousElapsedMillis: Long, nowElapsedMillis: Long): Double {
        if (previousElapsedMillis <= 0L) return 0.0
        val deltaMillis = nowElapsedMillis - previousElapsedMillis
        if (deltaMillis <= 0L || deltaMillis > MAX_GAP_MILLIS) return 0.0
        return deltaMillis / 3_600_000.0
    }
}

/**
 * Reichweitenschaetzung auf Basis der Nennspannung des ERKANNTEN Modells.
 *
 * Wichtig: Fuer [ScooterModel.UNKNOWN] wird bewusst KEIN Wert geschaetzt.
 * Ein Default von 36 V fuer ein unbekanntes Geraet (z. B. ein 48-V-NAVEE)
 * waere eine erfundene Zahl und damit irrefuehrend.
 *
 * Die Faktoren [ASSUMED_PACK_AH] und [ASSUMED_WH_PER_KM] sind weiterhin
 * fachliche ANNAHMEN und nicht hardwareverifiziert; sie gelten nur fuer
 * bekannte Modelle und sind im Bericht als offenes Risiko dokumentiert.
 */
object RangeEstimator {

    const val ASSUMED_PACK_AH: Double = 10.0
    const val ASSUMED_WH_PER_KM: Double = 15.0

    /** null, wenn Batteriestand fehlt ODER das Modell unbekannt ist. */
    fun estimateKm(model: ScooterModel, batteryPercent: Int?): Double? {
        if (batteryPercent == null) return null
        if (!model.isKnown) return null
        val usableWh = model.nominalVoltage * ASSUMED_PACK_AH * (batteryPercent / 100.0)
        return usableWh / ASSUMED_WH_PER_KM
    }
}
