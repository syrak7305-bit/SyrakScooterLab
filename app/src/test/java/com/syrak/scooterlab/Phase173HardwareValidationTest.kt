package com.syrak.scooterlab

import com.syrak.scooterlab.diagnostics.GattCharacteristicRecord
import com.syrak.scooterlab.diagnostics.GattProperty
import com.syrak.scooterlab.diagnostics.GattServiceRecord
import com.syrak.scooterlab.diagnostics.HardwareTestReport
import com.syrak.scooterlab.recognition.AdvertisementFeatures
import com.syrak.scooterlab.recognition.BrandFamily
import com.syrak.scooterlab.recognition.BuiltInModelFingerprints
import com.syrak.scooterlab.recognition.DeviationSeverity
import com.syrak.scooterlab.recognition.FingerprintMaturity
import com.syrak.scooterlab.recognition.FingerprintQualityAudit
import com.syrak.scooterlab.recognition.GattDiscoveryStatus
import com.syrak.scooterlab.recognition.GattFeatures
import com.syrak.scooterlab.recognition.GattServiceFeature
import com.syrak.scooterlab.recognition.GattCharacteristicFeature
import com.syrak.scooterlab.recognition.HardwareGattSnapshot
import com.syrak.scooterlab.recognition.HardwareRecognitionSnapshot
import com.syrak.scooterlab.recognition.HardwareScanSnapshot
import com.syrak.scooterlab.recognition.HardwareTestEvaluator
import com.syrak.scooterlab.recognition.HardwareTestRecord
import com.syrak.scooterlab.recognition.HardwareTestRegistry
import com.syrak.scooterlab.recognition.HardwareTestStatus
import com.syrak.scooterlab.recognition.HardwareValidationExport
import com.syrak.scooterlab.recognition.RecognitionConfidence
import com.syrak.scooterlab.recognition.RecognitionEngine
import com.syrak.scooterlab.recognition.RecognitionExport
import com.syrak.scooterlab.recognition.RecognitionInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 17.3 — Tests fuer Hardware-Validierung und Fingerprint-Qualitaet.
 *
 * Reine Logik, keine Android-Abhaengigkeit, keine echten Geraete, keine
 * erfundenen UUIDs. Deckt vollstaendige, unvollstaendige und widerspruechliche
 * Hardware-Daten sowie fehlende/ungueltige Testbelege ab.
 */
class Phase173HardwareValidationTest {

    private val engine = RecognitionEngine()

    private val naveeService = "0000ffe0-0000-1000-8000-00805f9b34fb"
    private val naveeNotify = "0000ffe1-0000-1000-8000-00805f9b34fb"
    private val naveeWrite = "0000ffe2-0000-1000-8000-00805f9b34fb"
    private val naveeFingerprintId = "navee-gt3max-unconfirmed"

    /* ----------------------------- Helfer ----------------------------- */

    private fun naveeFingerprint() = BuiltInModelFingerprints.byId(naveeFingerprintId)!!

    private fun scan(
        name: String? = "NAVEE GT3 Max",
        services: List<String> = listOf(naveeService),
        manufacturerIds: List<Int> = emptyList()
    ) = HardwareScanSnapshot(
        deviceName = name,
        serviceUuids = services,
        manufacturerIds = manufacturerIds
    )

    private fun gatt(
        status: GattDiscoveryStatus = GattDiscoveryStatus.COMPLETE,
        services: List<GattServiceFeature> = listOf(
            GattServiceFeature(
                uuid = naveeService,
                characteristics = listOf(
                    GattCharacteristicFeature(naveeNotify, setOf(GattProperty.NOTIFY)),
                    GattCharacteristicFeature(
                        naveeWrite,
                        setOf(GattProperty.WRITE, GattProperty.WRITE_NO_RESPONSE)
                    )
                )
            )
        )
    ) = HardwareGattSnapshot(discoveryStatus = status, services = services)

    private fun observed(
        confidence: RecognitionConfidence = RecognitionConfidence.PROBABLE,
        brand: ScooterManufacturer = ScooterManufacturer.NAVEE,
        matchedProfileId: String? = naveeFingerprintId
    ) = HardwareRecognitionSnapshot(
        confidence = confidence,
        detectedBrand = brand,
        detectedBrandFamily = BrandFamily.NAVEE,
        detectedModelFamily = "NAVEE",
        confirmedModel = null,
        probableModel = "NAVEE GT3 Max (unbestätigt)",
        matchedProfileId = matchedProfileId
    )

