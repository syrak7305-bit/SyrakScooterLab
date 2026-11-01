package com.syrak.scooterlab

import com.syrak.scooterlab.diagnostics.GattCharacteristicRecord
import com.syrak.scooterlab.diagnostics.GattProperty
import com.syrak.scooterlab.diagnostics.GattServiceRecord
import com.syrak.scooterlab.diagnostics.HardwareTestReport
import com.syrak.scooterlab.recognition.AdvertisementFeatures
import com.syrak.scooterlab.recognition.AdvertisementFingerprint
import com.syrak.scooterlab.recognition.BrandFamily
import com.syrak.scooterlab.recognition.BuiltInModelFingerprints
import com.syrak.scooterlab.recognition.EvidenceGrade
import com.syrak.scooterlab.recognition.FingerprintEvidence
import com.syrak.scooterlab.recognition.GattDiscoveryStatus
import com.syrak.scooterlab.recognition.GattFeatures
import com.syrak.scooterlab.recognition.GattFingerprint
import com.syrak.scooterlab.recognition.HardwareTestStatus
import com.syrak.scooterlab.recognition.ModelFingerprint
import com.syrak.scooterlab.recognition.ModelFingerprintDatabase
import com.syrak.scooterlab.recognition.RecognitionConfidence
import com.syrak.scooterlab.recognition.RecognitionEngine
import com.syrak.scooterlab.recognition.RecognitionExport
import com.syrak.scooterlab.recognition.RecognitionInput
import com.syrak.scooterlab.recognition.RecognitionReasonCode
import com.syrak.scooterlab.recognition.RecognitionSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 17.1 — Tests der Recognition Engine.
 *
 * Deckt alle im Auftrag (§12) geforderten Szenarien ab. Reine Logik, keine
 * Android-Abhaengigkeit, keine echten Geraete.
 */
class Phase171RecognitionTest {

    private val engine = RecognitionEngine()

    /* ----------------------------- Helfer ----------------------------- */

    private fun gatt(
        serviceUuid: String,
        characteristics: List<Pair<String, Set<GattProperty>>> = emptyList(),
        status: GattDiscoveryStatus = GattDiscoveryStatus.COMPLETE,
        errors: List<String> = emptyList()
    ): GattFeatures = GattFeatures.from(
        listOf(
            GattServiceRecord(
                uuid = serviceUuid,
                characteristics = characteristics.map { (uuid, props) ->
                    GattCharacteristicRecord(uuid = uuid, properties = props)
                }
            )
        ),
        discoveryStatus = status,
        errors = errors
    )

    private fun adv(
        name: String?,
        services: List<String> = emptyList(),
        manufacturerIds: List<Int> = emptyList(),
        serviceData: List<String> = emptyList(),
        rssi: Int? = -60
    ): AdvertisementFeatures = AdvertisementFeatures.of(
        address = "AA:BB:CC:DD:EE:FF",
        deviceName = name,
        rssi = rssi,
        serviceUuids = services,
        manufacturerIds = manufacturerIds,
        serviceDataUuids = serviceData
    )

    private val naveeService = "0000ffe0-0000-1000-8000-00805f9b34fb"
    private val naveeNotify = "0000ffe1-0000-1000-8000-00805f9b34fb"
    private val naveeWrite = "0000ffe2-0000-1000-8000-00805f9b34fb"
    private val ninebotService = "0000e003-0000-1000-8000-00805f9b34fb"
    private val ninebotChar = "0000fff1-0000-1000-8000-00805f9b34fb"
    private val genericBattery = "0000180f-0000-1000-8000-00805f9b34fb"

    /* ------------------- 1. Vollstaendiges Advertisement ------------------- */

    @Test
    fun completeAdvertisementWithMatchingGattIsProbable() {
        val result = engine.recognize(
            RecognitionInput(
                advertisement = adv("NAVEE GT3 Max", listOf(naveeService)),
                gatt = gatt(
                    naveeService,
                    listOf(
                        naveeNotify to setOf(GattProperty.NOTIFY),
                        naveeWrite to setOf(GattProperty.WRITE)
                    )
                )
            )
        )
        assertEquals(RecognitionConfidence.PROBABLE, result.confidence)
        assertEquals(ScooterManufacturer.NAVEE, result.detectedBrand)
        assertEquals(BrandFamily.NAVEE, result.detectedBrandFamily)
        assertNotNull(result.probableModel)
        assertNull(result.confirmedModel)
        assertTrue(result.hasReason(RecognitionReasonCode.PROBABLE_PROFILE_MATCH))
    }

