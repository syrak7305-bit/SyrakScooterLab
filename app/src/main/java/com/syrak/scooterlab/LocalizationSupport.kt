package com.syrak.scooterlab

import java.util.Locale

/* ============================================================================
 *  PHASE 14 — LOKALISIERUNG (reine Logik, keine Android-Abhaengigkeiten)
 *  ----------------------------------------------------------------------------
 *  Zwei fachlich GETRENNTE Dinge:
 *
 *   1. Allgemeiner Haftungstext  -> erscheint beim ersten Start IMMER auf
 *      Englisch (verbindliche Vorgabe). Die deutsche Fassung bleibt als
 *      gepruefter, vollstaendiger Text erhalten und ist umschaltbar.
 *
 *   2. Laenderbezogener Hinweis  -> Sprache folgt der Android-Systemsprache
 *      (Deutsch/Englisch), mit definiertem Fallback. Das LAND (Rechtsordnung)
 *      bleibt davon unabhaengig und wird weiterhin separat gewaehlt.
 *
 *  WICHTIG: Es gibt KEINE Laufzeit-Maschinenuebersetzung. Alle Texte sind
 *  redaktionell gepflegte, nachvollziehbare Fassungen derselben Quelle. Die
 *  englischen Laendertexte sind Uebersetzungen der deutschen Quelldaten und
 *  tragen denselben Stand (CATALOG_DATE). Sie sind ausdruecklich KEINE
 *  Rechtsberatung.
 * ==========================================================================*/

/** Unterstuetzte UI-/Rechtstext-Sprachen. Modular erweiterbar. */
enum class AppLanguage(val tag: String, val displayName: String) {
    DE("de", "Deutsch"),
    EN("en", "English");

    companion object {
        fun fromTag(tag: String?): AppLanguage? {
            val t = tag?.trim()?.lowercase(Locale.ROOT) ?: return null
            return entries.firstOrNull { it.tag == t }
        }
    }
}

/**
 * Loest die Anzeigesprache auf.
 *
 * Reihenfolge:
 *  1. Android-Systemsprache, wenn unterstuetzt.
 *  2. Sonst Landeszunge: Laender mit deutscher Amtssprache -> DE.
 *  3. Sonst ENGLISCH (definierter Fallback, nie "keine Sprache").
 *
 * Sprache und Land werden bewusst getrennt behandelt: ein deutschsprachiger
 * Nutzer in den USA bekommt den US-Hinweis auf Deutsch.
 */
object LanguageResolver {

    /** Laender, in denen Deutsch eine Amtssprache ist. */
    private val GERMAN_SPEAKING_COUNTRIES = setOf("DE", "AT", "CH", "LI", "LU")

    fun resolve(systemLanguageTag: String?, countryCode: String? = null): AppLanguage {
        // 1) Systemsprache (auch "de-AT", "en-US" ...)
        val primary = systemLanguageTag
            ?.substringBefore('-')
            ?.substringBefore('_')
        AppLanguage.fromTag(primary)?.let { return it }

        // 2) Landeszunge als Fallback
        val cc = countryCode?.trim()?.uppercase(Locale.ROOT)
        if (cc != null && cc in GERMAN_SPEAKING_COUNTRIES) return AppLanguage.DE

        // 3) Definierter Fallback
        return AppLanguage.EN
    }

    /** Fallback-Sprache fuer ein Land, wenn die Systemsprache nicht passt. */
    fun fallbackForCountry(countryCode: String?): AppLanguage {
        val cc = countryCode?.trim()?.uppercase(Locale.ROOT)
        return if (cc != null && cc in GERMAN_SPEAKING_COUNTRIES) AppLanguage.DE else AppLanguage.EN
    }
}

/* ---------------------------------------------------------------------------
 *  ALLGEMEINER HAFTUNGSTEXT
 * -------------------------------------------------------------------------*/

data class DisclaimerSection(val heading: String, val body: String)