    private fun record(
        id: String = "test-1",
        fingerprintId: String? = naveeFingerprintId,
        testDate: String = "2026-11-15",
        modelName: String = "NAVEE GT3 Max",
        scan: HardwareScanSnapshot? = scan(),
        gatt: HardwareGattSnapshot? = gatt(),
        observed: HardwareRecognitionSnapshot? = observed()
    ) = HardwareTestRecord(
        id = id,
        manufacturer = ScooterManufacturer.NAVEE,
        modelName = modelName,
        testDate = testDate,
        expectedFingerprintId = fingerprintId,
        scan = scan,
        gatt = gatt,
        observedRecognition = observed
    )

    /* ================= 1. Leere Registry = ehrlicher Ausgangszustand ================= */

    @Test
    fun emptyRegistryKeepsAllFingerprintsDocumented() {
        val reports = FingerprintQualityAudit.audit(
            BuiltInModelFingerprints,
            HardwareTestRegistry.empty()
        )
        assertEquals(3, reports.size)
        reports.forEach { r ->
            assertEquals(FingerprintMaturity.DOCUMENTED, r.maturity)
            assertEquals(0, r.testCount)
            assertFalse(r.maturity.isHardwareConfirmed)
            assertFalse(r.upgradePending)
            assertTrue(r.statusMatchesDeclared)
        }
    }

    @Test
    fun emptyRegistryHasNoBuiltInConfirmation() {
        val registry = HardwareTestRegistry.empty()
        assertTrue(registry.isEmpty())
        assertEquals(0, registry.size())
        assertNull(registry.byId("irgendwas"))
        assertTrue(registry.byFingerprintId(naveeFingerprintId).isEmpty())
    }

    /* ================= 2. Vollstaendiger, passender Test ================= */

    @Test
    fun completeMatchingRecordConfirmsFingerprint() {
        val assessment = HardwareTestEvaluator.assess(record(), naveeFingerprint())
        assertEquals(FingerprintMaturity.HARDWARE_CONFIRMED, assessment.maturity)
        assertTrue(assessment.isConfirming)
        assertFalse(assessment.isContradictory)
        assertEquals(0, assessment.criticalCount)
        assertTrue(assessment.matchedEvidence.contains("ADV_SERVICE=$naveeService"))
        assertTrue(assessment.matchedEvidence.contains("GATT_SERVICE=$naveeService"))
        assertTrue(assessment.matchedEvidence.contains("GATT_CHAR=$naveeNotify"))
    }

    @Test
    fun completeRecordProducesUpgradePendingButDoesNotMutateFingerprint() {
        val fingerprint = naveeFingerprint()
        val registry = HardwareTestRegistry.of(record())
        val report = FingerprintQualityAudit.auditOne(fingerprint, registry)

        assertEquals(FingerprintMaturity.HARDWARE_CONFIRMED, report.maturity)
        assertEquals(HardwareTestStatus.NOT_TESTED, report.declaredStatus)
        assertTrue(report.upgradePending)
        assertFalse(report.statusMatchesDeclared)
        // Der Fingerprint selbst bleibt unveraendert:
        assertEquals(HardwareTestStatus.NOT_TESTED, fingerprint.hardwareTestStatus)
        assertFalse(fingerprint.isHardwareConfirmed)
    }

    /* ================= 3. Unvollstaendige Hardware-Daten ================= */

    @Test
    fun missingGattMakesRecordIncompleteAndDocumented() {
        val r = record(gatt = null)
        assertFalse(r.isComplete)
        assertTrue(r.missingFields.contains("gatt"))
        val assessment = HardwareTestEvaluator.assess(r, naveeFingerprint())
        assertEquals(FingerprintMaturity.DOCUMENTED, assessment.maturity)
        assertFalse(assessment.isConfirming)
    }

    @Test
    fun missingScanMakesRecordIncomplete() {
        val r = record(scan = null)
        assertFalse(r.isComplete)
        assertTrue(r.missingFields.contains("scan"))
    }