    /* ------------------- 2. Unvollstaendiges Advertisement ------------------- */

    @Test
    fun incompleteAdvertisementNameOnlyIsFamilyOnly() {
        val result = engine.recognize(RecognitionInput(advertisement = adv("NAVEE GT3 Max")))
        assertEquals(RecognitionConfidence.FAMILY_ONLY, result.confidence)
        assertEquals(BrandFamily.NAVEE, result.detectedBrandFamily)
        assertNull(result.confirmedModel)
        assertTrue(result.hasReason(RecognitionReasonCode.NAME_ONLY_NO_PAYLOAD))
    }

    @Test
    fun incompleteGattDiscoveryIsReportedButStillUsable() {
        val result = engine.recognize(
            RecognitionInput(
                advertisement = adv("NAVEE GT3 Max", listOf(naveeService)),
                gatt = gatt(naveeService, listOf(naveeNotify to setOf(GattProperty.NOTIFY)), GattDiscoveryStatus.INCOMPLETE)
            )
        )
        assertTrue(result.hasReason(RecognitionReasonCode.GATT_INCOMPLETE))
        assertNotEquals(RecognitionConfidence.UNKNOWN, result.confidence)
    }

    /* ------------------- 3. Fehlende Manufacturer Data ------------------- */

    @Test
    fun missingManufacturerDataIsReportedNotPenalised() {
        val result = engine.recognize(RecognitionInput(advertisement = adv("NAVEE GT3 Max", listOf(naveeService))))
        assertTrue(result.hasReason(RecognitionReasonCode.MISSING_MANUFACTURER_DATA))
        // Name + Service sind zwei unabhaengige Merkmale => PROBABLE, aber nie CONFIRMED.
        assertEquals(RecognitionConfidence.PROBABLE, result.confidence)
        assertNull(result.confirmedModel)
    }

    /* ------------------- 4. Unbekannte Manufacturer IDs ------------------- */

    @Test
    fun unknownManufacturerIdStaysUnknown() {
        val result = engine.recognize(
            RecognitionInput(advertisement = adv(null, manufacturerIds = listOf(0x1234)))
        )
        assertTrue(result.hasReason(RecognitionReasonCode.UNKNOWN_MANUFACTURER_ID))
        assertEquals(ScooterManufacturer.UNKNOWN, result.detectedBrand)
        assertEquals(RecognitionConfidence.UNKNOWN, result.confidence)
    }

    /* ------------------- 5. Unbekannte Service UUIDs ------------------- */

    @Test
    fun unknownServiceUuidIsReported() {
        val result = engine.recognize(
            RecognitionInput(advertisement = adv(null, listOf("12345678-1234-1234-1234-123456789abc")))
        )
        assertTrue(result.hasReason(RecognitionReasonCode.UNKNOWN_SERVICE_UUID))
        assertEquals(RecognitionConfidence.UNKNOWN, result.confidence)
    }

    /* ------------------- 6. Widerspruechliche Merkmale ------------------- */

    @Test
    fun contradictoryBrandAndServiceIsSurfacedAndDemoted() {
        // Name sagt NAVEE, Service sagt Ninebot.
        val result = engine.recognize(
            RecognitionInput(advertisement = adv("NAVEE GT3 Max", listOf(ninebotService)))
        )
        assertTrue(result.hasReason(RecognitionReasonCode.CONTRADICTION_BRAND))
        assertEquals(ScooterManufacturer.UNKNOWN, result.detectedBrand)
        assertEquals(BrandFamily.UNKNOWN, result.detectedBrandFamily)
        assertEquals(RecognitionConfidence.UNKNOWN, result.confidence)
    }

    /* ------------------- 7. Generische UUIDs ------------------- */

    @Test
    fun genericUuidsNeverProveAModel() {
        val result = engine.recognize(
            RecognitionInput(advertisement = adv(null, listOf(genericBattery)))
        )
        assertTrue(result.hasReason(RecognitionReasonCode.SERVICE_UUID_GENERIC_IGNORED))
        assertEquals(RecognitionConfidence.UNKNOWN, result.confidence)
    }

