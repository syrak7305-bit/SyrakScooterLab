package com.syrak.scooterlab

import java.util.Locale

/* ============================================================================
 *  GERAETE-IDENTIFIKATION (Phase 8)
 *  ----------------------------------------------------------------------------
 *  WARUM DIESE DATEI EXISTIERT
 *  Bis Phase 8 gab es nur EINE Erkennungsebene: [ScooterModel] mit
 *  Keyword-Scoring. Hersteller (Brand) und Modell waren vermischt, KuKirin
 *  fehlte komplett, und der Scan-Kandidatenfilter lag als private Inline-Liste
 *  im ScanCallback von MainActivity — damit ohne Unit-Test pruefbar.
 *
 *  Diese Datei trennt die Ebenen sauber (Multi-Brand-Vorbereitung):
 *    1. [ScooterManufacturer]  — Hersteller/Brand, rein namensbasiert
 *    2. [ScooterModel]         — konkretes Modell (Models.kt)
 *    3. [DetectionConfidence]  — Erkennungssicherheit (Models.kt)
 *    4. [ScooterCandidateFilter] — "ist das ueberhaupt ein Scooter?"
 *    5. [TuningSafetyGate]     — Sicherheitssperre fuer Tuning-Schreibbefehle
 *
 *  GRUNDREGEL: Ein BLE-Name ist eine HEURISTIK, kein Beweis. Es werden hier
 *  KEINE BLE-UUIDs, Protokoll-Offsets oder Firmware-Daten erfunden. Die
 *  Herstellerzuordnung sagt nur "der Name deutet auf Marke X" — nicht
 *  "das Geraet spricht Protokoll Y".
 * ==========================================================================*/

/**
 * Hersteller-/Marken-Ebene der Erkennung.
 *
 * Bewusst KONSERVATIV: nur eindeutige Marken-Tokens im BLE-Namen. Ein Name
 * ohne Marken-Token ergibt [UNKNOWN] — es wird nichts geraten.
 */
enum class ScooterManufacturer(
    val displayName: String,
    /** Namens-Tokens, die auf diese Marke hindeuten (klein geschrieben). */
    val keywords: List<String>
) {
    NAVEE("Navee", listOf("navee")),
    XIAOMI("Xiaomi", listOf("xiaomi", "mi scooter", "miscooter", "mi electric", "m365")),
    NINEBOT("Ninebot", listOf("ninebot")),
    SEGWAY("Segway", listOf("segway")),
    KUKIRIN("KuKirin", listOf("kukirin", "ku kirin")),
    SOFLOW("SoFlow", listOf("soflow")),
    UNKNOWN("Unbekannt", emptyList());

    val isKnown: Boolean get() = this != UNKNOWN

    companion object {
        /**
         * Ordnet einen BLE-Namen einer Marke zu.
         *
         * Reihenfolge ist bewusst festgelegt und deterministisch:
         * "Segway Ninebot Max G30" enthaelt BEIDE Tokens. Ninebot hat Vorrang,
         * weil Segway-Ninebot der gemeinsame Hersteller ist und "ninebot" das
         * spezifischere ESC-Merkmal darstellt. "Segway" allein -> [SEGWAY].
         *
         * @return [UNKNOWN] bei null/leer/ohne Marken-Token.
         */
        fun classify(name: String?): ScooterManufacturer {
            val n = name?.lowercase(Locale.ROOT)?.trim() ?: return UNKNOWN
            if (n.isEmpty()) return UNKNOWN
            // Feste Prioritaetsliste (nicht von der Enum-Reihenfolge abhaengig,
            // damit ein spaeteres Umsortieren der Enum nichts aendert).
            val priority = listOf(KUKIRIN, NAVEE, XIAOMI, NINEBOT, SEGWAY, SOFLOW)
            for (brand in priority) {
                if (brand.keywords.any { n.contains(it) }) return brand
            }
            return UNKNOWN
        }
    }
}

/**
 * Entscheidet, ob ein gescanntes Geraet ueberhaupt als Scooter-Kandidat in der
 * Liste erscheint. Reine Funktion, damit sie ohne Android testbar ist.
 *
 * Vorher lag genau diese Logik als private Inline-Liste im ScanCallback.
 * KuKirin fehlte dort.
 */
object ScooterCandidateFilter {

    /** Namens-Tokens, die auf einen E-Scooter hindeuten. */
    val KEYWORDS: List<String> = listOf(
        "scooter", "e-scooter", "escooter",
        "ninebot", "segway", "xiaomi", "navee", "m365", "soflow", "kukirin",
        "mi scooter", "mi electric"
    )

    /**
     * true, wenn [name] auf einen Scooter hindeutet.
     *
     * Ein bekannter Hersteller genuegt ebenfalls (deckt Namen ab, die nur den
     * Markennamen ohne das Wort "scooter" enthalten, z. B. "NAVEE").
     * "Unknown Device" und leere Namen sind bewusst KEINE Kandidaten.
     */
    fun looksLikeScooter(name: String?): Boolean {
        val n = name?.trim() ?: return false
        if (n.isEmpty()) return false
        if (n.equals("Unknown Device", ignoreCase = true)) return false
        if (ScooterManufacturer.classify(n).isKnown) return true
        return KEYWORDS.any { n.contains(it, ignoreCase = true) }
    }
}