    @Test
    fun missingObservedRecognitionMakesRecordIncomplete() {
        val r = record(observed = null)
        assertFalse(r.isComplete)
        assertTrue(r.missingFields.contains("observedRecognition"))
    }

    @Test
    fun missingFingerprintIdMakesRecordIncomplete() {
        val r = record(fingerprintId = null)
        assertFalse(r.isComplete)
        assertTrue(r.missingFields.contains("expectedFingerprintId"))
    }

    @Test
    fun blankModelNameMakesRecordIncomplete() {
        val r = record(modelName = "   ")
        assertFalse(r.isComplete)
        assertTrue(r.missingFields.contains("modelName"))
    }

    /* ================= 4. Fehlende / ungueltige Testbelege ================= */

    @Test
    fun invalidTestDateIsRejected() {
        assertFalse(record(testDate = "2026-13-01").hasValidTestDate)
        assertFalse(record(testDate = "15.11.2026").hasValidTestDate)
        assertFalse(record(testDate = "").hasValidTestDate)
        assertTrue(record(testDate = "2026-11-15").hasValidTestDate)
        assertTrue(record(testDate = "2026-02-28").hasValidTestDate)
    }

    @Test
    fun invalidTestDateMakesRecordIncomplete() {
        val r = record(testDate = "2026-13-01")
        assertFalse(r.isComplete)
        assertTrue(r.missingFields.contains("testDate"))
        val assessment = HardwareTestEvaluator.assess(r, naveeFingerprint())
        assertEquals(FingerprintMaturity.DOCUMENTED, assessment.maturity)
    }

    @Test
    fun recordWithoutFingerprintCannotConfirm() {
        val assessment = HardwareTestEvaluator.assess(record(fingerprintId = null), null)
        assertEquals(FingerprintMaturity.DOCUMENTED, assessment.maturity)
        assertFalse(assessment.isConfirming)
        assertTrue(assessment.missingEvidence.isNotEmpty())
    }

    /* ================= 5. Widerspruechliche Hardware-Daten ================= */

    @Test
    fun missingExpectedCharacteristicIsCriticalContradiction() {
        val gatt = gatt(
            services = listOf(
                GattServiceFeature(
                    uuid = naveeService,
                    characteristics = listOf(
                        GattCharacteristicFeature(naveeNotify, setOf(GattProperty.NOTIFY))
                    )
                )
            )
        )
        val assessment = HardwareTestEvaluator.assess(record(gatt = gatt), naveeFingerprint())
        assertTrue(assessment.isContradictory)
        assertTrue(assessment.criticalCount >= 1)
        assertTrue(assessment.deviations.any {
            it.field == "GATT_CHAR" && it.severity == DeviationSeverity.CRITICAL
        })
        assertEquals(FingerprintMaturity.TESTED, assessment.maturity)
    }

    @Test
    fun missingRequiredPropertyIsCritical() {
        val gatt = gatt(
            services = listOf(
                GattServiceFeature(
                    uuid = naveeService,
                    characteristics = listOf(
                        GattCharacteristicFeature(naveeNotify, setOf(GattProperty.READ)),
                        GattCharacteristicFeature(
                            naveeWrite,
                            setOf(GattProperty.WRITE, GattProperty.WRITE_NO_RESPONSE)
                        )
                    )
                )
            )
        )
        val assessment = HardwareTestEvaluator.assess(record(gatt = gatt), naveeFingerprint())
        assertTrue(assessment.deviations.any {
            it.field == "GATT_PROPERTY" && it.severity == DeviationSeverity.CRITICAL
        })
        assertEquals(FingerprintMaturity.TESTED, assessment.maturity)
    }

    @Test
    fun brandContradictionIsCritical() {
        val assessment = HardwareTestEvaluator.assess(
            record(observed = observed(brand = ScooterManufacturer.NINEBOT)),
            naveeFingerprint()
        )
        assertTrue(assessment.isContradictory)
        assertTrue(assessment.deviations.any { it.field == "BRAND" })
        assertEquals(FingerprintMaturity.TESTED, assessment.maturity)
    }