/**
 * Vollstaendiger Haftungstext in beiden Sprachen.
 *
 * Der englische Text ist die beim Erststart verbindlich angezeigte Fassung.
 * Beide Fassungen enthalten ALLE sieben Abschnitte und den Notaus-/Firmware-
 * Hinweis — es wurde nichts gekuerzt oder abgeschwaecht.
 */
object DisclaimerText {

    val SECTIONS_EN: List<DisclaimerSection> = listOf(
        DisclaimerSection(
            "Age Restriction & Competence",
            "This app and its firmware tools are intended exclusively for adult users. Minors may use the vehicle only under the direct supervision and full responsibility of a parent or legal guardian."
        ),
        DisclaimerSection(
            "Personal Safety & Protective Equipment",
            "Always wear certified protective equipment (helmet, protectors) and check brakes, tyre pressure, folding mechanism and steering before every ride. Never ride distracted, tired or under the influence of alcohol, medication or drugs."
        ),
        DisclaimerSection(
            "Hardware Risks & Component Damage",
            "Low-level diagnostic commands, changing firmware registers, adjusting speed limiters or flashing custom MCU packages carry technical risks. Incorrect parameters can permanently damage the motor controller, lead to thermal runaway in lithium-ion batteries, blocking brakes or severe electronic malfunctions."
        ),
        DisclaimerSection(
            "Legal Compliance & Road Traffic",
            "Changing factory speed limits, power limits or software profiles may violate local traffic law, regional type approvals (e.g. eKFV in Germany) and public traffic rules. Vehicles modified beyond the permitted limits must not be used in public traffic, on cycle paths or on footpaths."
        ),
        DisclaimerSection(
            "Insurance & Loss of Warranty",
            "Modifying firmware or bypassing factory hardware restrictions immediately voids the manufacturer's warranty. Changes to top speed or output power generally void statutory liability insurance and expose the operator to substantial financial and legal risk in the event of damage."
        ),
        DisclaimerSection(
            "Independent Third-Party Project",
            "Syrak ScooterLab is an independent developer tool. There is no connection, support, sponsorship or official collaboration with Segway-Ninebot, Xiaomi, Navee or any other micro-mobility manufacturer."
        ),
        DisclaimerSection(
            "Disclaimer of Warranty & Personal Responsibility",
            "The software is provided without any express or implied warranty. By continuing, you accept full personal liability for property damage, personal injury, legal sanctions or fines arising from its use."
        )
    )

    val SECTIONS_DE: List<DisclaimerSection> = listOf(
        DisclaimerSection(
            "Altersbeschraenkung & Sachkunde",
            "Diese App und ihre Firmware-Werkzeuge richten sich ausschliesslich an volljaehrige Nutzer. Minderjaehrige duerfen das Fahrzeug nur unter direkter Aufsicht und voller Verantwortung einer erziehungsberechtigten Person nutzen."
        ),
        DisclaimerSection(
            "Persoenliche Sicherheit & Schutzausruestung",
            "Trage immer zertifizierte Schutzausruestung (Helm, Protektoren) und pruefe vor jeder Fahrt Bremsen, Reifendruck, Faltmechanismus und Lenkung. Fahre niemals abgelenkt, muede oder unter Einfluss von Alkohol, Medikamenten oder Drogen."
        ),
        DisclaimerSection(
            "Hardware-Risiken & Bauteilschaeden",
            "Low-Level-Diagnosebefehle, das Aendern von Firmware-Registern, das Verstellen von Geschwindigkeitsbegrenzern oder das Flashen eigener MCU-Pakete bergen technische Risiken. Fehlerhafte Parameter koennen den Motorcontroller dauerhaft beschaedigen, zu Thermal-Durchgehen in Lithium-Ionen-Akkus, blockierenden Bremsen oder schweren elektronischen Fehlfunktionen fuehren."
        ),
        DisclaimerSection(
            "Rechtliche Konformitaet & Strassenverkehr",
            "Das Veraendern werkseitiger Geschwindigkeitsgrenzen, Leistungsgrenzen oder Softwareprofile kann gegen lokales Verkehrsrecht, regionale Typgenehmigungen (z. B. eKFV in Deutschland) und oeffentliche Verkehrsregeln verstossen. Fahrzeuge, die ueber die zulaessigen Grenzen hinaus modifiziert wurden, duerfen im oeffentlichen Verkehr, auf Radwegen oder Gehwegen nicht betrieben werden."
        ),
        DisclaimerSection(
            "Versicherung & Gewaehrleistungsverlust",
            "Das Modifizieren von Firmware oder das Umgehen werkseitiger Hardware-Beschraenkungen laesst die Herstellergarantie sofort erloeschen. Aenderungen an Hoechstgeschwindigkeit oder Ausgangsleistung lassen in der Regel die gesetzliche Haftpflichtversicherung entfallen und setzen den Betreiber im Schadensfall einem erheblichen finanziellen und rechtlichen Risiko aus."
        ),
        DisclaimerSection(
            "Unabhaengiges Drittanbieter-Projekt",
            "Syrak ScooterLab ist ein unabhaengiges Entwickler-Werkzeug. Es besteht keine Verbindung, Unterstuetzung, Sponsoring oder offizielle Zusammenarbeit mit Segway-Ninebot, Xiaomi, Navee oder anderen Micro-Mobility-Herstellern."
        ),
        DisclaimerSection(
            "Gewaehrleistungsausschluss & Eigenverantwortung",
            "Die Software wird ohne jegliche ausdrueckliche oder stillschweigende Gewaehrleistung bereitgestellt. Mit dem Fortfahren uebernimmst du die vollstaendige persoenliche Haftung fuer Sachschaeden, Koerperverletzungen, rechtliche Sanktionen oder Bussgelder, die aus der Nutzung entstehen."
        )
    )

