package com.syrak.scooterlab.recognition

import com.syrak.scooterlab.ScanObservation
import com.syrak.scooterlab.ScooterManufacturer
import java.util.Locale

/* ============================================================================
 *  PHASE 17.3 — HARDWARE-VALIDIERUNG & FINGERPRINT-QUALITAET (reine Logik)
 *  ---------------------------------------------------------------------------
 *  WARUM DIESE DATEI EXISTIERT:
 *  Bis Phase 17.2 gab es nur drei eingebaute Fingerprints, die ALLE auf
 *  `NOT_TESTED` stehen. Es fehlte ein strukturierter Weg, echte Hardware-
 *  Tests nachvollziehbar zu dokumentieren und daraus den Reifegrad eines
 *  Fingerprints abzuleiten.
 *
 *  BINDENDE REGELN (aus dem Auftrag):
 *   • Es werden KEINE UUIDs, Hersteller-IDs oder Modellmerkmale erfunden.
 *   • Ein Fingerprint wird NIE automatisch hochgestuft. Der Reifegrad wird
 *     ausschliesslich aus dokumentierten Testdatensaetzen BERECHNET und
 *     getrennt vom `ModelFingerprint.hardwareTestStatus` gefuehrt.
 *   • MAC-Adressen und Seriennummern werden nie unmaskiert exportiert.
 *   • Widerspruechliche Merkmale werden sichtbar gemacht, nicht geglaettet.
 *   • Reine Lese-/Diagnoselogik: keine Schreibbefehle, kein Flashen.
 * ==========================================================================*/

/**
 * Reifegrad eines Fingerprints — bewusst getrennt von [HardwareTestStatus].
 *
 * [HardwareTestStatus] ist die im Profil *deklarierte* Angabe.
 * [FingerprintMaturity] ist der aus Testbelegen *berechnete* Stand.
 */
enum class FingerprintMaturity {
    /** Nur aus Dokumentation/Registry abgeleitet, kein verwertbarer Testbeleg. */
    DOCUMENTED,

    /** Es gibt einen Test, aber er ist unvollstaendig, teilweise oder widerspruechlich. */
    TESTED,

    /** Vollstaendiger, widerspruchsfreier Test mit vollstaendiger GATT-Discovery. */
    HARDWARE_CONFIRMED;

    val displayName: String
        get() = when (this) {
            DOCUMENTED -> "dokumentiert"
            TESTED -> "getestet (nicht bestätigt)"
            HARDWARE_CONFIRMED -> "hardware-bestätigt"
        }

    /** Nur der hoechste Grad gilt als echte Hardware-Bestaetigung. */
    val isHardwareConfirmed: Boolean get() = this == HARDWARE_CONFIRMED
}

/** Schweregrad einer Abweichung zwischen Erwartung und Beobachtung. */
enum class DeviationSeverity {
    /** Hinweis, keine Bewertung moeglich (z. B. Discovery unvollstaendig). */
    INFO,

    /** Merkmal fehlt oder weicht ab, aber nicht zwingend modellkritisch. */
    WARNING,

    /** Echter Widerspruch: schliesst eine Bestaetigung aus. */
    CRITICAL;

    val displayName: String
        get() = when (this) {
            INFO -> "Hinweis"
            WARNING -> "Abweichung"
            CRITICAL -> "Widerspruch"
        }
}

/** Eine einzelne, sichtbar dokumentierte Abweichung. */
data class HardwareDeviation(
    val field: String,
    val expected: String,
    val observed: String,
    val severity: DeviationSeverity,
    val detail: String = ""
) {
    fun label(): String =
        "$field: erwartet=$expected, beobachtet=$observed [${severity.displayName}]" +
            if (detail.isBlank()) "" else " — $detail"
}

