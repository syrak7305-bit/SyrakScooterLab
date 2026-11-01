package com.syrak.scooterlab

import java.util.Locale

/* ============================================================================
 *  PHASE 17.0 — PERSONALISIERTES ONBOARDING & SCOOTER-PROFILE
 *  ----------------------------------------------------------------------------
 *  Reine Logik (KEINE Android-Abhaengigkeiten), damit sie ohne Context/Emulator
 *  testbar ist. Die Persistenz (SharedPreferences) liegt weiterhin in
 *  [ScooterRepository]; hier stehen nur die Datenstrukturen, der Codec, die
 *  Marken-/Modell-Zuordnung, das Onboarding-Gate und die Feature-Ableitung.
 *
 *  WICHTIG (Regel 5/6/7): Es werden KEINE BLE-UUIDs, Protokolle, Befehle oder
 *  Kompatibilitaeten erfunden. Die Feature-Ableitung [ScooterFeatureRegistry]
 *  stuetzt sich ausschliesslich auf die BESTEHENDE Adapter-Registry
 *  ([ProtocolAdapters]) und deren [AdapterCapabilities]. Eine Nutzer-Auswahl
 *  allein schaltet NIE unbestaetigte Schreibbefehle frei.
 * ==========================================================================*/

/**
 * Nutzerprofil (Step A). Bewusst minimal — keine unnoetigen personenbezogenen
 * Daten. [displayName] ist ein frei waehlbarer Anzeigename, [language] die
 * gewuenschte App-Sprache, [countryCode] das Land/Region (steuert die
 * bestehenden Rechts-/Limit-Hinweise ueber [Region]).
 */
data class UserProfile(
    val displayName: String = "",
    val language: AppLanguage = AppLanguage.DE,
    val countryCode: String = Region.WORLD.code,
    val setupCompleted: Boolean = false
) {
    /** Region aus dem gespeicherten Laendercode (nutzt die bestehende Enum). */
    val region: Region get() = Region.fromCountry(countryCode)

    val hasName: Boolean get() = displayName.isNotBlank()

    companion object {
        /** Entfernt fuehrende/trailing Leerzeichen, kollabiert Mehrfach-Leerzeichen, begrenzt auf 40 Zeichen. */
        fun sanitizeName(raw: String?): String {
            val trimmed = raw?.trim() ?: return ""
            val collapsed = trimmed.replace(Regex("\\s+"), " ")
            return if (collapsed.length > 40) collapsed.substring(0, 40) else collapsed
        }
    }
}

/**
 * Scooter-Identitaet aus der Nutzereingabe (Step B/C).
 *
 * Bewusst NICHT [ScooterProfile] genannt — dieser Name ist bereits durch das
 * Tuning-Profil in [Models.kt] belegt. Diese Klasse beschreibt die Identitaet
 * des Rollers (Marke/Modell/optionaler Code), NICHT Tuning-Parameter.
 *
 * [identificationConfidence] beschreibt ausschliesslich die HERKUNFT der
 * Angabe (Nutzer-Eingabe), nicht eine Hardware-Bestaetigung. Eine Nutzer-Auswahl
 * ist KEIN Kompatibilitaetsnachweis.
 */