    /* ------------------- 8. Name ohne weitere Evidenz ------------------- */

    @Test
    fun deviceNameAloneNeverConfirmsModel() {
        val result = engine.recognize(RecognitionInput(advertisement = adv("Ninebot Max G30")))
        assertEquals(RecognitionConfidence.FAMILY_ONLY, result.confidence)
        assertEquals(BrandFamily.SEGWAY_NINEBOT, result.detectedBrandFamily)
        assertNull(result.confirmedModel)
        assertNull(result.probableModel)
    }

    /* ------------------- 9. RSSI ohne weitere Evidenz ------------------- */

    @Test
    fun rssiAloneNeverDeterminesModel() {
        val result = engine.recognize(RecognitionInput(advertisement = adv(null, rssi = -42)))
        assertTrue(result.hasReason(RecognitionReasonCode.RSSI_IGNORED))
        assertEquals(RecognitionConfidence.UNKNOWN, result.confidence)
    }

    /* ------------------- 10. Mehrere passende Merkmale ------------------- */

    @Test
    fun multipleMatchingFeaturesRaiseConfidenceToProbable() {
        val result = engine.recognize(
            RecognitionInput(
                advertisement = adv("NAVEE GT3 Max", listOf(naveeService)),
                gatt = gatt(
                    naveeService,
                    listOf(
                        naveeNotify to setOf(GattProperty.NOTIFY),
                        naveeWrite to setOf(GattProperty.WRITE)
                    )
                )
            )
        )
        assertTrue(result.matchedFeatures.size >= 3)
        assertEquals(RecognitionConfidence.PROBABLE, result.confidence)
    }

    /* ------------------- 11. Segway/Ninebot getrennt ------------------- */

    @Test
    fun segwayAndNinebotRemainDistinguishable() {
        val segway = engine.recognize(RecognitionInput(advertisement = adv("Segway F3 Pro")))
        val ninebot = engine.recognize(RecognitionInput(advertisement = adv("Ninebot Max G30")))

        assertEquals(ScooterManufacturer.SEGWAY, segway.detectedBrand)
        assertEquals(ScooterManufacturer.NINEBOT, ninebot.detectedBrand)
        assertEquals(BrandFamily.SEGWAY_NINEBOT, segway.detectedBrandFamily)
        assertEquals(BrandFamily.SEGWAY_NINEBOT, ninebot.detectedBrandFamily)
        assertNotEquals(segway.detectedBrand, ninebot.detectedBrand)
    }

    @Test
    fun ambiguousSegwayNinebotFamilyKeepsBrandUnknown() {
        // Name nennt Segway, Service deutet auf Ninebot: gleiche Familie, aber
        // die konkrete Marke ist nicht eindeutig.
        val result = engine.recognize(
            RecognitionInput(advertisement = adv("Segway Max", listOf(ninebotService)))
        )
        assertEquals(BrandFamily.SEGWAY_NINEBOT, result.detectedBrandFamily)
        assertEquals(ScooterManufacturer.UNKNOWN, result.detectedBrand)
        assertTrue(result.hasReason(RecognitionReasonCode.BRAND_FAMILY_AMBIGUOUS))
    }

    /* ------------------- 12. NAVEE/Ninebot getrennt ------------------- */

    @Test
    fun naveeIsNotDetectedAsNinebot() {
        val result = engine.recognize(
            RecognitionInput(
                advertisement = adv("NAVEE GT3 Max", listOf(naveeService)),
                gatt = gatt(naveeService, listOf(naveeNotify to setOf(GattProperty.NOTIFY)))
            )
        )
        assertEquals(ScooterManufacturer.NAVEE, result.detectedBrand)
        assertEquals(BrandFamily.NAVEE, result.detectedBrandFamily)
        assertNotEquals(BrandFamily.SEGWAY_NINEBOT, result.detectedBrandFamily)
    }

    /* ------------------- 13. Xiaomi und andere Marken ------------------- */

