package com.syrak.scooterlab.recognition

import com.syrak.scooterlab.ScooterManufacturer

/* ============================================================================
 *  PHASE 17.1 — ERKENNUNGS-VOKABULAR (reine Logik, ohne Android-Abhaengigkeit)
 *  ---------------------------------------------------------------------------
 *  Diese Datei definiert NUR Begriffe und Bedeutungen. Sie enthaelt bewusst
 *  keine Erkennungsentscheidung — die passiert in [RecognitionEngine].
 *
 *  Grundsaetze (aus dem Auftrag, woertlich bindend):
 *   • Ein gefundenes Bluetooth-Geraet ist NICHT automatisch ein erkannter Roller.
 *   • Eine erkannte Marke ist NICHT automatisch ein bestaetigtes Modell.
 *   • Ein bestaetigtes Modell ist NICHT automatisch Beweis fuer jede Funktion.
 *   • Fehlende Daten zaehlen NIE als positives Merkmal.
 *   • Es gibt KEINE kuenstlichen Genauigkeits-Prozente.
 * ==========================================================================*/

/**
 * Erkennungsqualitaet der Hardware-Erkennung.
 *
 * Die Stufen sind bewusst streng geordnet. Jede Stufe beschreibt, was
 * tatsaechlich belegt ist — nicht, was plausibel waere.
 */
enum class RecognitionConfidence {
    /**
     * Konkretes Modell durch bestaetigte Merkmale belegt.
     *
     * Voraussetzung: ein Profil mit [HardwareTestStatus.CONFIRMED_ON_HARDWARE]
     * UND ausschliesslich Merkmale der Stufe [EvidenceGrade.CONFIRMED] UND
     * keine Widersprueche. Da im Projekt derzeit KEIN Profil hardware-
     * bestaetigt ist, kann dieser Zustand aktuell nicht erreicht werden —
     * das ist ehrlich und kein Defekt.
     */
    CONFIRMED,

    /**
     * Konkretes Modell wahrscheinlich, aber nicht bestaetigt.
     *
     * Voraussetzung: mindestens zwei unabhaengige Merkmale der Stufe
     * [EvidenceGrade.PROBABLE] oder besser, konsistente Marke, keine
     * Widersprueche.
     */
    PROBABLE,

    /**
     * Nur ein Marken-/Familienhinweis. Das konkrete Modell ist unbekannt.
     *
     * Voraussetzung: mindestens ein Marken- oder Familienmerkmal, aber zu
     * wenig fuer ein Modell — oder ein Widerspruch, der die Modellstufe
     * herabstuft.
     */
    FAMILY_ONLY,

    /** Marke und Modell unbekannt. Keine belastbaren Merkmale. */
    UNKNOWN;

    /** Anzeigename fuer die UI. */
    val displayName: String
        get() = when (this) {
            CONFIRMED -> "BESTÄTIGT"
            PROBABLE -> "WAHRSCHEINLICH"
            FAMILY_ONLY -> "NUR FAMILIE"
            UNKNOWN -> "UNBEKANNT"
        }

    /** Rang fuer deterministische Vergleiche (hoeher = staerker). */
    val rank: Int get() = ordinal
}

/**
 * Evidenzgrad eines einzelnen Merkmals.
 *
 * [CONFIRMED] ist ausschliesslich echten Hardware-Messungen vorbehalten.
 * Im aktuellen Projektbestand existiert kein solches Merkmal.
 */
enum class EvidenceGrade {
    /** Durch echten Hardware-Test reproduzierbar bestaetigt. */
    CONFIRMED,

    /** Durch dokumentierte Spezifikation oder Projektkonstante plausibel. */
    PROBABLE,

    /** Herkunft unklar / Legacy / Platzhalter. Zaehlt nicht als Modellbeweis. */
    UNKNOWN;

    val rank: Int get() = ordinal

    /** Nur PROBABLE oder besser darf ueberhaupt zu einer Modellstufe beitragen. */
    val countsTowardModel: Boolean get() = this != UNKNOWN
}

/** Hardware-Teststatus eines Modell-Profils. */
enum class HardwareTestStatus {
    /** Kein echter Hardware-Test durchgefuehrt. */
    NOT_TESTED,

    /** Teilweise getestet, aber nicht ausreichend fuer eine Bestaetigung. */
    PARTIALLY_TESTED,

    /** Auf echter Hardware reproduzierbar bestaetigt. */
    CONFIRMED_ON_HARDWARE;