    fun sections(language: AppLanguage): List<DisclaimerSection> = when (language) {
        AppLanguage.EN -> SECTIONS_EN
        AppLanguage.DE -> SECTIONS_DE
    }

    fun title(language: AppLanguage): String = when (language) {
        AppLanguage.EN -> "SAFETY & LIABILITY NOTICE"
        AppLanguage.DE -> "SICHERHEITS- & HAFTUNGSHINWEIS"
    }

    fun intro(language: AppLanguage): String = when (language) {
        AppLanguage.EN -> "Please read in full. This notice must be confirmed before first use."
        AppLanguage.DE -> "Bitte vollstaendig lesen. Dieser Hinweis muss vor der ersten Nutzung bestaetigt werden."
    }

    fun emergency(language: AppLanguage): String = when (language) {
        AppLanguage.EN ->
            "The emergency stop is an electronic aid and does NOT replace the mechanical brake. " +
                "Firmware flashing remains locked as long as no verified SHA-256 and no proven model/hardware compatibility are available."
        AppLanguage.DE ->
            "Der Notaus ist eine elektronische Unterstuetzung und ersetzt NICHT die mechanische Bremse. " +
                "Firmware-Flashen bleibt gesperrt, solange kein verifizierter SHA-256 und keine belegte Modell-/Hardware-Kompatibilitaet vorliegen."
    }

    fun gateHint(language: AppLanguage, seconds: Int): String = when (language) {
        AppLanguage.EN -> "Mandatory reading time: at least $seconds seconds and scroll to the end."
        AppLanguage.DE -> "Pflicht-Lesephase: mindestens $seconds Sekunden und bis zum Ende scrollen."
    }

    fun acceptLabel(language: AppLanguage): String = when (language) {
        AppLanguage.EN -> "ACCEPT NOTICE"
        AppLanguage.DE -> "HINWEIS AKZEPTIEREN"
    }

    fun waitingStatus(language: AppLanguage, seconds: Int): String = when (language) {
        AppLanguage.EN -> "Please wait another $seconds seconds \u2026"
        AppLanguage.DE -> "Bitte warte noch $seconds Sekunden \u2026"
    }

    fun scrollStatus(language: AppLanguage): String = when (language) {
        AppLanguage.EN -> "Please read the notice to the end (scroll)."
        AppLanguage.DE -> "Bitte lies den Hinweis bis zum Ende (scrollen)."
    }