/** Advertisement-Momentaufnahme aus einem realen Test (verlustfrei). */
data class HardwareScanSnapshot(
    val deviceName: String?,
    val serviceUuids: List<String> = emptyList(),
    val manufacturerIds: List<Int> = emptyList(),
    val serviceDataUuids: List<String> = emptyList(),
    val rssi: Int? = null
) {
    companion object {
        fun from(adv: AdvertisementFeatures?): HardwareScanSnapshot? = adv?.let {
            HardwareScanSnapshot(
                deviceName = it.deviceName,
                serviceUuids = it.serviceUuids,
                manufacturerIds = it.manufacturerIds,
                serviceDataUuids = it.serviceDataUuids,
                rssi = it.rssi
            )
        }
    }
}

/** GATT-Momentaufnahme aus einem realen Test. */
data class HardwareGattSnapshot(
    val discoveryStatus: GattDiscoveryStatus,
    val services: List<GattServiceFeature> = emptyList(),
    val errors: List<String> = emptyList()
) {
    val serviceUuids: List<String> get() = services.map { it.uuid }.distinct()

    val characteristicUuids: List<String>
        get() = services.flatMap { s -> s.characteristics.map { it.uuid } }.distinct()

    val characteristicCount: Int get() = services.sumOf { it.characteristics.size }

    val isComplete: Boolean get() = discoveryStatus == GattDiscoveryStatus.COMPLETE

    companion object {
        fun from(gatt: GattFeatures?): HardwareGattSnapshot? = gatt?.let {
            HardwareGattSnapshot(
                discoveryStatus = it.discoveryStatus,
                services = it.services,
                errors = it.errors
            )
        }
    }
}

/** Ist-Erkennungsergebnis, wie es die Engine waehrend des Tests lieferte. */
data class HardwareRecognitionSnapshot(
    val confidence: RecognitionConfidence,
    val detectedBrand: ScooterManufacturer,
    val detectedBrandFamily: BrandFamily,
    val detectedModelFamily: String?,
    val confirmedModel: String?,
    val probableModel: String?,
    val matchedProfileId: String?
) {
    companion object {
        fun from(result: RecognitionResult?): HardwareRecognitionSnapshot? = result?.let {
            HardwareRecognitionSnapshot(
                confidence = it.confidence,
                detectedBrand = it.detectedBrand,
                detectedBrandFamily = it.detectedBrandFamily,
                detectedModelFamily = it.detectedModelFamily,
                confirmedModel = it.confirmedModel,
                probableModel = it.probableModel,
                matchedProfileId = it.matchedProfileId
            )
        }
    }
}

/**
 * Ein strukturierter Hardware-Testdatensatz.
 *
 * @param addressMasked bereits maskierte Geraeteadresse (z. B. `AA:BB:**:**:EE:FF`).
 *   Roh-MACs werden bewusst NICHT aufgenommen.
 * @param testDate ISO-Datum `yyyy-MM-dd`; nur so ist ein Beleg nachvollziehbar.
 */
data class HardwareTestRecord(
    val id: String,
    val manufacturer: ScooterManufacturer,
    val modelName: String,
    val testDate: String,
    val tester: String? = null,
    val addressMasked: String? = null,
    val expectedFingerprintId: String? = null,
    val scan: HardwareScanSnapshot? = null,
    val gatt: HardwareGattSnapshot? = null,
    val observedRecognition: HardwareRecognitionSnapshot? = null,
    val notes: String? = null
) {
    /** true, wenn [testDate] ein plausibles ISO-Datum ist. */
    val hasValidTestDate: Boolean
        get() {
            val m = ISO_DATE.matchEntire(testDate.trim()) ?: return false
            val month = m.groupValues[2].toIntOrNull() ?: return false
            val day = m.groupValues[3].toIntOrNull() ?: return false
            return month in 1..12 && day in 1..31
        }

    /** Welche Pflichtangaben fehlen, um diesen Test als Beleg zu verwerten. */
    val missingFields: List<String>
        get() = buildList {
            if (modelName.isBlank()) add("modelName")
            if (!hasValidTestDate) add("testDate")
            if (expectedFingerprintId.isNullOrBlank()) add("expectedFingerprintId")
            if (scan == null) add("scan")
            if (gatt == null) add("gatt")
            if (observedRecognition == null) add("observedRecognition")
        }

    /** Nur vollstaendige Datensaetze koennen einen Fingerprint bestaetigen. */
    val isComplete: Boolean get() = missingFields.isEmpty()

    companion object {
        private val ISO_DATE = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")
    }
}

