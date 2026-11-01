package com.syrak.scooterlab.recognition

import com.syrak.scooterlab.ScanObservation
import com.syrak.scooterlab.ScooterManufacturer

/* ============================================================================
 *  PHASE 17.1 — RECOGNITION ENGINE (reine Logik, UI-unabhaengig)
 *  ---------------------------------------------------------------------------
 *  Fuehrt die geforderten Schritte deterministisch zusammen:
 *   1. Geraet gefunden?
 *   2. Advertisement-Daten erfassen (strukturell)
 *   3. Marke schaetzen
 *   4. Modellfamilie schaetzen
 *   5. Konkretes Modell pruefen
 *   6. Erkennungsqualitaet bewerten
 *   7. Ergebnis + Begruendung liefern
 *
 *  BINDENDE REGELN:
 *   • Der Engine kennt KEIN Nutzerprofil. Nutzerprofil und Hardware-Erkennung
 *     bleiben strikt getrennt — das ist hier durch die Signatur erzwungen.
 *   • RSSI wird nie zur Modellbestimmung verwendet.
 *   • Generische UUIDs zaehlen nie als Modellbeweis.
 *   • Ein Name allein ergibt hoechstens FAMILY_ONLY.
 *   • Fehlende Daten zaehlen nie positiv.
 *   • Widersprueche senken die Konfidenz sichtbar.
 *   • Keine Prozentwerte, keine Zufaelle, keine Zeitabhaengigkeit.
 * ==========================================================================*/

/** Eingabe der Erkennung. Bewusst OHNE Nutzerprofil. */
data class RecognitionInput(
    val advertisement: AdvertisementFeatures? = null,
    val gatt: GattFeatures? = null
)

/**
 * Ergebnis der Hardware-Erkennung.
 *
 * [confirmedModel] ist nur bei [RecognitionConfidence.CONFIRMED] gesetzt.
 * [probableModel] ist nur bei [RecognitionConfidence.PROBABLE] gesetzt.
 * Ein Modellname ist damit nie staerker behauptet, als die Konfidenz erlaubt.
 */
data class RecognitionResult(
    val deviceFound: Boolean,
    val deviceName: String?,
    val address: String,
    val detectedBrand: ScooterManufacturer,
    val detectedBrandFamily: BrandFamily,
    val detectedModelFamily: String?,
    val confirmedModel: String?,
    val probableModel: String?,
    val matchedProfileId: String?,
    val confidence: RecognitionConfidence,
    val reasons: List<RecognitionReason>,
    val matchedFeatures: List<RecognitionFeatureMatch> = emptyList(),
    val missingFeatures: List<String> = emptyList(),
    val contradictoryFeatures: List<String> = emptyList(),
    val unknownFeatures: List<String> = emptyList(),
    val advertisement: AdvertisementFeatures? = null,
    val gatt: GattFeatures? = null
) {
    /** Konkretes, bestaetigtes Modell (nur bei CONFIRMED). */
    val detectedModel: String? get() = confirmedModel

    /** Maschinenlesbare Reason-Codes in stabiler Reihenfolge. */
    fun reasonCodes(): List<String> = reasons.map { it.code.name }

    /** true, wenn ein Reason-Code enthalten ist. */
    fun hasReason(code: RecognitionReasonCode): Boolean = reasons.any { it.code == code }

    /**
     * Deutsche Kurzbegruendung fuer die UI. Bewusst ohne Prozentwerte.
     */
    fun summarySentence(): String = when {
        !deviceFound -> "Kein Bluetooth-Gerät gefunden."
        confidence == RecognitionConfidence.CONFIRMED && confirmedModel != null ->
            "Bluetooth-Gerät gefunden. Modell $confirmedModel wurde anhand bestätigter technischer Merkmale bestätigt."
        confidence == RecognitionConfidence.PROBABLE && probableModel != null ->
            "Bluetooth-Gerät gefunden. Modell $probableModel ist wahrscheinlich, konnte aber nicht bestätigt werden."
        confidence == RecognitionConfidence.FAMILY_ONLY && detectedBrandFamily != BrandFamily.UNKNOWN ->
            "Bluetooth-Gerät gefunden. ${detectedBrandFamily.displayName}-Familienhinweis. " +
                "Das konkrete Modell konnte anhand der verfügbaren technischen Merkmale noch nicht bestätigt werden."
        else ->
            "Bluetooth-Gerät gefunden. Marke und Modell konnten anhand der verfügbaren technischen Merkmale nicht bestimmt werden."
    }
}