data class RiderScooterProfile(
    val brand: ScooterManufacturer = ScooterManufacturer.UNKNOWN,
    val model: ScooterModel = ScooterModel.UNKNOWN,
    val modelCode: String = "",
    val serialNumber: String? = null,
    /** true, wenn der Nutzer "Mein Modell ist nicht gelistet" gewaehlt hat. */
    val modelNotListed: Boolean = false,
    val identificationConfidence: DetectionConfidence = DetectionConfidence.UNKNOWN
) {
    val hasBrand: Boolean get() = brand.isKnown

    /** Modell gewaehlt ODER bewusst "nicht gelistet". */
    val hasModelSelection: Boolean get() = model.isKnown || modelNotListed

    val isComplete: Boolean get() = hasBrand && hasModelSelection

    /**
     * Das tatsaechlich nutzbare Modell: nur wenn es zur gewaehlten Marke passt.
     * Verhindert, dass eine inkonsistente Kombination (z. B. Marke NAVEE +
     * Modell Ninebot) faelschlich als kompatibel gilt.
     */
    fun effectiveModel(): ScooterModel =
        if (model.isKnown && ScooterBrandModels.modelBelongsToBrand(model, brand)) model
        else ScooterModel.UNKNOWN

    companion object {
        fun sanitizeCode(raw: String?): String = raw?.trim()?.take(32) ?: ""
        fun sanitizeSerial(raw: String?): String? {
            val v = raw?.trim() ?: return null
            return if (v.isEmpty()) null else v.take(32)
        }
    }
}

/**
 * Marken-/Modell-Zuordnung. Nutzt die BESTEHENDE [ScooterModel.manufacturer]-
 * Ableitung, statt eine zweite Wahrheit zu pflegen.
 *
 * SEGWAY und NINEBOT sind derselbe Herstellerverbund (Segway-Ninebot); die
 * Marke bleibt im Profil trotzdem getrennt gespeichert. Eine bestaetigte
 * Ninebot-Kompatibilitaet wird NIEMALS automatisch zu NAVEE/Xiaomi usw.
 */
object ScooterBrandModels {

    /** Marken in Wizard-Reihenfolge (mind. die vom Auftrag geforderten). */
    fun brandOptions(): List<ScooterManufacturer> = listOf(
        ScooterManufacturer.NINEBOT,
        ScooterManufacturer.SEGWAY,
        ScooterManufacturer.XIAOMI,
        ScooterManufacturer.NAVEE,
        ScooterManufacturer.SOFLOW,
        ScooterManufacturer.KUKIRIN,
        ScooterManufacturer.INMOTION,
        ScooterManufacturer.UNKNOWN
    )

    /** Modelle, die zu einer Marke gehoeren (leer, wenn keine gefuehrt sind). */
    fun modelsForBrand(brand: ScooterManufacturer): List<ScooterModel> = when (brand) {
        ScooterManufacturer.NINEBOT, ScooterManufacturer.SEGWAY ->
            ScooterModel.values().filter { it.manufacturer == ScooterManufacturer.NINEBOT }
        ScooterManufacturer.UNKNOWN -> emptyList()
        else -> ScooterModel.values().filter { it.isKnown && it.manufacturer == brand }
    }

    /** true, wenn [model] fachlich zu [brand] gehoert. */
    fun modelBelongsToBrand(model: ScooterModel, brand: ScooterManufacturer): Boolean {
        if (!model.isKnown) return false
        return when (brand) {
            ScooterManufacturer.NINEBOT, ScooterManufacturer.SEGWAY ->
                model.manufacturer == ScooterManufacturer.NINEBOT
            ScooterManufacturer.UNKNOWN -> false
            else -> model.manufacturer == brand
        }
    }
}

/**
 * Serialisierung der Profile in ein flaches String-Map (1:1 zu
 * SharedPreferences-Schluesseln). Robust gegen fehlende/kaputte Werte: ein
 * ungueltiger Enum-Name faellt auf den Default zurueck, statt zu werfen.
 */
object ProfileCodec {

    const val KEY_NAME = "profile_display_name"
    const val KEY_LANGUAGE = "profile_language"
    const val KEY_COUNTRY = "profile_country"
    const val KEY_SETUP_COMPLETED = "profile_setup_completed"

    const val KEY_BRAND = "scooter_brand"
    const val KEY_MODEL = "scooter_model"
    const val KEY_MODEL_CODE = "scooter_model_code"
    const val KEY_SERIAL = "scooter_serial"
    const val KEY_MODEL_NOT_LISTED = "scooter_model_not_listed"
    const val KEY_CONFIDENCE = "scooter_confidence"