/**
 * Ergebnis der Bewertung eines [HardwareTestRecord] gegen einen Fingerprint.
 */
data class HardwareTestAssessment(
    val recordId: String,
    val fingerprintId: String?,
    val maturity: FingerprintMaturity,
    val deviations: List<HardwareDeviation> = emptyList(),
    val missingEvidence: List<String> = emptyList(),
    val matchedEvidence: List<String> = emptyList()
) {
    val criticalCount: Int get() = deviations.count { it.severity == DeviationSeverity.CRITICAL }
    val warningCount: Int get() = deviations.count { it.severity == DeviationSeverity.WARNING }
    val isContradictory: Boolean get() = criticalCount > 0
    val isConfirming: Boolean get() = maturity == FingerprintMaturity.HARDWARE_CONFIRMED

    fun summary(): String = buildString {
        append("Test ").append(recordId).append(": ").append(maturity.displayName)
        append(" · passende Belege: ").append(matchedEvidence.size)
        append(" · fehlend: ").append(missingEvidence.size)
        append(" · Widersprüche: ").append(criticalCount)
    }
}

/**
 * Bewertet einen Testdatensatz gegen einen erwarteten Fingerprint.
 *
 * Es wird NICHTS hochgestuft und NICHTS erfunden: alle Aussagen leiten sich
 * ausschliesslich aus den im Datensatz vorhandenen Feldern ab.
 */
object HardwareTestEvaluator {

