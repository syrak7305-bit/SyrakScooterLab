package com.syrak.scooterlab

/* ============================================================================
 *  VERBINDUNGS-/SCAN-LEBENSZYKLUS (reine Logik, ohne Android-Abhaengigkeiten)
 *  ---------------------------------------------------------------------------
 *  Zwei kleine, testbare Regeln, die zwei in Phase 5 belegte Defekte in
 *  [MainActivity] absichern:
 *
 *  1) [ScanTimeoutGuard]        — ein 12-s-Scan-Timeout eines FRUEHEREN Scans
 *     darf einen NEU gestarteten Scan nicht vorzeitig beenden.
 *  2) [ConnectionSwitchPolicy]  — vor dem Aufbau einer neuen BLE-Engine muss eine
 *     bereits bestehende Verbindung getrennt werden (keine zwei GATT-Clients).
 *
 *  Beide Klassen enthalten KEINE Android-Abhaengigkeit und sind damit direkt
 *  per JUnit testbar.
 * ==========================================================================*/

/**
 * Schuetzt vor veralteten Scan-Timeouts.
 *
 * Belegter Defekt (Phase 5): In [MainActivity] blieb das Timeout-Runnable eines
 * manuell beendeten Scans in der Handler-Queue. Startete der Nutzer innerhalb
 * der 12 s einen neuen Scan, beendete der alte Timeout den neuen Scan vorzeitig
 * und erzeugte zusaetzlich eine irrefuehrende Log-Zeile "BLE-Scan beendet.".
 *
 * Jeder Scan erhaelt eine Generation. Ein Timeout darf nur wirken, wenn seine
 * Generation noch die aktuelle ist.
 */
class ScanTimeoutGuard {

    private var generation = 0

    /** Startet einen neuen Scan und liefert dessen Generation. */
    fun begin(): Int {
        generation += 1
        return generation
    }

    /** Beendet den aktuellen Scan; bereits geplante Timeouts werden ungueltig. */
    fun end() {
        generation += 1
    }

    /** true, wenn [token] zum aktuell laufenden Scan gehoert. */
    fun isCurrent(token: Int): Boolean = generation != 0 && token == generation
}

/**
 * Entscheidet, ob vor dem Neuaufbau einer Verbindung eine bestehende Engine
 * getrennt werden muss.
 *
 * Belegter Defekt (Phase 5): [MainActivity.connectToDevice] erzeugte beim
 * Wechsel auf ein ANDERES Geraet eine zweite [BleEngine], ohne die bestehende zu
 * trennen. Folge: zwei parallele GATT-Verbindungen, doppelte Telemetrie und ein
 * Verbindungs-Leak. Es darf nie mehr als eine Engine gleichzeitig geben.
 */
object ConnectionSwitchPolicy {

    /**
     * @param hasExistingEngine ob bereits eine Engine (verbunden/verbindend)
     *        existiert.
     * @return true, wenn die bestehende Verbindung VOR dem Neuaufbau getrennt
     *         werden muss. Das ist bei jeder vorhandenen Engine der Fall.
     */
    fun mustTearDownExisting(hasExistingEngine: Boolean): Boolean = hasExistingEngine
}
