package com.syrak.scooterlab.recognition

import android.content.SharedPreferences

/* ============================================================================
 *  PHASE 17.4 — ANDROID-SPEICHERADAPTER FUER HARDWARE-TESTS
 *  ---------------------------------------------------------------------------
 *  Nutzt BEWUSST denselben SharedPreferences-Store wie der Rest der App
 *  ("SyrakPrefs"). Es wird KEIN zweites Speichersystem eingefuehrt.
 *
 *  Atomaritaet:
 *   • Geschrieben wird mit [SharedPreferences.Editor.commit] (synchron). Android
 *     schreibt dabei in eine temporaere Datei und ersetzt die alte atomar.
 *   • Vor dem Ueberschreiben wird der bisherige Stand als Backup mitgeschrieben
 *     — in DEMSELBEN Commit, damit Haupt- und Backup-Stand konsistent sind.
 *   • Schlaegt der Commit fehl, bleibt der alte Stand unveraendert.
 * ==========================================================================*/
class SharedPreferencesHardwareTestPersistence(
    private val prefs: SharedPreferences
) : HardwareTestPersistence {

    override fun read(): String? = prefs.getString(KEY_PRIMARY, null)

    override fun readBackup(): String? = prefs.getString(KEY_BACKUP, null)

    override fun write(payload: String): Boolean {
        val previous = prefs.getString(KEY_PRIMARY, null)
        val editor = prefs.edit().putString(KEY_PRIMARY, payload)
        if (!previous.isNullOrBlank()) {
            editor.putString(KEY_BACKUP, previous)
        }
        return try {
            editor.commit()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * PHASE 17.4.1: schreibt nur den Hauptstand. Das Backup bleibt unberuehrt,
     * damit ein aus dem Backup wiederhergestellter Stand das gute Backup nicht
     * durch den defekten Hauptstand ersetzt.
     */
    override fun writePrimaryOnly(payload: String): Boolean = try {
        prefs.edit().putString(KEY_PRIMARY, payload).commit()
    } catch (_: Throwable) {
        false
    }

    companion object {
        const val KEY_PRIMARY = "hardware_tests_json"
        const val KEY_BACKUP = "hardware_tests_json_backup"
    }
}
