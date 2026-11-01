package com.syrak.scooterlab

import java.util.Locale

/* ============================================================================
 *  PHASE 16.3.2 — ADVERTISEMENT-ANALYSE (reine Logik, ohne Android)
 *  ---------------------------------------------------------------------------
 *  Belegter Defekt (R1/R2): Die Geraeteerkennung wertete AUSSCHLIESSLICH den
 *  BLE-Namen aus. Ein `grep` ueber das gesamte app/src nach
 *  manufacturerData / getServiceData / serviceUuids / txPowerLevel ergab
 *  NULL Treffer. Reale E-Scooter senden im Advertisement regelmaessig keinen
 *  Namen (der kommt erst ueber GATT 0x2A00) — Folge: 16 Treffer, 0 Kandidaten.
 *
 *  WAS HIER PASSIERT:
 *   Diese Datei liest die TATSAECHLICH empfangenen Advertisement-Felder und
 *   leitet daraus einen KONSERVATIVEN Hinweis ab, ob das Geraet ein E-Scooter
 *   sein KOENNTE.
 *
 *  HARTE REGELN (bewusst so, siehe Auftrag Punkt 5 und 7):
 *   • Ein Hinweis ist NIEMALS eine Kompatibilitaetsaussage. Ein Treffer auf
 *     Service-UUID e003 bedeutet "koennte Ninebot-Familie sein", NICHT
 *     "spricht das Ninebot-Protokoll".
 *   • Es wird keine UUID und keine Company-ID erfunden. Verwendet werden nur
 *     Konstanten, die bereits im Projekt existieren ([BleUuids]), plus eine
 *     Company-ID-Tabelle, die LEER ist, solange kein belegbarer Wert vorliegt
 *     (siehe [CompanyIdRegistry]).
 *   • Ein Geraet ohne jeden Hinweis bleibt [ScooterManufacturer.UNKNOWN] und
 *     wird von [ScanClassifier] NICHT als Kandidat behandelt.
 *   • Aus der MAC-Adresse wird nichts abgeleitet.
 * ==========================================================================*/

/** Art des Advertisement-Hinweises. */
enum class EvidenceKind(val label: String) {
    /** Hinweis kommt aus dem Geraetenamen (klassischer Pfad). */
    NAME("Geraetename"),

    /** Hinweis kommt aus einer beworbenen Service-UUID. */
    SERVICE_UUID("Advertisement-Service-UUID"),

    /** Hinweis kommt aus einem Company Identifier (Manufacturer Data 0xFF). */
    MANUFACTURER_ID("Advertisement-Company-ID"),

    /** Hinweis kommt aus Service Data (0x16). */
    SERVICE_DATA("Advertisement-Service-Data")
}

/**
 * Staerke eines Hinweises.
 *
 * [STRONG]  — hinreichend fuer "Scooter-Kandidat" (mehr nicht!).
 * [WEAK]    — allein NICHT hinreichend, nur als Zusatzinformation.
 */
enum class EvidenceStrength { STRONG, WEAK }

/**
 * Ergebnis der Advertisement-Analyse.
 *
 * @param kind        staerkster gefundener Hinweis, oder null.
 * @param strength    Staerke des staerksten Hinweises.
 * @param manufacturer abgeleiteter Hersteller, oder UNKNOWN.
 * @param matchedValue der konkret passende Wert (UUID/Company-ID) fuer die UI.
 * @param description menschenlesbare Begruendung (fuer Log und Diagnose).
 * @param allKinds    alle gefundenen Hinweisarten (fuer die Detailanzeige).
 */
data class AdvertisementEvidence(
    val kind: EvidenceKind? = null,
    val strength: EvidenceStrength? = null,
    val manufacturer: ScooterManufacturer = ScooterManufacturer.UNKNOWN,
    val matchedValue: String? = null,
    val description: String = "Kein Advertisement-Hinweis.",
    val allKinds: Set<EvidenceKind> = emptySet()
) {
    /** true, wenn ueberhaupt ein Hinweis vorliegt. */
    val hasHint: Boolean get() = kind != null

    /** true, wenn der Hinweis allein einen Kandidaten rechtfertigt. */
    val isStrong: Boolean get() = strength == EvidenceStrength.STRONG

    /** true, wenn daraus ein Scooter-Kandidat werden darf. */
    val justifiesCandidate: Boolean get() = hasHint && isStrong

    companion object {
        /** Kein Hinweis — der ehrliche Standardfall. */
        val NONE = AdvertisementEvidence()
    }
}