    @Test
    fun confirmedConfidenceOnUnconfirmedFingerprintIsCritical() {
        val assessment = HardwareTestEvaluator.assess(
            record(observed = observed(confidence = RecognitionConfidence.CONFIRMED)),
            naveeFingerprint()
        )
        assertTrue(assessment.isContradictory)
        assertTrue(assessment.deviations.any {
            it.field == "CONFIDENCE" && it.severity == DeviationSeverity.CRITICAL
        })
    }

    @Test
    fun missingAdvServiceIsWarningNotCritical() {
        val assessment = HardwareTestEvaluator.assess(
            record(scan = scan(services = emptyList())),
            naveeFingerprint()
        )
        assertTrue(assessment.deviations.any {
            it.field == "ADV_SERVICE" && it.severity == DeviationSeverity.WARNING
        })
    }

    @Test
    fun extraAdvServiceIsInfoOnly() {
        val extra = "0000abcd-0000-1000-8000-00805f9b34fb"
        val assessment = HardwareTestEvaluator.assess(
            record(scan = scan(services = listOf(naveeService, extra))),
            naveeFingerprint()
        )
        assertTrue(assessment.deviations.any {
            it.field == "ADV_SERVICE_EXTRA" && it.severity == DeviationSeverity.INFO
        })
    }

    /* ================= 6. Unvollstaendige Discovery ================= */

    @Test
    fun incompleteDiscoveryIsInfoAndNotConfirmed() {
        val assessment = HardwareTestEvaluator.assess(
            record(gatt = gatt(status = GattDiscoveryStatus.INCOMPLETE)),
            naveeFingerprint()
        )
        assertTrue(assessment.deviations.any {
            it.field == "GATT_DISCOVERY" && it.severity == DeviationSeverity.INFO
        })
        assertEquals(FingerprintMaturity.TESTED, assessment.maturity)
        assertFalse(assessment.isConfirming)
    }

    /* ================= 7. Fehlende Testbelege im Qualitaetsbericht ================= */

    @Test
    fun missingEvidenceListsUncoveredExpectedFeatures() {
        // Nur der Advertisement-Service wurde beobachtet, kein GATT.
        val r = record(gatt = null)
        val report = FingerprintQualityAudit.auditOne(naveeFingerprint(), HardwareTestRegistry.of(r))
        assertTrue(report.missingEvidence.any { it.startsWith("GATT_SERVICE=") })
        assertTrue(report.missingEvidence.any { it.startsWith("GATT_CHAR=") })
        assertFalse(report.missingEvidence.any { it == "ADV_SERVICE=$naveeService" })
    }

    @Test
    fun completeRecordLeavesNoMissingEvidence() {
        val report = FingerprintQualityAudit.auditOne(
            naveeFingerprint(),
            HardwareTestRegistry.of(record())
        )
        assertTrue(report.missingEvidence.isEmpty())
    }

    /* ================= 8. Mehrere Tests / Widerspruch neben Bestaetigung ================= */

    @Test
    fun confirmingAndContradictoryRecordsAreBothReported() {
        val confirming = record(id = "good")
        val contradictory = record(
            id = "bad",
            observed = observed(brand = ScooterManufacturer.NINEBOT)
        )
        val report = FingerprintQualityAudit.auditOne(
            naveeFingerprint(),
            HardwareTestRegistry.of(confirming, contradictory)
        )
        assertEquals(FingerprintMaturity.HARDWARE_CONFIRMED, report.maturity)
        assertTrue(report.confirmingTestIds.contains("good"))
        assertTrue(report.contradictoryTestIds.contains("bad"))
        assertTrue(report.hasContradictions)
        assertEquals(2, report.testCount)
    }

    /* ================= 9. Export-Integration ================= */

    @Test
    fun hardwareValidationExportMapsReport() {
        val report = FingerprintQualityAudit.auditOne(
            naveeFingerprint(),
            HardwareTestRegistry.of(record())
        )
        val export = HardwareValidationExport.from(report)
        assertEquals(naveeFingerprintId, export.fingerprintId)
        assertEquals("HARDWARE_CONFIRMED", export.maturity)
        assertTrue(export.maturityConfirmed)
        assertEquals(1, export.testCount)
        assertTrue(export.upgradePending)
        assertEquals("NOT_TESTED", export.declaredStatus)
    }

    @Test
    fun hardwareValidationNoneIsExplicit() {
        val none = HardwareValidationExport.none()
        assertNull(none.fingerprintId)
        assertEquals(0, none.testCount)
        assertFalse(none.maturityConfirmed)
    }