    @Test
    fun xiaomiIsNeverConfirmedByGenericFeatures() {
        val result = engine.recognize(
            RecognitionInput(advertisement = adv("Xiaomi M365", listOf(genericBattery)))
        )
        assertEquals(ScooterManufacturer.XIAOMI, result.detectedBrand)
        assertEquals(RecognitionConfidence.FAMILY_ONLY, result.confidence)
        assertNull(result.confirmedModel)
    }

    @Test
    fun xiaomiLegacyServiceIsUnknownEvidenceOnly() {
        val result = engine.recognize(
            RecognitionInput(advertisement = adv(null, listOf("0000fe95-0000-1000-8000-00805f9b34fb")))
        )
        // UNKNOWN-Evidenz zaehlt nicht als Modellbeweis.
        assertEquals(RecognitionConfidence.UNKNOWN, result.confidence)
    }

    /* ------------------- 14. Unbekannte Modelle ------------------- */

    @Test
    fun unknownModelIsNotAssignedToKnownBrand() {
        val result = engine.recognize(
            RecognitionInput(advertisement = adv("Generic Scooter 3000", listOf("12345678-1234-1234-1234-123456789abc")))
        )
        assertEquals(ScooterManufacturer.UNKNOWN, result.detectedBrand)
        assertEquals(RecognitionConfidence.UNKNOWN, result.confidence)
    }

    /* ------------------- 15. Nutzerprofil vs. Hardware ------------------- */

    @Test
    fun engineResultIsIndependentOfUserProfile() {
        val input = RecognitionInput(advertisement = adv("NAVEE GT3 Max", listOf(naveeService)))
        val first = engine.recognize(input)
        val second = engine.recognize(input)
        // Kein Nutzerprofil im Input => identisches, deterministisches Ergebnis.
        assertEquals(first, second)
        assertEquals(RecognitionConfidence.PROBABLE, first.confidence)
        assertNull(first.confirmedModel)
    }

    @Test
    fun exportKeepsUserProfileAndHardwareSeparate() {
        val result = engine.recognize(
            RecognitionInput(
                advertisement = adv("NAVEE GT3 Max", listOf(naveeService)),
                gatt = gatt(naveeService, listOf(naveeNotify to setOf(GattProperty.NOTIFY)))
            )
        )
        val profile = RiderScooterProfile(
            brand = ScooterManufacturer.NAVEE,
            model = ScooterModel.NAVEE_GT3_MAX,
            modelCode = "GT3M",
            serialNumber = "SN-SECRET-123"
        )
        val export = RecognitionExport.from(result, profile)
        assertEquals("Navee", export.userProfileBrand)
        assertEquals("Navee GT3 Max", export.userProfileModel)
        assertEquals("Navee", export.detectedBrand)
        assertEquals("PROBABLE", export.confidence)
        assertFalse(export.reasons.isEmpty())
    }

    /* ------------------- 16. Konfidenzregeln ------------------- */

    @Test
    fun noBuiltInProfileIsHardwareConfirmed() {
        assertTrue(BuiltInModelFingerprints.all().all { !it.isHardwareConfirmed })
        assertTrue(BuiltInModelFingerprints.all().all { it.hardwareTestStatus == HardwareTestStatus.NOT_TESTED })
    }

    @Test
    fun confirmedRequiresHardwareConfirmedProfileAndConfirmedEvidence() {
        val confirmedProfile = ModelFingerprint(
            id = "synthetic-confirmed",
            manufacturer = ScooterManufacturer.NAVEE,
            brandFamily = BrandFamily.NAVEE,
            modelName = "Synthetic Confirmed",
            modelFamily = "NAVEE",
            advertisement = AdvertisementFingerprint(
                serviceUuids = mapOf(naveeService to FingerprintEvidence(EvidenceGrade.CONFIRMED, "test"))
            ),
            gatt = GattFingerprint(
                serviceUuids = mapOf(naveeService to FingerprintEvidence(EvidenceGrade.CONFIRMED, "test")),
                characteristicUuids = mapOf(naveeNotify to FingerprintEvidence(EvidenceGrade.CONFIRMED, "test"))
            ),
            source = "test",
            hardwareTestStatus = HardwareTestStatus.CONFIRMED_ON_HARDWARE,
            confirmationDate = "2026-11-02"
        )
        val db = object : ModelFingerprintDatabase {
            override fun all() = listOf(confirmedProfile)
            override fun byBrand(manufacturer: ScooterManufacturer) = listOf(confirmedProfile)
            override fun byId(id: String) = confirmedProfile.takeIf { it.id == id }
        }
        val confirmedEngine = RecognitionEngine(db)
        val result = confirmedEngine.recognize(
            RecognitionInput(
                advertisement = adv("NAVEE GT3 Max", listOf(naveeService)),
                gatt = gatt(naveeService, listOf(naveeNotify to setOf(GattProperty.NOTIFY)))
            )
        )
        assertEquals(RecognitionConfidence.CONFIRMED, result.confidence)
        assertEquals("Synthetic Confirmed", result.confirmedModel)
    }