    fun encodeUser(u: UserProfile): Map<String, String> = mapOf(
        KEY_NAME to u.displayName,
        KEY_LANGUAGE to u.language.tag,
        KEY_COUNTRY to u.countryCode,
        KEY_SETUP_COMPLETED to u.setupCompleted.toString()
    )

    fun decodeUser(map: Map<String, String>): UserProfile {
        val language = AppLanguage.fromTag(map[KEY_LANGUAGE]) ?: AppLanguage.DE
        val country = map[KEY_COUNTRY]?.trim()?.uppercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() } ?: Region.WORLD.code
        return UserProfile(
            displayName = UserProfile.sanitizeName(map[KEY_NAME]),
            language = language,
            countryCode = country,
            setupCompleted = map[KEY_SETUP_COMPLETED]?.toBooleanStrictOrNull() ?: false
        )
    }

    fun encodeScooter(s: RiderScooterProfile): Map<String, String> = mapOf(
        KEY_BRAND to s.brand.name,
        KEY_MODEL to s.model.name,
        KEY_MODEL_CODE to s.modelCode,
        KEY_SERIAL to (s.serialNumber ?: ""),
        KEY_MODEL_NOT_LISTED to s.modelNotListed.toString(),
        KEY_CONFIDENCE to s.identificationConfidence.name
    )

    fun decodeScooter(map: Map<String, String>): RiderScooterProfile {
        val brand = enumOr(map[KEY_BRAND], ScooterManufacturer.UNKNOWN) { ScooterManufacturer.valueOf(it) }
        val model = enumOr(map[KEY_MODEL], ScooterModel.UNKNOWN) { ScooterModel.valueOf(it) }
        val confidence = enumOr(map[KEY_CONFIDENCE], DetectionConfidence.UNKNOWN) {
            DetectionConfidence.valueOf(it)
        }
        return RiderScooterProfile(
            brand = brand,
            model = model,
            modelCode = RiderScooterProfile.sanitizeCode(map[KEY_MODEL_CODE]),
            serialNumber = RiderScooterProfile.sanitizeSerial(map[KEY_SERIAL]),
            modelNotListed = map[KEY_MODEL_NOT_LISTED]?.toBooleanStrictOrNull() ?: false,
            identificationConfidence = confidence
        )
    }

    private inline fun <T> enumOr(raw: String?, fallback: T, parse: (String) -> T): T {
        val v = raw?.trim() ?: return fallback
        if (v.isEmpty()) return fallback
        return try { parse(v) } catch (_: IllegalArgumentException) { fallback }
    }
}

/**
 * Entscheidet, ob und an welcher Stelle der Setup-Wizard erscheint.
 *
 *  - Wizard nur, wenn das Setup noch NICHT abgeschlossen ist.
 *  - Ein unvollstaendiges Setup wird beim naechsten Start an der ersten
 *    offenen Stelle fortgesetzt (Resume).
 */
object OnboardingGate {

    enum class Step { USER, BRAND, MODEL, DONE }

    /** true, wenn der Wizard (erneut) angezeigt werden muss. */
    fun shouldShowWizard(user: UserProfile): Boolean = !user.setupCompleted

    /** Erste offene Stelle fuer einen Resume. */
    fun resumeStep(user: UserProfile, scooter: RiderScooterProfile): Step = when {
        !user.hasName -> Step.USER
        !scooter.hasBrand -> Step.BRAND
        !scooter.hasModelSelection -> Step.MODEL
        else -> Step.DONE
    }
}

/**
 * Schuetzt davor, dass Diagnose-/Messwerte des alten Rollers dem neu gewaehlten
 * Roller zugeschrieben werden (Auftrag Section 9). Aendert sich Marke ODER
 * Modell, MUSS die laufende Diagnose-Sitzung zurueckgesetzt werden.
 */
object ProfileSessionGuard {

    fun mustResetSession(previous: RiderScooterProfile?, next: RiderScooterProfile): Boolean {
        if (previous == null) return false
        return previous.brand != next.brand ||
            previous.effectiveModel() != next.effectiveModel()
    }
}