    fun assess(record: HardwareTestRecord, fingerprint: ModelFingerprint?): HardwareTestAssessment {
        if (fingerprint == null) {
            return HardwareTestAssessment(
                recordId = record.id,
                fingerprintId = null,
                maturity = FingerprintMaturity.DOCUMENTED,
                missingEvidence = listOf("kein erwarteter Fingerprint hinterlegt")
            )
        }

        val deviations = mutableListOf<HardwareDeviation>()
        val matched = mutableListOf<String>()
        val missing = mutableListOf<String>()

        val scan = record.scan
        val gatt = record.gatt
        val gattComplete = gatt?.isComplete == true

        /* ---- Advertisement: Name ---- */
        val name = scan?.deviceName
        val patterns = fingerprint.advertisement.namePatterns
        if (!name.isNullOrBlank() && patterns.isNotEmpty()) {
            val lower = name.lowercase(Locale.ROOT)
            if (patterns.any { lower.contains(it.lowercase(Locale.ROOT)) }) {
                matched += "NAME=$name"
            } else {
                deviations += HardwareDeviation(
                    field = "NAME",
                    expected = patterns.joinToString("/"),
                    observed = name,
                    severity = DeviationSeverity.WARNING,
                    detail = "Geraetename passt zu keinem Namensmuster"
                )
            }
        }

        /* ---- Advertisement: Service-UUIDs ---- */
        val expectedAdv = fingerprint.advertisement.serviceUuids.keys.map(::norm).toSet()
        val observedAdv = scan?.serviceUuids.orEmpty().map(::norm).toSet()
        for (uuid in expectedAdv) {
            if (uuid in observedAdv) matched += "ADV_SERVICE=$uuid"
            else {
                missing += "ADV_SERVICE=$uuid"
                deviations += HardwareDeviation(
                    field = "ADV_SERVICE",
                    expected = uuid,
                    observed = if (scan == null) "kein Scan" else "fehlt",
                    severity = DeviationSeverity.WARNING
                )
            }
        }
        for (uuid in observedAdv - expectedAdv) {
            if (!GenericUuids.isGeneric(uuid)) {
                deviations += HardwareDeviation(
                    field = "ADV_SERVICE_EXTRA",
                    expected = "—",
                    observed = uuid,
                    severity = DeviationSeverity.INFO,
                    detail = "Zusaetzlicher Service nicht im Fingerprint"
                )
            }
        }

        /* ---- Advertisement: Manufacturer-IDs ---- */
        val observedMfr = scan?.manufacturerIds.orEmpty().toSet()
        for (id in fingerprint.advertisement.manufacturerIds.keys) {
            val hex = "0x" + id.toString(16)
            if (id in observedMfr) matched += "ADV_COMPANY=$hex"
            else {
                missing += "ADV_COMPANY=$hex"
                deviations += HardwareDeviation(
                    field = "ADV_COMPANY",
                    expected = hex,
                    observed = if (scan == null) "kein Scan" else "fehlt",
                    severity = DeviationSeverity.WARNING
                )
            }
        }

        /* ---- GATT: Services ---- */
        val expectedGatt = fingerprint.gatt.serviceUuids.keys.map(::norm).toSet()
        val observedGatt = gatt?.serviceUuids.orEmpty().map(::norm).toSet()
        if (gatt == null) {
            if (expectedGatt.isNotEmpty()) missing += "GATT_SERVICE (keine Discovery)"
        } else {
            for (uuid in expectedGatt) {
                if (uuid in observedGatt) matched += "GATT_SERVICE=$uuid"
                else {
                    missing += "GATT_SERVICE=$uuid"
                    deviations += HardwareDeviation(
                        field = "GATT_SERVICE",
                        expected = uuid,
                        observed = if (gattComplete) "fehlt" else "nicht bewertbar",
                        severity = if (gattComplete) DeviationSeverity.CRITICAL else DeviationSeverity.WARNING,
                        detail = if (gattComplete) "Discovery vollstaendig, Service fehlt" else "Discovery unvollstaendig"
                    )
                }
            }
        }

        /* ---- GATT: Characteristics ---- */
        val expectedChars = fingerprint.gatt.characteristicUuids.keys.map(::norm).toSet()
        val observedChars = gatt?.characteristicUuids.orEmpty().map(::norm).toSet()
        if (gatt != null) {
            for (uuid in expectedChars) {
                if (uuid in observedChars) matched += "GATT_CHAR=$uuid"
                else {
                    missing += "GATT_CHAR=$uuid"
                    deviations += HardwareDeviation(
                        field = "GATT_CHAR",
                        expected = uuid,
                        observed = if (gattComplete) "fehlt" else "nicht bewertbar",
                        severity = if (gattComplete) DeviationSeverity.CRITICAL else DeviationSeverity.WARNING
                    )
                }
            }
        }

        /* ---- GATT: erforderliche Properties ---- */
        if (gatt != null) {
            val charByUuid = gatt.services.flatMap { it.characteristics }
                .associateBy { norm(it.uuid) }
            for ((uuid, required) in fingerprint.gatt.requiredProperties) {
                val observed = charByUuid[norm(uuid)] ?: continue
                val missingProps = required - observed.properties
                if (missingProps.isEmpty()) {
                    matched += "GATT_PROPERTY=$uuid"
                } else {
                    deviations += HardwareDeviation(
                        field = "GATT_PROPERTY",
                        expected = "$uuid ${required.sortedBy { it.ordinal }}",
                        observed = "$uuid ${observed.properties.sortedBy { it.ordinal }}",
                        severity = DeviationSeverity.CRITICAL,
                        detail = "erforderliche Properties fehlen: ${missingProps.sortedBy { it.ordinal }}"
                    )
                }
            }
        }

        /* ---- Discovery-Status ---- */
        if (gatt != null && !gattComplete) {
            deviations += HardwareDeviation(
                field = "GATT_DISCOVERY",
                expected = GattDiscoveryStatus.COMPLETE.displayName,
                observed = gatt.discoveryStatus.displayName,
                severity = DeviationSeverity.INFO,
                detail = "GATT-Bewertung nur eingeschraenkt moeglich"
            )
        }

        /* ---- Ist-Erkennung: Marke / Profil / Konfidenz ---- */
        val observedRec = record.observedRecognition
        if (observedRec != null) {
            if (observedRec.detectedBrand != ScooterManufacturer.UNKNOWN &&
                observedRec.detectedBrand != fingerprint.manufacturer
            ) {
                deviations += HardwareDeviation(
                    field = "BRAND",
                    expected = fingerprint.manufacturer.displayName,
                    observed = observedRec.detectedBrand.displayName,
                    severity = DeviationSeverity.CRITICAL,
                    detail = "erkannte Marke widerspricht dem Fingerprint"
                )
            }
            if (!observedRec.matchedProfileId.isNullOrBlank() &&
                observedRec.matchedProfileId != fingerprint.id
            ) {
                deviations += HardwareDeviation(
                    field = "MATCHED_PROFILE",
                    expected = fingerprint.id,
                    observed = observedRec.matchedProfileId,
                    severity = DeviationSeverity.WARNING
                )
            }
            if (observedRec.confidence == RecognitionConfidence.CONFIRMED &&
                !fingerprint.isHardwareConfirmed
            ) {
                deviations += HardwareDeviation(
                    field = "CONFIDENCE",
                    expected = "nicht hardware-bestätigt",
                    observed = "CONFIRMED",
                    severity = DeviationSeverity.CRITICAL,
                    detail = "CONFIRMED ohne hardware-bestätigten Fingerprint ist unzulaessig"
                )
            }
        }

        /* ---- Reifegrad ableiten (NIE automatisch hochstufen) ---- */
        val maturity = when {
            !record.isComplete -> FingerprintMaturity.DOCUMENTED
            deviations.any { it.severity == DeviationSeverity.CRITICAL } -> FingerprintMaturity.TESTED
            matched.isEmpty() -> FingerprintMaturity.DOCUMENTED
            gattComplete -> FingerprintMaturity.HARDWARE_CONFIRMED
            else -> FingerprintMaturity.TESTED
        }

        return HardwareTestAssessment(
            recordId = record.id,
            fingerprintId = fingerprint.id,
            maturity = maturity,
            deviations = deviations,
            missingEvidence = missing,
            matchedEvidence = matched
        )
    }