    @Test
    fun contradictionsCapConfidenceAtFamilyOnly() {
        val result = engine.recognize(
            RecognitionInput(
                advertisement = adv("NAVEE GT3 Max", listOf(naveeService, ninebotService)),
                gatt = gatt(naveeService, listOf(naveeNotify to setOf(GattProperty.NOTIFY)))
            )
        )
        assertTrue(
            result.confidence == RecognitionConfidence.UNKNOWN ||
                result.confidence == RecognitionConfidence.FAMILY_ONLY
        )
        assertNull(result.confirmedModel)
        assertNull(result.probableModel)
    }

    /* ------------------- 17. GATT ohne vollstaendige Properties ------------------- */

    @Test
    fun gattWithoutPropertiesStillProducesStructuredResult() {
        val result = engine.recognize(
            RecognitionInput(
                advertisement = adv("NAVEE GT3 Max", listOf(naveeService)),
                gatt = gatt(naveeService, listOf(naveeNotify to emptySet()))
            )
        )
        assertTrue(result.gatt != null)
        assertEquals(1, result.gatt!!.characteristicCount)
        assertFalse(result.gatt!!.services.first().characteristics.first().notifiable)
    }

    /* ------------------- 18. Verbindungsabbruch waehrend Erkennung ------------------- */

    @Test
    fun failedGattDiscoveryIsRecordedAsError() {
        val result = engine.recognize(
            RecognitionInput(
                advertisement = adv("NAVEE GT3 Max", listOf(naveeService)),
                gatt = GattFeatures.failed("GATT_ERROR_133")
            )
        )
        assertTrue(result.hasReason(RecognitionReasonCode.GATT_INCOMPLETE))
        assertTrue(result.unknownFeatures.any { it.contains("GATT_ERROR_133") })
    }

    /* ------------------- 19. Reconnect mit anderem Roller ------------------- */

    @Test
    fun sessionResetsOnDeviceChange() {
        val session = RecognitionSession()
        val first = engine.recognize(RecognitionInput(advertisement = adv("NAVEE GT3 Max")))
        session.observe("AA:BB:CC:DD:EE:01", first)
        assertTrue(session.hasResultFor("AA:BB:CC:DD:EE:01"))

        val second = engine.recognize(RecognitionInput(advertisement = adv("Ninebot Max G30")))
        session.observe("AA:BB:CC:DD:EE:02", second)

        assertFalse(session.hasResultFor("AA:BB:CC:DD:EE:01"))
        assertTrue(session.hasResultFor("AA:BB:CC:DD:EE:02"))
        assertEquals("AA:BB:CC:DD:EE:02", session.currentAddress)
    }

    /* ------------------- 20. Diagnose-Export ------------------- */

