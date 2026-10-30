package com.syrak.scooterlab

/* ============================================================================
 *  PHASE 11 — ZENTRALE SCHREIB-/READ-ONLY-SPERRE
 *  ---------------------------------------------------------------------------
 *  Buendelt alle Bedingungen, unter denen ein Schreibbefehl an einen Scooter
 *  gesendet werden darf, an EINER Stelle. Die bestehende [TuningSafetyGate]
 *  bleibt unveraendert erhalten und wird hier nur zusaetzlich herangezogen.
 *
 *  Verbindliche Regeln (Phase 11, Abschnitt D):
 *   1. Unbekannte Scooter bleiben standardmaessig READ-ONLY.
 *   2. Keine Schreibbefehle ohne Modell- UND Hardware-Verifikation.
 *   3. Kein automatischer Unlock fuer unbekannte Geraete
 *      (siehe [AutoHandshakePolicy]).
 *   4. Kein unvalidiertes Flashen (siehe [FirmwareValidator]).
 *   5. Keine unbestaetigten Geschwindigkeitsaenderungen.
 *
 *  Lesen (READ/NOTIFY) ist IMMER erlaubt — es ist die Grundlage jeder
 *  ehrlichen Diagnose und veraendert das Geraet nicht.
 * ==========================================================================*/

/** Ergebnis der Schreibfreigabe-Pruefung. */
enum class WriteDecision(val allowed: Boolean) {
    ALLOWED(true),
    BLOCKED_UNKNOWN_MODEL(false),
    BLOCKED_HARDWARE_NOT_VERIFIED(false),
    BLOCKED_NO_MODEL_ADAPTER(false),
    BLOCKED_READ_ONLY_ADAPTER(false)
}

object WriteGuard {

    /**
     * Entscheidet, ob ein Schreibbefehl gesendet werden darf.
     *
     * @param model das erkannte Modell (UNKNOWN = nicht erkannt)
     * @param hardwareVerified Nutzerbestaetigung der Hardware-Kompatibilitaet
     * @param adapter der aktuell aktive Adapter (optional; wird sonst aus dem
     *   Modell aufgeloest)
     */
    fun evaluate(
        model: ScooterModel,
        hardwareVerified: Boolean,
        adapter: ScooterProtocolAdapter? = null
    ): WriteDecision {
        // 1. Unbekanntes Modell -> niemals schreiben.
        if (!model.isKnown) return WriteDecision.BLOCKED_UNKNOWN_MODEL

        // 2. Bestehende Sicherheitssperre respektieren (Hardware-Verifikation).
        if (!TuningSafetyGate.canSendTuning(hardwareVerified, modelKnown = true)) {
            return WriteDecision.BLOCKED_HARDWARE_NOT_VERIFIED
        }

        // 3. Es muss ein modellspezifischer, schreibfaehiger Adapter existieren.
        val caps = adapter?.capabilities ?: ProtocolAdapters.capabilitiesFor(model)
        if (caps == null || !caps.supportsModel(model)) {
            return WriteDecision.BLOCKED_NO_MODEL_ADAPTER
        }

        // 4. Read-only-Adapter duerfen niemals schreiben.
        if (!caps.supportsAnyWrite()) return WriteDecision.BLOCKED_READ_ONLY_ADAPTER

        return WriteDecision.ALLOWED
    }

    /** true, wenn fuer dieses Geraet ausschliesslich gelesen werden darf. */
    fun isReadOnly(
        model: ScooterModel,
        hardwareVerified: Boolean,
        adapter: ScooterProtocolAdapter? = null
    ): Boolean = !evaluate(model, hardwareVerified, adapter).allowed

    /**
     * Menschenlesbare Begruendung, warum Schreiben gesperrt ist.
     * @return null, wenn Schreiben erlaubt ist.
     */
    fun blockReason(
        model: ScooterModel,
        hardwareVerified: Boolean,
        adapter: ScooterProtocolAdapter? = null
    ): String? = when (evaluate(model, hardwareVerified, adapter)) {
        WriteDecision.ALLOWED -> null
        WriteDecision.BLOCKED_UNKNOWN_MODEL ->
            "Modell nicht erkannt — Schreiben gesperrt (read-only). " +
                "Registerkarte/Protokoll unbelegt."
        WriteDecision.BLOCKED_HARDWARE_NOT_VERIFIED ->
            "Hardware-Kompatibilität nicht bestätigt — Schreiben gesperrt. " +
                "Bestätigung in CONFIG erforderlich."
        WriteDecision.BLOCKED_NO_MODEL_ADAPTER ->
            "Für ${model.displayName} ist kein modellspezifischer, schreibfähiger " +
                "Adapter hinterlegt. Schreiben bleibt gesperrt."
        WriteDecision.BLOCKED_READ_ONLY_ADAPTER ->
            "Der Adapter für ${model.displayName} ist read-only deklariert. " +
                "Schreiben bleibt gesperrt."
    }

    /** Kurze Statuszeile fuer die UI. */
    fun statusLine(
        model: ScooterModel,
        hardwareVerified: Boolean,
        adapter: ScooterProtocolAdapter? = null
    ): String {
        val decision = evaluate(model, hardwareVerified, adapter)
        return if (decision.allowed) {
            "Schreiben: freigegeben (${model.displayName})"
        } else {
            "Schreiben: GESPERRT · Lesen: erlaubt (${decision.name})"
        }
    }
}