/**
 * Eine belegbare Service-UUID, die im Advertisement auf eine Scooter-Familie
 * hindeuten kann.
 *
 * @param uuid       kanonische 128-Bit-Kleinschreibung
 * @param family     Herstellerfamilie
 * @param strength   STRONG nur, wenn die UUID im Projekt als transportspezifisch
 *                   gilt; WEAK, wenn sie auch von Nicht-Scootern benutzt wird.
 * @param rationale  Begruendung inkl. Belegstatus — wird 1:1 in der UI/Log
 *                   angezeigt, damit niemand den Hinweis fuer einen Beweis haelt.
 */
data class ServiceHint(
    val uuid: String,
    val family: ScooterManufacturer,
    val strength: EvidenceStrength,
    val rationale: String
)

/**
 * Company-Identifier-Registry (Manufacturer Specific Data, AD-Typ 0xFF).
 *
 * WICHTIG — bewusste Leere:
 *   Ein Company Identifier ist nur dann ein zulaessiger Hinweis, wenn der Wert
 *   aus einer nachpruefbaren Quelle (Bluetooth SIG Assigned Numbers) stammt.
 *   Im Projekt liegt fuer keinen Scooter-Hersteller ein solcher belegter Wert
 *   vor. Deshalb ist [entries] LEER. Es wird KEIN Wert geraten.
 *
 *   Der Mechanismus ist vollstaendig implementiert und getestet: sobald ein
 *   belegter Wert vorliegt, genuegt ein Eintrag hier — ohne Aenderung an
 *   [AdvertisementAnalyzer]. Bis dahin liefert die Analyse fuer Manufacturer
 *   Data ehrlich "kein Hinweis".
 */
object CompanyIdRegistry {

    /**
     * Ein Company-Identifier-Eintrag.
     *
     * @param companyId 16-Bit Company Identifier aus dem SIG-Register.
     * @param family    zugeordnete Herstellerfamilie.
     * @param source    woher der Wert stammt (Pflicht, damit nichts anonym bleibt).
     */
    data class Entry(
        val companyId: Int,
        val family: ScooterManufacturer,
        val source: String
    )

    /**
     * Belegte Eintraege. Bewusst leer — siehe Klassenkommentar.
     * Kein Eintrag ohne nachpruefbare Quelle.
     */
    private val entries: List<Entry> = emptyList()

    /** Alle belegten Eintraege (fuer Tests und Entwickleransicht). */
    fun all(): List<Entry> = entries

    /** Lookup; unbekannte IDs liefern null (kein Raten). */
    fun manufacturerOf(companyId: Int): ScooterManufacturer? =
        entries.firstOrNull { it.companyId == companyId }?.family

    /** true, wenn die Registry ueberhaupt belegte Werte enthaelt. */
    fun isEmpty(): Boolean = entries.isEmpty()
}

/**
 * Analysiert eine [ScanObservation] auf konservative Scooter-Hinweise.
 *
 * Alle Methoden sind rein und ohne Android-Abhaengigkeit testbar.
 */
object AdvertisementAnalyzer {

    /**
     * Im Projekt vorhandene, transportspezifische Service-UUIDs.
     *
     * Herkunft: [BleUuids] — dort ausdruecklich als PROJECT_CONSTANT
     * (nicht DOCUMENTED) gefuehrt. Sie sind damit ein Hinweis, kein Beweis.
     */
    val SERVICE_HINTS: List<ServiceHint> = listOf(
        ServiceHint(
            uuid = BleUuids.NINEBOT_SERVICE.lowercase(Locale.ROOT),
            family = ScooterManufacturer.NINEBOT,
            strength = EvidenceStrength.STRONG,
            rationale = "Service e003 ist im Projekt als Ninebot-Transport " +
                "gefuehrt (PROJECT_CONSTANT, nicht extern belegt). " +
                "Hinweis auf die Ninebot-Familie — KEIN Protokollnachweis."
        ),
        ServiceHint(
            uuid = BleUuids.NAVEE_SERVICE.lowercase(Locale.ROOT),
            family = ScooterManufacturer.NAVEE,
            strength = EvidenceStrength.STRONG,
            rationale = "Service ffe0 ist im Projekt als Navee/Brightway-Transport " +
                "gefuehrt (PROJECT_CONSTANT, nicht extern belegt). " +
                "Hinweis auf die Navee-Familie — KEIN Protokollnachweis."
        ),
        ServiceHint(
            uuid = BleUuids.LEGACY_XIAOMI_SERVICE.lowercase(Locale.ROOT),
            family = ScooterManufacturer.XIAOMI,
            strength = EvidenceStrength.WEAK,
            rationale = "Service fe95 (MiBeacon) wird von SEHR vielen " +
                "Xiaomi-Geraeten benutzt (Baender, Sensoren, Lampen). " +
                "Allein KEIN Scooter-Hinweis."
        )
    )