/**
 * Sicherheitssperre fuer Tuning-Schreibbefehle (Regel 6 der Phase 8).
 *
 * Bis echte Hardwaretests und Protokollvalidierung erfolgt sind, duerfen
 * KEINE Tuning-Befehle automatisch gesendet werden. Die Funktion bleibt
 * erhalten, ist aber standardmaessig GESPERRT.
 *
 * Freigabe erfordert BEIDES:
 *  - eine bewusste Bestaetigung des Nutzers ([hardwareVerified], pro Sitzung,
 *    nicht persistiert), UND
 *  - ein bekanntes Modell ([modelKnown]) — bei "Unbekanntes Modell" wird nie
 *    geschrieben, weil dann weder Registerkarte noch Protokoll belegt sind.
 *
 * NICHT betroffen: Not-Aus, Unlock-Handshake, Reboot und reine Lese-Befehle.
 * Diese laufen weiterhin ueber [MainActivity.sendCommand] ohne dieses Gate.
 */
object TuningSafetyGate {

    /** true, wenn ein Tuning-Schreibbefehl gesendet werden darf. */
    fun canSendTuning(hardwareVerified: Boolean, modelKnown: Boolean): Boolean =
        hardwareVerified && modelKnown

    /**
     * Menschenlesbare Begruendung, warum ein Tuning-Befehl gesperrt ist.
     * @return null, wenn NICHT gesperrt.
     */
    fun blockReason(hardwareVerified: Boolean, modelKnown: Boolean): String? = when {
        !modelKnown ->
            "Modell nicht erkannt — Tuning gesperrt (Registerkarte/Protokoll unbelegt)."
        !hardwareVerified ->
            "Hardware-Kompatibilitaet nicht bestaetigt — Tuning gesperrt. " +
                "Bestaetigung in CONFIG erforderlich."
        else -> null
    }
}

/* ============================================================================
 *  AUTO-HANDSHAKE-POLICY  (Phase 8.1 — Sicherheitsaudit, Punkt 1)
 *  ---------------------------------------------------------------------------
 *  BELEGTER BEFUND: In `BleEngine.afterMtu()` wurde der Unlock-Handshake
 *  (Brightway: `AA 55 01 01 01`, CMD 0x01 / Payload 0x01) AUTOMATISCH beim
 *  Verbindungsaufbau gesendet, sobald der Adapter `requiresUnlock` meldet.
 *  Der Adapter wird allein aus dem GATT-Layout (Service FFE0) gewaehlt — also
 *  auch fuer ein voellig UNBEKANNTES Geraet, das zufaellig FFE0 exponiert.
 *  Damit wurde ohne Modell- und ohne Hardware-Verifikation ein Schreibbefehl
 *  an ein unbekanntes Geraet gesendet. Das widerspricht Regel 6
 *  ("keine unbekannten BLE-Schreibbefehle").

 *  FIX: Der EAGER-Handshake beim Verbindungsaufbau ist nur noch erlaubt, wenn
 *  das Modell erkannt UND die Hardware-Kompatibilitaet vom Nutzer bestaetigt
 *  wurde — also genau dann, wenn [TuningSafetyGate] Schreibbefehle freigibt.
 *  Der LAZY-Handshake in `enqueueCommand()` bleibt unveraendert: er feuert nur
 *  unmittelbar vor einem tatsaechlich erlaubten Befehl (Tuning nach Freigabe
 *  oder Not-Aus). Not-Aus bleibt dadurch voll funktionsfaehig.
 * ==========================================================================*/
object AutoHandshakePolicy {

    /**
     * true, wenn der Unlock-Handshake bereits beim Verbindungsaufbau gesendet
     * werden darf.
     *
     * @param requiresUnlock     Adapter verlangt einen Handshake (Brightway).
     * @param modelKnown         Modell wurde erkannt (nicht UNKNOWN).
     * @param hardwareVerified   Nutzer hat Hardware-Kompatibilitaet bestaetigt.
     */
    fun shouldSendEagerHandshake(
        requiresUnlock: Boolean,
        modelKnown: Boolean,
        hardwareVerified: Boolean
    ): Boolean = requiresUnlock && TuningSafetyGate.canSendTuning(hardwareVerified, modelKnown)

    /** Begruendung, warum der Eager-Handshake unterdrueckt wird (fuer das Log). */
    fun suppressionReason(
        requiresUnlock: Boolean,
        modelKnown: Boolean,
        hardwareVerified: Boolean
    ): String? = when {
        !requiresUnlock -> null
        shouldSendEagerHandshake(requiresUnlock, modelKnown, hardwareVerified) -> null
        !modelKnown ->
            "Unlock-Handshake beim Verbindungsaufbau unterdrueckt: Modell nicht erkannt."
        else ->
            "Unlock-Handshake beim Verbindungsaufbau unterdrueckt: " +
                "Hardware-Kompatibilitaet nicht bestaetigt."
    }
}