    fun readyStatus(language: AppLanguage): String = when (language) {
        AppLanguage.EN -> "Confirmation unlocked \u2014 you can continue now."
        AppLanguage.DE -> "Bestaetigung freigeschaltet \u2014 du kannst jetzt fortfahren."
    }

    fun lockedButton(language: AppLanguage, seconds: Int): String = when (language) {
        AppLanguage.EN -> "LOCKED \u00b7 ${seconds}s"
        AppLanguage.DE -> "GESPERRT \u00b7 ${seconds}s"
    }

    fun scrollButton(language: AppLanguage): String = when (language) {
        AppLanguage.EN -> "PLEASE SCROLL TO THE END"
        AppLanguage.DE -> "BITTE BIS ZUM ENDE SCROLLEN"
    }

    fun languageNote(language: AppLanguage): String = when (language) {
        AppLanguage.EN -> "Language: English (default for the general notice)."
        AppLanguage.DE -> "Sprache: Deutsch."
    }
}

/* ---------------------------------------------------------------------------
 *  LAENDERBEZOGENER HINWEIS — Uebersetzungen
 * -------------------------------------------------------------------------*/

/**
 * Lokalisierter Laenderhinweis. Die deutsche Fassung wird unveraendert aus
 * [CountryLegalCatalog] uebernommen (keine inhaltliche Aenderung). Die
 * englische Fassung ist eine Uebersetzung derselben Aussagen mit demselben
 * Quellenstand.
 */
data class LocalizedCountryNotice(
    val code: String,
    val displayName: String,
    val legalSpeedKmh: Int?,
    val verified: Boolean,
    val source: String,
    val sourceDate: String,
    val points: List<String>,
    val language: AppLanguage
)

object CountryNoticeTexts {

    private const val EN_DISCLAIMER =
        "General information only, not legal advice. Consult the competent authority in case of doubt."

    private val EN_POINTS: Map<String, List<String>> = mapOf(
        "DE" to listOf(
            "Electric micro-vehicles are subject to the technical requirements of the eKFV.",
            "The design-related maximum speed is 20 km/h; exceeding it in public traffic is not permitted.",
            "Tampering with the motor controller or firmware voids the operating permit (ABE).",
            "Without a valid ABE, insurance cover generally lapses.",
            EN_DISCLAIMER
        ),
        "AT" to listOf(
            "In Austria, e-scooters are bound to a design-related maximum speed of 25 km/h.",
            "Exceeding this can legally classify the vehicle as an inadmissible motor vehicle.",
            "Use in public areas requires compliance with the Austrian Road Traffic Act (StVO).",
            EN_DISCLAIMER
        ),
        "CH" to listOf(
            "E-scooters may reach a design-related maximum of 20 km/h with motor assistance.",
            "Technical tuning measures are prohibited on public roads and can invalidate approval.",
            EN_DISCLAIMER
        ),
        "US" to listOf(
            "There is no uniform nationwide maximum speed for e-scooters in the USA.",
            "The regulations of the respective state or municipality apply.",
            "No specific limit is stated here on purpose, because none can be substantiated.",
            EN_DISCLAIMER
        ),
        "XX" to listOf(
            "No verified legal information is stored for this country.",
            "No invented speed limits are displayed on purpose.",
            "Verify the local regulations yourself before using the vehicle in public areas.",
            EN_DISCLAIMER
        )
    )

    private val EN_SOURCE: Map<String, String> = mapOf(
        "DE" to "eKFV (Electric Micro-Vehicle Regulation) in conjunction with the German Road Traffic Act (StVO)",
        "AT" to "Motor Vehicle Act (KFG) / e-scooter regulation, Road Traffic Act (StVO)",
        "CH" to "VTS (Ordinance on the Technical Requirements for Road Vehicles)",
        "US" to "No uniform federal limit; regulation by state/municipality",
        "XX" to "No reliable source stored for this country"
    )

    private val EN_NAME: Map<String, String> = mapOf(
        "DE" to "Germany",
        "AT" to "Austria",
        "CH" to "Switzerland",
        "US" to "United States",
        "XX" to "Unknown country"
    )