    val displayName: String
        get() = when (this) {
            NOT_TESTED -> "nicht getestet"
            PARTIALLY_TESTED -> "teilweise getestet"
            CONFIRMED_ON_HARDWARE -> "auf Hardware bestätigt"
        }
}

/**
 * Herstellerfamilie. Trennt Marken, die technisch verwandt sind, aber fuer die
 * konkrete Modellerkennung unterscheidbar bleiben muessen.
 *
 * Wichtig: Segway und Ninebot teilen die Familie [SEGWAY_NINEBOT], bleiben aber
 * als [ScooterManufacturer] getrennt. NAVEE ist eine eigene Familie und darf
 * NICHT ueber generische UUIDs als Ninebot erscheinen.
 */
enum class BrandFamily(val displayName: String) {
    SEGWAY_NINEBOT("Segway / Ninebot"),
    NAVEE("NAVEE"),
    XIAOMI("Xiaomi"),
    SOFLOW("SOFLOW"),
    INMOTION("INMOTION"),
    KUKIRIN("KUKIRIN"),
    UNKNOWN("Unbekannt");

    companion object {
        /** Bildet eine Marke deterministisch auf ihre Familie ab. */
        fun of(manufacturer: ScooterManufacturer): BrandFamily = when (manufacturer) {
            ScooterManufacturer.SEGWAY,
            ScooterManufacturer.NINEBOT -> SEGWAY_NINEBOT
            ScooterManufacturer.NAVEE -> NAVEE
            ScooterManufacturer.XIAOMI -> XIAOMI
            ScooterManufacturer.SOFLOW -> SOFLOW
            ScooterManufacturer.INMOTION -> INMOTION
            ScooterManufacturer.KUKIRIN -> KUKIRIN
            ScooterManufacturer.UNKNOWN -> UNKNOWN
        }
    }
}

/**
 * Maschinenlesbarer Grund einer Erkennungsentscheidung.
 *
 * Der [code] ist stabil und darf in Exporten/Tests verwendet werden. [detail]
 * ist menschenlesbar und darf sich aendern.
 */
data class RecognitionReason(
    val code: RecognitionReasonCode,
    val detail: String = ""
) {
    /** Exportform "CODE: detail". */
    fun machineString(): String =
        if (detail.isBlank()) code.name else "${code.name}: $detail"
}

/** Stabile Reason-Codes. Die Namen sind Teil der Export-Schnittstelle. */
enum class RecognitionReasonCode {
    NO_DEVICE,
    DEVICE_FOUND,
    NAME_BRAND_HINT,
    NAME_MODEL_HINT,
    NAME_GENERIC,
    NAME_ONLY_NO_PAYLOAD,
    SERVICE_UUID_MATCH,
    SERVICE_UUID_GENERIC_IGNORED,
    SERVICE_UUID_UNKNOWN,
    COMPANY_ID_MATCH,
    COMPANY_ID_UNKNOWN,
    SERVICE_DATA_PRESENT,
    GATT_SERVICE_MATCH,
    GATT_CHARACTERISTIC_MATCH,
    GATT_PROPERTY_MATCH,
    GATT_INCOMPLETE,
    GATT_NOT_ATTEMPTED,
    CONTRADICTION_BRAND,
    CONTRADICTION_SERVICE,
    CONTRADICTION_NAME,
    BRAND_FAMILY_AMBIGUOUS,
    MISSING_MANUFACTURER_DATA,
    MISSING_SERVICE_DATA,
    RSSI_IGNORED,
    FAMILY_ONLY_EVIDENCE,
    CONFIRMED_PROFILE_MATCH,
    PROBABLE_PROFILE_MATCH,
    WEAK_PROFILE_MATCH,
    NO_PROFILE_MATCH,
    AMBIGUOUS_PROFILE_MATCH,
    UNKNOWN_MANUFACTURER_ID,
    UNKNOWN_SERVICE_UUID,
    USER_PROFILE_INDEPENDENT,
    DETERMINISTIC_TIE_BREAK
}

/**
 * Ein einzelnes tatsaechlich gefundenes Merkmal, das zu einer Bewertung
 * beigetragen hat. [kind] ist eine kurze, stabile Kategorie (z. B. "SERVICE_UUID").
 */
data class RecognitionFeatureMatch(
    val kind: String,
    val value: String,
    val grade: EvidenceGrade,
    val source: String
) {
    fun label(): String = "$kind=$value (${grade.name})"
}