/* --------------------------------------------------------------------------- */
/*  FEATURE-REGISTRY (abgeleitet aus der bestehenden Adapter-Registry)         */
/* --------------------------------------------------------------------------- */

/** Fachliche App-Funktionen, deren Verfuegbarkeit pro Modell angezeigt wird. */
enum class AppFeature(val label: String) {
    LIVE_TELEMETRY("Live-Telemetrie"),
    TRIP_COMPUTER("Trip-Computer"),
    DIAGNOSTICS("Diagnose"),
    HARDWARE_REPORT("Hardware-Testbericht"),
    GATT_DIAGNOSIS("Bluetooth-/GATT-Diagnose"),
    TUNING("Tuning-Schreibbefehle"),
    FIRMWARE_FLASH("Firmware-Flash")
}

enum class FeatureStatus {
    /** Fuer dieses Modell bestaetigt nutzbar (Adapter fuehrt Modell exakt). */
    AVAILABLE,

    /** Technisch vorhanden, aber nicht hardware-bestaetigt -> experimentell. */
    EXPERIMENTAL,

    /** Fuer dieses Modell nicht verfuegbar (wird angezeigt, nicht versteckt). */
    UNAVAILABLE,

    /** Aus Sicherheitsgruenden gesperrt, bis Hardware bestaetigt ist. */
    LOCKED_UNCONFIRMED
}

data class FeatureAvailability(
    val feature: AppFeature,
    val status: FeatureStatus,
    val reason: String
) {
    /** Anzeigetext inkl. klarer Kennzeichnung nicht verfuegbarer Funktionen. */
    fun displayLabel(): String = when (status) {
        FeatureStatus.AVAILABLE -> "${feature.label} \u00b7 verf\u00fcgbar"
        FeatureStatus.EXPERIMENTAL -> "${feature.label} \u00b7 experimentell (nicht best\u00e4tigt)"
        FeatureStatus.UNAVAILABLE -> "${feature.label} \u00b7 F\u00fcr dieses Modell nicht verf\u00fcgbar"
        FeatureStatus.LOCKED_UNCONFIRMED -> "${feature.label} \u00b7 gesperrt (Hardware nicht best\u00e4tigt)"
    }
}

/**
 * Leitet die Feature-Verfuegbarkeit AUSSCHLIESSLICH aus der bestehenden
 * [ProtocolAdapters]-Registry ab. Kein neues Protokollwissen, keine erfundenen
 * Faehigkeiten. Ein unbekanntes Modell erhaelt nur die generische
 * Bluetooth-/GATT-Diagnose; alle modellspezifischen Funktionen werden als
 * "nicht verfuegbar" markiert (nicht entfernt).
 */
object ScooterFeatureRegistry {