    private fun norm(uuid: String): String =
        ScanObservation.normalizeUuid(uuid) ?: uuid.trim().lowercase(Locale.ROOT)
}

/**
 * Sammlung von Hardware-Testdatensaetzen. Bewusst ohne eingebaute
 * Bestaetigungen: die Registry startet leer, bis echte Tests dokumentiert sind.
 */
class HardwareTestRegistry(records: List<HardwareTestRecord> = emptyList()) {

    private val entries: List<HardwareTestRecord> = records.toList()

    fun all(): List<HardwareTestRecord> = entries

    fun isEmpty(): Boolean = entries.isEmpty()

    fun size(): Int = entries.size

    fun byId(id: String): HardwareTestRecord? = entries.firstOrNull { it.id == id }

    fun byFingerprintId(fingerprintId: String): List<HardwareTestRecord> =
        entries.filter { it.expectedFingerprintId == fingerprintId }

    companion object {
        /** Leere Registry — der ehrliche Ausgangszustand ohne echte Tests. */
        fun empty(): HardwareTestRegistry = HardwareTestRegistry()

        fun of(vararg records: HardwareTestRecord): HardwareTestRegistry =
            HardwareTestRegistry(records.toList())
    }
}

/**
 * Qualitaetsbericht eines einzelnen Fingerprints.
 *
 * Trennt ausdruecklich:
 *  • [declaredStatus] — was im Profil steht (`hardwareTestStatus`),
 *  • [maturity]       — was die Testbelege tatsaechlich hergeben.
 */