    /**
     * Analysiert eine Beobachtung.
     *
     * Reihenfolge der Auswertung (deterministisch):
     *   1. Name (staerkster Hinweis, weil er explizit vom Hersteller stammt)
     *   2. Service-UUID (STRONG vor WEAK)
     *   3. Company-ID (nur wenn belegt, siehe [CompanyIdRegistry])
     *   4. Service-Data (nur als Zusatz, nie allein ausreichend)
     *
     * @param companyIds optionale Ueberschreibung der Company-ID-Tabelle
     *        (fuer Tests). Default ist die belegte Registry.
     */
    fun analyze(
        observation: ScanObservation,
        companyIds: Map<Int, ScooterManufacturer> = CompanyIdRegistry.all()
            .associate { it.companyId to it.family }
    ): AdvertisementEvidence {
        val kinds = LinkedHashSet<EvidenceKind>()

        // ---- 1. Name ----------------------------------------------------
        val resolved = DeviceNameResolver.resolve(observation)
        if (resolved.hasName) {
            val manufacturer = ScooterManufacturer.classify(resolved.name)
            val nameHint = ScooterCandidateFilter.looksLikeScooter(resolved.name)
            if (manufacturer.isKnown || nameHint) {
                kinds.add(EvidenceKind.NAME)
                return AdvertisementEvidence(
                    kind = EvidenceKind.NAME,
                    strength = EvidenceStrength.STRONG,
                    manufacturer = manufacturer,
                    matchedValue = resolved.name,
                    description = "Geraetename \"${resolved.name}\" deutet auf " +
                        "${manufacturer.displayName} hin (Quelle: ${resolved.source.name}).",
                    allKinds = kinds
                )
            }
        }

        // ---- 2. Service-UUIDs -------------------------------------------
        val normalizedServices = observation.serviceUuids.mapNotNull {
            ScanObservation.normalizeUuid(it)
        }.toSet()
        val strongService = SERVICE_HINTS
            .filter { it.strength == EvidenceStrength.STRONG }
            .firstOrNull { it.uuid in normalizedServices }
        if (strongService != null) {
            kinds.add(EvidenceKind.SERVICE_UUID)
            return AdvertisementEvidence(
                kind = EvidenceKind.SERVICE_UUID,
                strength = EvidenceStrength.STRONG,
                manufacturer = strongService.family,
                matchedValue = strongService.uuid,
                description = "Advertisement bewirbt ${strongService.uuid}. " +
                    strongService.rationale,
                allKinds = kinds
            )
        }

        // ---- 3. Company-ID ----------------------------------------------
        val matchedCompany = observation.manufacturerIds
            .firstNotNullOfOrNull { id -> companyIds[id]?.let { id to it } }
        if (matchedCompany != null) {
            kinds.add(EvidenceKind.MANUFACTURER_ID)
            return AdvertisementEvidence(
                kind = EvidenceKind.MANUFACTURER_ID,
                strength = EvidenceStrength.STRONG,
                manufacturer = matchedCompany.second,
                matchedValue = "0x%04X".format(matchedCompany.first),
                description = "Manufacturer Data enthaelt Company-ID " +
                    "0x%04X (${matchedCompany.second.displayName}).".format(matchedCompany.first),
                allKinds = kinds
            )
        }

        // ---- 4. Service-Data (nur Zusatz) -------------------------------
        val weakService = SERVICE_HINTS
            .filter { it.strength == EvidenceStrength.WEAK }
            .firstOrNull { hint ->
                observation.serviceDataUuids.mapNotNull { ScanObservation.normalizeUuid(it) }
                    .contains(hint.uuid)
            }
        if (weakService != null) {
            kinds.add(EvidenceKind.SERVICE_DATA)
            return AdvertisementEvidence(
                kind = EvidenceKind.SERVICE_DATA,
                strength = EvidenceStrength.WEAK,
                manufacturer = weakService.family,
                matchedValue = weakService.uuid,
                description = "Service Data fuer ${weakService.uuid}. " +
                    weakService.rationale,
                allKinds = kinds
            )
        }

        // ---- Kein Hinweis ------------------------------------------------
        return AdvertisementEvidence(
            description = if (observation.hasAdvertisementPayload) {
                "Advertisement enthaelt Daten, aber keinen belegbaren Scooter-Hinweis."
            } else {
                "Advertisement enthaelt keine auswertbaren Zusatzdaten."
            }
        )
    }
}