/**
 * Deterministische Erkennungs-Engine.
 *
 * @param database austauschbare Profilquelle (Standard: dokumentierte
 *   Built-in-Profile). Fuer Tests kann eine eigene Datenbank injiziert werden.
 */
class RecognitionEngine(
    private val database: ModelFingerprintDatabase = BuiltInModelFingerprints
) {

    /** Bequeme Variante fuer den Scan-Pfad. */
    fun recognize(observation: ScanObservation, gatt: GattFeatures? = null): RecognitionResult =
        recognize(RecognitionInput(AdvertisementFeatures.from(observation), gatt))

    fun recognize(input: RecognitionInput): RecognitionResult {
        val adv = input.advertisement
        val gatt = input.gatt
        val reasons = mutableListOf<RecognitionReason>()
        val address = adv?.address ?: ""
        val deviceName = adv?.deviceName

        /* ---------- Schritt 1: Geraet gefunden? ---------- */
        val hasGatt = gatt != null && gatt.hasAnyData
        if (adv == null && !hasGatt) {
            reasons += RecognitionReason(RecognitionReasonCode.NO_DEVICE, "keine Advertisement- und keine GATT-Daten")
            return emptyResult(address, deviceName, reasons, adv, gatt)
        }
        reasons += RecognitionReason(
            RecognitionReasonCode.DEVICE_FOUND,
            deviceName?.takeIf { it.isNotBlank() } ?: "ohne Namen"
        )

        /* ---------- Schritt 2: Datenlage ehrlich benennen ---------- */
        if (adv == null || !adv.hasManufacturerData) {
            reasons += RecognitionReason(
                RecognitionReasonCode.MISSING_MANUFACTURER_DATA,
                "keine Manufacturer Specific Data empfangen"
            )
        }
        if (adv == null || !adv.hasServiceData) {
            reasons += RecognitionReason(
                RecognitionReasonCode.MISSING_SERVICE_DATA,
                "keine Service Data empfangen"
            )
        }
        if (adv != null && adv.hasName && adv.isNameOnly) {
            reasons += RecognitionReason(
                RecognitionReasonCode.NAME_ONLY_NO_PAYLOAD,
                "nur Gerätename, kein Advertisement-Payload"
            )
        }
        if (adv != null && adv.rssi != null) {
            reasons += RecognitionReason(
                RecognitionReasonCode.RSSI_IGNORED,
                "RSSI ${adv.rssi} dBm wird nicht zur Modellbestimmung verwendet"
            )
        }
        when {
            gatt == null || gatt.discoveryStatus == GattDiscoveryStatus.NOT_ATTEMPTED ->
                reasons += RecognitionReason(
                    RecognitionReasonCode.GATT_NOT_ATTEMPTED,
                    "keine GATT-Discovery vorhanden"
                )
            gatt.isIncomplete -> reasons += RecognitionReason(
                RecognitionReasonCode.GATT_INCOMPLETE,
                "GATT-Discovery ${gatt.discoveryStatus.displayName}"
            )
        }

        /* ---------- Merkmalsmengen (generische UUIDs entfernt) ---------- */
        val advServices = adv?.specificServiceUuids.orEmpty().toSet()
        val advServiceData = adv?.specificServiceDataUuids.orEmpty().toSet()
        val advCompanyIds = adv?.manufacturerIds.orEmpty().toSet()
        val gattServices = gatt?.specificServiceUuids.orEmpty().toSet()
        val allServices = advServices + gattServices

        if (adv != null) {
            val genericCount = adv.serviceUuids.count { GenericUuids.isGeneric(it) }
            if (genericCount > 0) {
                reasons += RecognitionReason(
                    RecognitionReasonCode.SERVICE_UUID_GENERIC_IGNORED,
                    "$genericCount generische Service-UUID(s) ignoriert"
                )
            }
        }

        /* ---------- Schritt 3: Marke schaetzen ---------- */
        data class BrandVote(val manufacturer: ScooterManufacturer, val source: String)
        val votes = mutableListOf<BrandVote>()

        val nameBrand = deviceName?.let { ScooterManufacturer.classify(it) } ?: ScooterManufacturer.UNKNOWN
        if (nameBrand != ScooterManufacturer.UNKNOWN) {
            votes += BrandVote(nameBrand, "name:$deviceName")
            reasons += RecognitionReason(
                RecognitionReasonCode.NAME_BRAND_HINT,
                "$nameBrand aus Gerätename"
            )
        } else if (adv?.hasName == true) {
            reasons += RecognitionReason(RecognitionReasonCode.NAME_GENERIC, "Gerätename ohne Markenbezug")
        }

        // Service-basierte Markenhinweise aus der Profil-Datenbank.
        val knownServiceUuids = mutableSetOf<String>()
        for (profile in database.all()) {
            knownServiceUuids += profile.advertisement.serviceUuids.keys
            knownServiceUuids += profile.gatt.serviceUuids.keys

            val matchedAdv = profile.advertisement.serviceUuids.filterKeys { it in advServices }
            val matchedGatt = profile.gatt.serviceUuids.filterKeys { it in gattServices }
            val matched = matchedAdv + matchedGatt
            val counting = matched.filterValues { it.grade.countsTowardModel }
            if (counting.isNotEmpty()) {
                votes += BrandVote(profile.manufacturer, "service:${profile.id}")
                reasons += RecognitionReason(
                    RecognitionReasonCode.SERVICE_UUID_MATCH,
                    "${profile.id} über ${counting.keys.joinToString(",")}"
                )
            }
        }

        // Company IDs: unbekannte IDs bleiben unbekannt.
        for (companyId in advCompanyIds) {
            val owners = database.all().filter { it.advertisement.manufacturerIds.containsKey(companyId) }
            if (owners.isEmpty()) {
                reasons += RecognitionReason(
                    RecognitionReasonCode.UNKNOWN_MANUFACTURER_ID,
                    "Company-ID 0x${companyId.toString(16)} nicht in Datenbank"
                )
            } else {
                owners.forEach { votes += BrandVote(it.manufacturer, "company:0x${companyId.toString(16)}") }
                reasons += RecognitionReason(
                    RecognitionReasonCode.COMPANY_ID_MATCH,
                    "Company-ID 0x${companyId.toString(16)}"
                )
            }
        }

        if (advServiceData.isNotEmpty()) {
            reasons += RecognitionReason(
                RecognitionReasonCode.SERVICE_DATA_PRESENT,
                advServiceData.joinToString(",")
            )
        }

        val unknownServices = allServices - knownServiceUuids
        if (unknownServices.isNotEmpty()) {
            reasons += RecognitionReason(
                RecognitionReasonCode.UNKNOWN_SERVICE_UUID,
                unknownServices.joinToString(",")
            )
        }

        // Marken-/Familienaufloesung.
        val distinctBrands = votes.map { it.manufacturer }.distinct()
        val distinctFamilies = distinctBrands
            .map { BrandFamily.of(it) }
            .filter { it != BrandFamily.UNKNOWN }
            .distinct()

        var brand = ScooterManufacturer.UNKNOWN
        var family = BrandFamily.UNKNOWN
        when {
            distinctFamilies.size > 1 -> {
                reasons += RecognitionReason(
                    RecognitionReasonCode.CONTRADICTION_BRAND,
                    "widersprüchliche Markenfamilien: ${distinctFamilies.joinToString { it.displayName }}"
                )
            }
            distinctFamilies.size == 1 -> {
                family = distinctFamilies.first()
                brand = if (distinctBrands.size == 1) {
                    distinctBrands.first()
                } else {
                    reasons += RecognitionReason(
                        RecognitionReasonCode.BRAND_FAMILY_AMBIGUOUS,
                        "Marke innerhalb der Familie ${family.displayName} nicht eindeutig"
                    )
                    ScooterManufacturer.UNKNOWN
                }
            }
        }

        /* ---------- Schritte 4–6: Modell pruefen und bewerten ---------- */
        val scores = mutableListOf<ProfileScore>()
        if (family != BrandFamily.UNKNOWN) {
            for (profile in database.all().filter { it.brandFamily == family }) {
                scores += scoreProfile(profile, deviceName, nameBrand, advServices, advServiceData, advCompanyIds, gattServices, gatt)
            }
        }

        val viable = scores.filter { it.matches.isNotEmpty() }
        val classified = viable.map { it to classify(it) }.filter { it.second != RecognitionConfidence.UNKNOWN }

        var confidence = RecognitionConfidence.UNKNOWN
        var confirmedModel: String? = null
        var probableModel: String? = null
        var detectedModelFamily: String? = null
        var matchedProfileId: String? = null
        var matchedFeatures: List<RecognitionFeatureMatch> = emptyList()
        var missingFeatures: List<String> = emptyList()
        var contradictory: List<String> = emptyList()

        if (classified.isNotEmpty()) {
            val topRank = classified.maxOf { it.second.rank }
            val byRank = classified.filter { it.second.rank == topRank }
            val topCount = byRank.maxOf { it.first.countingMatches() }
            val top = byRank.filter { it.first.countingMatches() == topCount }

            if (top.size > 1) {
                reasons += RecognitionReason(
                    RecognitionReasonCode.AMBIGUOUS_PROFILE_MATCH,
                    top.joinToString(",") { it.first.profile.id }
                )
                confidence = RecognitionConfidence.FAMILY_ONLY
                detectedModelFamily = family.displayName
                matchedFeatures = top.flatMap { it.first.matches }.distinct()
                contradictory = top.flatMap { it.first.contradictions }.distinct()
                reasons += RecognitionReason(
                    RecognitionReasonCode.FAMILY_ONLY_EVIDENCE,
                    "mehrere Profile gleichwertig — kein Modell behauptet"
                )
            } else {
                val score = top.first().first
                confidence = top.first().second
                matchedProfileId = score.profile.id
                matchedFeatures = score.matches
                missingFeatures = score.missingFeatures()
                contradictory = score.contradictions.distinct()
                detectedModelFamily = score.profile.modelFamily
                when (confidence) {
                    RecognitionConfidence.CONFIRMED -> {
                        confirmedModel = score.profile.modelName
                        reasons += RecognitionReason(
                            RecognitionReasonCode.CONFIRMED_PROFILE_MATCH,
                            score.profile.id
                        )
                    }
                    RecognitionConfidence.PROBABLE -> {
                        probableModel = score.profile.modelName
                        reasons += RecognitionReason(
                            RecognitionReasonCode.PROBABLE_PROFILE_MATCH,
                            score.profile.id
                        )
                    }
                    RecognitionConfidence.FAMILY_ONLY -> {
                        reasons += RecognitionReason(
                            RecognitionReasonCode.WEAK_PROFILE_MATCH,
                            "${score.profile.id} — nur Familienhinweis"
                        )
                    }
                    RecognitionConfidence.UNKNOWN -> Unit
                }
                if (contradictory.isNotEmpty()) {
                    reasons += RecognitionReason(
                        RecognitionReasonCode.CONTRADICTION_SERVICE,
                        contradictory.joinToString("; ")
                    )
                }
            }
        } else {
            reasons += RecognitionReason(
                RecognitionReasonCode.NO_PROFILE_MATCH,
                "kein Profil ausreichend gestützt"
            )
            if (family != BrandFamily.UNKNOWN) {
                confidence = RecognitionConfidence.FAMILY_ONLY
                detectedModelFamily = family.displayName
                reasons += RecognitionReason(
                    RecognitionReasonCode.FAMILY_ONLY_EVIDENCE,
                    "Marke/Familie belegt, Modell nicht"
                )
            }
        }

        // Widersprueche begrenzen die Konfidenz sichtbar.
        if (contradictory.isNotEmpty() && confidence.rank > RecognitionConfidence.FAMILY_ONLY.rank) {
            confidence = RecognitionConfidence.FAMILY_ONLY
            confirmedModel = null
            probableModel = null
            reasons += RecognitionReason(
                RecognitionReasonCode.CONTRADICTION_BRAND,
                "Konfidenz wegen Widerspruch auf FAMILY_ONLY begrenzt"
            )
        }

        // Marke komplett unbekannt => kein Modell, keine Familie.
        if (family == BrandFamily.UNKNOWN) {
            confidence = RecognitionConfidence.UNKNOWN
            confirmedModel = null
            probableModel = null
            detectedModelFamily = null
            matchedProfileId = null
        }

        val unknownFeatures = buildList {
            addAll(unknownServices.map { "SERVICE:$it" })
            addAll(advCompanyIds.filter { id -> database.all().none { it.advertisement.manufacturerIds.containsKey(id) } }
                .map { "COMPANY:0x${it.toString(16)}" })
            gatt?.errors?.forEach { add("GATT_ERROR:$it") }
        }

        return RecognitionResult(
            deviceFound = true,
            deviceName = deviceName,
            address = address,
            detectedBrand = brand,
            detectedBrandFamily = family,
            detectedModelFamily = detectedModelFamily,
            confirmedModel = confirmedModel,
            probableModel = probableModel,
            matchedProfileId = matchedProfileId,
            confidence = confidence,
            reasons = reasons.distinctBy { it.code to it.detail },
            matchedFeatures = matchedFeatures,
            missingFeatures = missingFeatures,
            contradictoryFeatures = contradictory,
            unknownFeatures = unknownFeatures,
            advertisement = adv,
            gatt = gatt
        )
    }

    /* ------------------------- interne Bewertung ------------------------- */

    private data class ProfileScore(
        val profile: ModelFingerprint,
        val matches: MutableList<RecognitionFeatureMatch>,
        val contradictions: MutableList<String>,
        val expected: MutableList<String>
    ) {
        fun countingMatches(): Int = matches.count { it.grade.countsTowardModel }

        fun missingFeatures(): List<String> =
            expected.filterNot { exp -> matches.any { it.value.startsWith(exp) } }.distinct()
    }

    private fun scoreProfile(
        profile: ModelFingerprint,
        deviceName: String?,
        nameBrand: ScooterManufacturer,
        advServices: Set<String>,
        advServiceData: Set<String>,
        advCompanyIds: Set<Int>,
        gattServices: Set<String>,
        gatt: GattFeatures?
    ): ProfileScore {
        val matches = mutableListOf<RecognitionFeatureMatch>()
        val contradictions = mutableListOf<String>()
        val expected = mutableListOf<String>()

        // Name (nie allein ausreichend — Bewertung deckelt NAME-only).
        if (!deviceName.isNullOrBlank()) {
            val lower = deviceName.lowercase()
            for (pattern in profile.advertisement.namePatterns) {
                if (lower.contains(pattern.lowercase())) {
                    matches += RecognitionFeatureMatch("NAME", pattern, EvidenceGrade.PROBABLE, "profile:${profile.id}")
                }
            }
        }

        // Advertisement-Services.
        profile.advertisement.serviceUuids.forEach { (uuid, ev) ->
            expected += uuid
            if (uuid in advServices) {
                matches += RecognitionFeatureMatch("ADV_SERVICE", uuid, ev.grade, ev.source)
            }
        }

        // Service Data.
        profile.advertisement.serviceDataUuids.forEach { (uuid, ev) ->
            expected += uuid
            if (uuid in advServiceData) {
                matches += RecognitionFeatureMatch("ADV_SERVICE_DATA", uuid, ev.grade, ev.source)
            }
        }

        // Company IDs.
        profile.advertisement.manufacturerIds.forEach { (id, ev) ->
            expected += "0x${id.toString(16)}"
            if (id in advCompanyIds) {
                matches += RecognitionFeatureMatch("ADV_COMPANY", "0x${id.toString(16)}", ev.grade, ev.source)
            }
        }

        // GATT-Services.
        profile.gatt.serviceUuids.forEach { (uuid, ev) ->
            expected += uuid
            if (uuid in gattServices) {
                matches += RecognitionFeatureMatch("GATT_SERVICE", uuid, ev.grade, ev.source)
            }
        }

        // GATT-Characteristics.
        profile.gatt.characteristicUuids.forEach { (uuid, ev) ->
            expected += uuid
            if (gatt?.characteristic(uuid) != null) {
                matches += RecognitionFeatureMatch("GATT_CHARACTERISTIC", uuid, ev.grade, ev.source)
            }
        }

        // GATT-Properties (nur technische Faehigkeiten, keine Rollen).
        profile.gatt.requiredProperties.forEach { (uuid, required) ->
            val ch = gatt?.characteristic(uuid)
            if (ch != null && ch.properties.containsAll(required)) {
                matches += RecognitionFeatureMatch(
                    "GATT_PROPERTY",
                    "$uuid:${required.sortedBy { it.ordinal }.joinToString("|") { it.name }}",
                    profile.gatt.characteristicUuids[uuid]?.grade ?: EvidenceGrade.UNKNOWN,
                    profile.source
                )
            }
        }

        // Widerspruch: ein Service, der zu einer anderen Markenfamilie gehoert.
        for (other in database.all()) {
            if (other.brandFamily == profile.brandFamily) continue
            val otherServices = other.advertisement.serviceUuids.keys + other.gatt.serviceUuids.keys
            val overlap = (advServices + gattServices).intersect(otherServices)
            if (overlap.isNotEmpty()) {
                contradictions += "Service ${overlap.first()} gehört zur Familie ${other.brandFamily.displayName}"
            }
        }

        // Widerspruch: Name nennt eine andere Markenfamilie.
        if (nameBrand != ScooterManufacturer.UNKNOWN && BrandFamily.of(nameBrand) != profile.brandFamily) {
            contradictions += "Gerätename deutet auf ${BrandFamily.of(nameBrand).displayName}"
        }

        return ProfileScore(profile, matches, contradictions, expected)
    }

    private fun classify(score: ProfileScore): RecognitionConfidence {
        val meaningful = score.matches.filter { it.grade.countsTowardModel }
        val distinctKinds = meaningful.map { it.kind }.distinct().size
        val nameOnly = meaningful.isNotEmpty() && meaningful.all { it.kind == "NAME" }

        return when {
            // Widerspruch: hoechstens Familienhinweis, nie Modell.
            score.contradictions.isNotEmpty() ->
                if (meaningful.isEmpty()) RecognitionConfidence.UNKNOWN else RecognitionConfidence.FAMILY_ONLY
            // Bestaetigung nur mit hardware-bestaetigtem Profil, ausschliesslich
            // CONFIRMED-Merkmalen und mindestens drei unabhaengigen Kategorien.
            score.profile.isHardwareConfirmed &&
                meaningful.isNotEmpty() &&
                meaningful.all { it.grade == EvidenceGrade.CONFIRMED } &&
                distinctKinds >= 3 -> RecognitionConfidence.CONFIRMED
            // Name allein ist nie Modellbeweis.
            nameOnly -> RecognitionConfidence.FAMILY_ONLY
            // Zwei unabhaengige, mindestens plausible Merkmale.
            meaningful.size >= 2 && distinctKinds >= 2 -> RecognitionConfidence.PROBABLE
            meaningful.isNotEmpty() -> RecognitionConfidence.FAMILY_ONLY
            else -> RecognitionConfidence.UNKNOWN
        }
    }

    private fun emptyResult(
        address: String,
        deviceName: String?,
        reasons: List<RecognitionReason>,
        adv: AdvertisementFeatures?,
        gatt: GattFeatures?
    ): RecognitionResult = RecognitionResult(
        deviceFound = false,
        deviceName = deviceName,
        address = address,
        detectedBrand = ScooterManufacturer.UNKNOWN,
        detectedBrandFamily = BrandFamily.UNKNOWN,
        detectedModelFamily = null,
        confirmedModel = null,
        probableModel = null,
        matchedProfileId = null,
        confidence = RecognitionConfidence.UNKNOWN,
        reasons = reasons,
        advertisement = adv,
        gatt = gatt
    )
}