    @Test
    fun recognitionExportCarriesHardwareValidation() {
        val result = engine.recognize(
            RecognitionInput(
                advertisement = AdvertisementFeatures.of(
                    address = "AA:BB:CC:DD:EE:FF",
                    deviceName = "NAVEE GT3 Max",
                    rssi = -55,
                    serviceUuids = listOf(naveeService)
                ),
                gatt = GattFeatures.from(
                    listOf(
                        GattServiceRecord(
                            uuid = naveeService,
                            characteristics = listOf(
                                GattCharacteristicRecord(naveeNotify, setOf(GattProperty.NOTIFY)),
                                GattCharacteristicRecord(
                                    naveeWrite,
                                    setOf(GattProperty.WRITE, GattProperty.WRITE_NO_RESPONSE)
                                )
                            )
                        )
                    ),
                    discoveryStatus = GattDiscoveryStatus.COMPLETE
                )
            )
        )
        val report = FingerprintQualityAudit.auditOne(
            naveeFingerprint(),
            HardwareTestRegistry.of(record())
        )
        val export = RecognitionExport.from(
            result = result,
            profile = null,
            hardwareValidation = HardwareValidationExport.from(report)
        )
        assertNotNull(export.hardwareValidation)
        assertEquals("HARDWARE_CONFIRMED", export.hardwareValidation!!.maturity)
    }

    @Test
    fun hardwareTestReportContainsHardwareValidationJsonAndCsv() {
        val result = engine.recognize(
            RecognitionInput(
                advertisement = AdvertisementFeatures.of(
                    address = "AA:BB:CC:DD:EE:FF",
                    deviceName = "NAVEE GT3 Max",
                    rssi = -55,
                    serviceUuids = listOf(naveeService)
                )
            )
        )
        val report = FingerprintQualityAudit.auditOne(
            naveeFingerprint(),
            HardwareTestRegistry.of(record())
        )
        val export = RecognitionExport.from(
            result = result,
            profile = null,
            hardwareValidation = HardwareValidationExport.from(report)
        )
        val hardwareReport = HardwareTestReport(
            generatedAtEpochMs = 0L,
            appVersion = "test",
            deviceName = "NAVEE GT3 Max",
            address = "AA:BB:**:**:EE:FF",
            manufacturer = "NAVEE",
            detectedModel = "unbestätigt",
            confidence = "PROBABLE",
            supportStatus = "READ_ONLY",
            readOnly = true,
            protocol = "BLE",
            adapterId = "navee",
            recognition = export
        )
        val json = hardwareReport.toJson()
        val csv = hardwareReport.toCsv()
        assertTrue(json.contains("\"hardwareValidation\""))
        assertTrue(json.contains("\"maturity\": \"HARDWARE_CONFIRMED\""))
        assertTrue(csv.contains("hardware_validation"))
        assertTrue(csv.contains("maturity"))
    }

    /* ================= 10. Datenschutz: MAC maskiert ================= */

    @Test
    fun macAddressIsMaskedInExport() {
        val masked = RecognitionExport.maskAddress("AA:BB:CC:DD:EE:FF")
        assertEquals("AA:BB:**:**:EE:FF", masked)
        assertEquals("—", RecognitionExport.maskAddress("keine-mac"))
        assertEquals("—", RecognitionExport.maskAddress(null))
    }

    /* ================= 11. Reifegrad-Grenzen ================= */

    @Test
    fun maturityDisplayNamesAreStable() {
        assertEquals("dokumentiert", FingerprintMaturity.DOCUMENTED.displayName)
        assertEquals("getestet (nicht bestätigt)", FingerprintMaturity.TESTED.displayName)
        assertEquals("hardware-bestätigt", FingerprintMaturity.HARDWARE_CONFIRMED.displayName)
    }

    @Test
    fun deviationLabelIsReadable() {
        val d = com.syrak.scooterlab.recognition.HardwareDeviation(
            field = "GATT_CHAR",
            expected = "ffe2",
            observed = "fehlt",
            severity = DeviationSeverity.CRITICAL
        )
        assertTrue(d.label().contains("GATT_CHAR"))
        assertTrue(d.label().contains("Widerspruch"))
    }
}