    fun availability(profile: RiderScooterProfile): List<FeatureAvailability> {
        val model = profile.effectiveModel()
        val resolution = ProtocolAdapters.resolveForModel(model)
        val adapter = resolution.adapter

        // Unbekanntes/inkonsistentes Modell: nur generische BLE-Diagnose.
        if (model == ScooterModel.UNKNOWN || adapter == null) {
            return listOf(
                FeatureAvailability(AppFeature.GATT_DIAGNOSIS, FeatureStatus.AVAILABLE,
                    "Bluetooth-Suche, Verbindung und GATT-Diagnose sind modellunabh\u00e4ngig m\u00f6glich."),
                FeatureAvailability(AppFeature.LIVE_TELEMETRY, FeatureStatus.UNAVAILABLE,
                    "Kein best\u00e4tigter Decoder f\u00fcr dieses Modell."),
                FeatureAvailability(AppFeature.TRIP_COMPUTER, FeatureStatus.UNAVAILABLE,
                    "Ben\u00f6tigt Live-Telemetrie."),
                FeatureAvailability(AppFeature.DIAGNOSTICS, FeatureStatus.EXPERIMENTAL,
                    "Nur generische Verbindungsdiagnose, keine modellspezifischen Werte."),
                FeatureAvailability(AppFeature.HARDWARE_REPORT, FeatureStatus.AVAILABLE,
                    "Rohdaten-Export (Scan/GATT/Frames) bleibt verf\u00fcgbar."),
                FeatureAvailability(AppFeature.TUNING, FeatureStatus.UNAVAILABLE,
                    "F\u00fcr unbekannte Modelle werden keine Schreibbefehle gesendet."),
                FeatureAvailability(AppFeature.FIRMWARE_FLASH, FeatureStatus.UNAVAILABLE,
                    "Kein Flash f\u00fcr unbekannte Modelle.")
            )
        }

        val caps = adapter.capabilities
        val telemetry = if (caps.telemetryDecoding) FeatureStatus.EXPERIMENTAL else FeatureStatus.UNAVAILABLE
        val tuning = if (caps.supportsAnyWrite()) FeatureStatus.LOCKED_UNCONFIRMED else FeatureStatus.UNAVAILABLE
        return listOf(
            FeatureAvailability(AppFeature.GATT_DIAGNOSIS, FeatureStatus.AVAILABLE,
                "Bluetooth-/GATT-Diagnose ist immer m\u00f6glich."),
            FeatureAvailability(AppFeature.LIVE_TELEMETRY, telemetry,
                if (telemetry == FeatureStatus.EXPERIMENTAL)
                    "Decoder vorhanden, aber nicht auf echter Hardware best\u00e4tigt."
                else "Adapter dekodiert keine Telemetrie."),
            FeatureAvailability(AppFeature.TRIP_COMPUTER, telemetry,
                "Basiert auf Live-Telemetrie."),
            FeatureAvailability(AppFeature.DIAGNOSTICS, FeatureStatus.EXPERIMENTAL,
                "Modellspezifische Diagnose, noch nicht hardware-best\u00e4tigt."),
            FeatureAvailability(AppFeature.HARDWARE_REPORT, FeatureStatus.AVAILABLE,
                "Rohdaten-Export bleibt verf\u00fcgbar."),
            FeatureAvailability(AppFeature.TUNING, tuning,
                if (tuning == FeatureStatus.LOCKED_UNCONFIRMED)
                    "Adapter deklariert Schreibbefehle, Freigabe erst nach Hardware-Best\u00e4tigung."
                else "Adapter ist read-only."),
            FeatureAvailability(AppFeature.FIRMWARE_FLASH, FeatureStatus.LOCKED_UNCONFIRMED,
                "Flash bleibt gesperrt, bis Kompatibilit\u00e4t auf echter Hardware best\u00e4tigt ist.")
        )
    }

    /** Nur die aktuell nutzbaren (AVAILABLE/EXPERIMENTAL) Funktionen. */
    fun usableFeatures(profile: RiderScooterProfile): List<AppFeature> =
        availability(profile)
            .filter { it.status == FeatureStatus.AVAILABLE || it.status == FeatureStatus.EXPERIMENTAL }
            .map { it.feature }
}

/**
 * Duenne Evidenz-Sicht ueber die bestehende Protokoll-Registry
 * ([ProtocolAdapters]) — KEIN zweites Registry-System. Zeigt je Modell, ob ein
 * exakter Adapter existiert und wie viele UUIDs hardware-bestaetigt sind.
 */
object ProtocolRegistryView {

    data class Entry(
        val model: ScooterModel,
        val adapterId: String?,
        val exact: Boolean,
        val reason: String
    )

    fun entryFor(model: ScooterModel): Entry {
        val r = ProtocolAdapters.resolveForModel(model)
        return Entry(model, r.adapter?.id, r.exact, r.reason)
    }

    /** Anzahl auf echter Hardware bestaetigter UUIDs (aus der zentralen Registry). */
    fun confirmedUuidCount(): Int = BleUuids.confirmedOnHardwareCount()
}