    @Test
    fun reportJsonContainsSeparatedRecognitionBlock() {
        val result = engine.recognize(
            RecognitionInput(
                advertisement = adv("NAVEE GT3 Max", listOf(naveeService)),
                gatt = gatt(naveeService, listOf(naveeNotify to setOf(GattProperty.NOTIFY)))
            )
        )
        val profile = RiderScooterProfile(
            brand = ScooterManufacturer.NAVEE,
            model = ScooterModel.NAVEE_GT3_MAX,
            serialNumber = "SN-SECRET-123"
        )
        val report = HardwareTestReport(
            generatedAtEpochMs = 1_700_000_000_000,
            appVersion = "1.0",
            deviceName = "NAVEE GT3 Max",
            address = "AA:BB:CC:DD:EE:FF",
            manufacturer = "NAVEE",
            detectedModel = "UNKNOWN",
            confidence = "PROBABLE",
            supportStatus = "UNKNOWN",
            readOnly = true,
            protocol = "NAVEE_BRIGHTWAY",
            adapterId = "navee-gt3max",
            recognition = RecognitionExport.from(result, profile)
        )
        val json = report.toJson()
        assertTrue(json.contains("\"schemaVersion\": 1"))
        assertTrue(json.contains("\"recognition\""))
        assertTrue(json.contains("\"userProfile\""))
        assertTrue(json.contains("\"hardwareDetection\""))
        assertTrue(json.contains("\"reasoning\""))
        assertTrue(json.contains("PROBABLE"))
        // Datenschutz: Seriennummer nicht exportiert, MAC maskiert.
        assertFalse(json.contains("SN-SECRET-123"))
        assertTrue(json.contains("AA:BB:**:**:EE:FF"))
    }

    @Test
    fun reportCsvContainsRecognitionRows() {
        val result = engine.recognize(RecognitionInput(advertisement = adv("NAVEE GT3 Max")))
        val report = HardwareTestReport(
            generatedAtEpochMs = 1,
            appVersion = "1.0",
            deviceName = "NAVEE GT3 Max",
            address = "AA:BB:CC:DD:EE:FF",
            manufacturer = "NAVEE",
            detectedModel = "UNKNOWN",
            confidence = "FAMILY_ONLY",
            supportStatus = "UNKNOWN",
            readOnly = true,
            protocol = "NAVEE_BRIGHTWAY",
            adapterId = "navee-gt3max",
            recognition = RecognitionExport.from(result, null)
        )
        val csv = report.toCsv()
        assertTrue(csv.contains("recognition_user,brand"))
        assertTrue(csv.contains("recognition_hw,confidence,FAMILY_ONLY"))
        assertTrue(csv.contains("recognition_reason,reasons"))
    }

    /* ------------------- 21. Regression bestehender BLE-Funktionen ------------------- */

    @Test
    fun scanObservationStillBuildsAdvertisementFeatures() {
        val observation = ScanObservation(
            address = "AA:BB:CC:DD:EE:FF",
            gattDeviceName = null,
            advertisedName = "NAVEE GT3 Max",
            serviceUuids = listOf("ffe0"),
            manufacturerIds = listOf(0x0059),
            serviceDataUuids = emptyList(),
            rssi = -55,
            timestampMs = 1
        )
        val features = AdvertisementFeatures.from(observation)
        assertEquals("NAVEE GT3 Max", features.deviceName)
        assertEquals(naveeService, features.serviceUuids.first())
        assertTrue(features.hasManufacturerData)
    }

    /* ------------------- 22. Determinismus und keine Prozente ------------------- */

    @Test
    fun recognitionIsDeterministic() {
        val input = RecognitionInput(
            advertisement = adv("NAVEE GT3 Max", listOf(naveeService)),
            gatt = gatt(naveeService, listOf(naveeNotify to setOf(GattProperty.NOTIFY)))
        )
        val a = engine.recognize(input)
        val b = engine.recognize(input)
        assertEquals(a, b)
        assertEquals(a.reasonCodes(), b.reasonCodes())
    }

    @Test
    fun summaryContainsNoArtificialPercentage() {
        val result = engine.recognize(RecognitionInput(advertisement = adv("NAVEE GT3 Max")))
        assertFalse(result.summarySentence().contains("%"))
    }

    @Test
    fun noDeviceProducesUnknownWithReason() {
        val result = engine.recognize(RecognitionInput())
        assertFalse(result.deviceFound)
        assertEquals(RecognitionConfidence.UNKNOWN, result.confidence)
        assertTrue(result.hasReason(RecognitionReasonCode.NO_DEVICE))
    }

    @Test
    fun familyOnlySummaryMatchesRequiredWording() {
        val result = engine.recognize(RecognitionInput(advertisement = adv("NAVEE GT3 Max")))
        assertEquals(RecognitionConfidence.FAMILY_ONLY, result.confidence)
        assertTrue(result.summarySentence().contains("NAVEE-Familienhinweis"))
        assertTrue(result.summarySentence().contains("noch nicht bestätigt"))
    }
}