    /** Liefert den Hinweis in der gewuenschten Sprache. */
    fun forRegion(region: Region, language: AppLanguage): LocalizedCountryNotice {
        val base = CountryLegalCatalog.forRegion(region)
        return when (language) {
            AppLanguage.DE -> LocalizedCountryNotice(
                code = base.code,
                displayName = base.displayName,
                legalSpeedKmh = base.legalSpeedKmh,
                verified = base.verified,
                source = base.source,
                sourceDate = base.sourceDate,
                points = base.points,
                language = AppLanguage.DE
            )
            AppLanguage.EN -> LocalizedCountryNotice(
                code = base.code,
                displayName = EN_NAME[base.code] ?: base.displayName,
                legalSpeedKmh = base.legalSpeedKmh,
                verified = base.verified,
                source = EN_SOURCE[base.code] ?: base.source,
                sourceDate = base.sourceDate,
                points = EN_POINTS[base.code] ?: base.points,
                language = AppLanguage.EN
            )
        }
    }

    /** UI-Beschriftungen des Laenderhinweises. */
    fun title(language: AppLanguage): String = when (language) {
        AppLanguage.EN -> "REGIONAL LEGAL NOTICES"
        AppLanguage.DE -> "REGIONALE RECHTLICHE HINWEISE"
    }

    fun speedLine(language: AppLanguage, speed: Int?, country: String): String = when (language) {
        AppLanguage.EN -> if (speed != null)
            "Design-related maximum speed in $country: $speed km/h."
        else
            "No substantiated maximum speed stored for $country."
        AppLanguage.DE -> if (speed != null)
            "Bauartbedingte Hoechstgeschwindigkeit in $country: $speed km/h."
        else
            "Fuer $country ist keine belegbare Hoechstgeschwindigkeit hinterlegt."
    }

    fun verifiedLine(language: AppLanguage, verified: Boolean): String = when (language) {
        AppLanguage.EN -> if (verified)
            "Status: core statement backed by a named legal source."
        else
            "Status: NOT verified \u2014 no reliable limit is asserted."
        AppLanguage.DE -> if (verified)
            "Status: Kernaussage durch benannte Rechtsquelle gestuetzt."
        else
            "Status: NICHT verifiziert \u2014 es wird keine belastbare Grenze behauptet."
    }

    fun sourceLabel(language: AppLanguage): String = when (language) {
        AppLanguage.EN -> "Source"
        AppLanguage.DE -> "Quelle"
    }

    fun dateLabel(language: AppLanguage): String = when (language) {
        AppLanguage.EN -> "As of"
        AppLanguage.DE -> "Stand"
    }

    fun changeCountry(language: AppLanguage): String = when (language) {
        AppLanguage.EN -> "SELECT COUNTRY MANUALLY"
        AppLanguage.DE -> "LAND MANUELL WAEHLEN"
    }

    fun accept(language: AppLanguage): String = when (language) {
        AppLanguage.EN -> "UNDERSTOOD & CONTINUE"
        AppLanguage.DE -> "VERSTANDEN & WEITER"
    }

    fun transparency(language: AppLanguage, catalogDate: String): String = when (language) {
        AppLanguage.EN ->
            "Currency: the stored information (catalogue status $catalogDate) may be outdated and is " +
                "expressly NOT legal advice and no assurance of current limits. Verify the applicable law with the competent authority."
        AppLanguage.DE ->
            "Aktualitaet: Die hinterlegten Angaben (Katalogstand $catalogDate) koennen veraltet sein und sind " +
                "ausdruecklich KEINE Rechtsberatung und keine Zusicherung aktueller Grenzwerte. Pruefe die geltende Rechtslage bei der zustaendigen Behoerde."
    }

    fun languageLine(language: AppLanguage): String = when (language) {
        AppLanguage.EN -> "Notice language: English (system language not German)."
        AppLanguage.DE -> "Hinweis-Sprache: Deutsch (Systemsprache)."
    }
}