data class FingerprintQualityReport(
    val fingerprintId: String,
    val modelName: String,
    val declaredStatus: HardwareTestStatus,
    val maturity: FingerprintMaturity,
    val testCount: Int,
    val confirmingTestIds: List<String> = emptyList(),
    val contradictoryTestIds: List<String> = emptyList(),
    val missingEvidence: List<String> = emptyList(),
    val deviations: List<HardwareDeviation> = emptyList()
) {
    /** Der berechnete Stand bestaetigt den deklarierten Stand. */
    val statusMatchesDeclared: Boolean
        get() = (declaredStatus == HardwareTestStatus.CONFIRMED_ON_HARDWARE) == maturity.isHardwareConfirmed

    /**
     * Es liegen Belege fuer eine Bestaetigung vor, das Profil ist aber noch
     * nicht hochgestuft. Wird nur GEMELDET — kein automatisches Upgrade.
     */
    val upgradePending: Boolean
        get() = maturity.isHardwareConfirmed && declaredStatus != HardwareTestStatus.CONFIRMED_ON_HARDWARE

    val hasContradictions: Boolean get() = contradictoryTestIds.isNotEmpty()

    fun summary(): String = buildString {
        append(modelName).append(": deklariert=").append(declaredStatus.displayName)
        append(", berechnet=").append(maturity.displayName)
        append(" · Tests: ").append(testCount)
        if (missingEvidence.isNotEmpty()) append(" · fehlende Belege: ").append(missingEvidence.size)
        if (hasContradictions) append(" · Widersprüche: ").append(contradictoryTestIds.size)
    }
}

/**
 * Berechnet den Reifegrad aller Fingerprints aus der Testregistry.
 *
 * Diese Klasse veraendert NIE einen [ModelFingerprint]. Sie liefert nur einen
 * Bericht, der in UI und Export angezeigt wird.
 */
object FingerprintQualityAudit {

    fun audit(
        database: ModelFingerprintDatabase,
        registry: HardwareTestRegistry
    ): List<FingerprintQualityReport> =
        database.all().map { auditOne(it, registry) }

    fun auditOne(
        fingerprint: ModelFingerprint,
        registry: HardwareTestRegistry
    ): FingerprintQualityReport {
        val records = registry.byFingerprintId(fingerprint.id)
        val assessments = records.map { HardwareTestEvaluator.assess(it, fingerprint) }

        val confirming = assessments.filter { it.isConfirming }
        val contradictory = assessments.filter { it.isContradictory }

        // Reifegrad = bester belastbarer Einzelbeleg. Ein Widerspruch in einem
        // anderen Test hebt eine Bestaetigung NICHT auf, wird aber sichtbar
        // als Widerspruch gefuehrt.
        val maturity = when {
            confirming.isNotEmpty() -> FingerprintMaturity.HARDWARE_CONFIRMED
            assessments.any { it.maturity == FingerprintMaturity.TESTED } -> FingerprintMaturity.TESTED
            else -> FingerprintMaturity.DOCUMENTED
        }

        // Fehlende Belege: erwartete Merkmale, die in KEINEM vollstaendigen
        // Test als passend beobachtet wurden.
        val coveredEvidence = assessments.flatMap { it.matchedEvidence }.toSet()
        val expectedEvidence = expectedEvidence(fingerprint)
        val missingEvidence = expectedEvidence.filterNot { it in coveredEvidence }

        return FingerprintQualityReport(
            fingerprintId = fingerprint.id,
            modelName = fingerprint.modelName,
            declaredStatus = fingerprint.hardwareTestStatus,
            maturity = maturity,
            testCount = records.size,
            confirmingTestIds = confirming.map { it.recordId },
            contradictoryTestIds = contradictory.map { it.recordId },
            missingEvidence = missingEvidence,
            deviations = assessments.flatMap { it.deviations }
        )
    }

    /** Alle erwarteten Merkmale eines Fingerprints in stabiler Textform. */
    fun expectedEvidence(fingerprint: ModelFingerprint): List<String> = buildList {
        fingerprint.advertisement.serviceUuids.keys.forEach { add("ADV_SERVICE=${norm(it)}") }
        fingerprint.advertisement.manufacturerIds.keys.forEach { add("ADV_COMPANY=0x${it.toString(16)}") }
        fingerprint.gatt.serviceUuids.keys.forEach { add("GATT_SERVICE=${norm(it)}") }
        fingerprint.gatt.characteristicUuids.keys.forEach { add("GATT_CHAR=${norm(it)}") }
    }

    private fun norm(uuid: String): String =
        ScanObservation.normalizeUuid(uuid) ?: uuid.trim().lowercase(Locale.ROOT)
}
