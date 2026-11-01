package com.syrak.scooterlab

import com.syrak.scooterlab.diagnostics.GattCharacteristicRecord
import com.syrak.scooterlab.diagnostics.GattProperty
import com.syrak.scooterlab.diagnostics.GattServiceRecord
import com.syrak.scooterlab.diagnostics.HardwareTestReport
import com.syrak.scooterlab.recognition.AdvertisementFeatures
import com.syrak.scooterlab.recognition.GattDiscoveryStatus
import com.syrak.scooterlab.recognition.GattFeatures
import com.syrak.scooterlab.recognition.RecognitionConfidence
import com.syrak.scooterlab.recognition.RecognitionEngine
import com.syrak.scooterlab.recognition.RecognitionExport
import com.syrak.scooterlab.recognition.RecognitionInput
import com.syrak.scooterlab.recognition.RecognitionReasonCode
import com.syrak.scooterlab.recognition.RecognitionSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 17.2 — Tests fuer Live-GATT-Erkennung und Geraete-/Session-Trennung.
 *
 * Deckt die 20 im Auftrag (§9) geforderten Szenarien ab. Reine Logik, keine
 * Android-Abhaengigkeit, keine echten Geraete, keine erfundenen UUIDs.
 */
class Phase172LiveGattRecognitionTest {

    private val engine = RecognitionEngine()

    /* ----------------------------- Helfer ----------------------------- */

    private val naveeService = "0000ffe0-0000-1000-8000-00805f9b34fb"
    private val naveeNotify = "0000ffe1-0000-1000-8000-00805f9b34fb"
    private val naveeWrite = "0000ffe2-0000-1000-8000-00805f9b34fb"
    private val ninebotService = "0000e003-0000-1000-8000-00805f9b34fb"
    private val ninebotChar = "0000fff1-0000-1000-8000-00805f9b34fb"
    private val genericBattery = "0000180f-0000-1000-8000-00805f9b34fb"
    private val genericDeviceInfo = "0000180a-0000-1000-8000-00805f9b34fb"

    private fun adv(
        name: String?,
        services: List<String> = emptyList(),
        manufacturerIds: List<Int> = emptyList(),
        serviceData: List<String> = emptyList(),
        rssi: Int? = -60,
        address: String = "AA:BB:CC:DD:EE:FF"
    ): AdvertisementFeatures = AdvertisementFeatures.of(
        address = address,
        deviceName = name,
        rssi = rssi,
        serviceUuids = services,
        manufacturerIds = manufacturerIds,
        serviceDataUuids = serviceData
    )

    private fun gatt(
        vararg services: Pair<String, List<Pair<String, Set<GattProperty>>>>,
        status: GattDiscoveryStatus = GattDiscoveryStatus.COMPLETE,
        errors: List<String> = emptyList()
    ): GattFeatures = GattFeatures.from(
        services.map { (serviceUuid, chars) ->
            GattServiceRecord(
                uuid = serviceUuid,
                characteristics = chars.map { (uuid, props) ->
                    GattCharacteristicRecord(uuid = uuid, properties = props)
                }
            )
        },
        discoveryStatus = status,
        errors = errors
    )

    private fun naveeGatt(
        status: GattDiscoveryStatus = GattDiscoveryStatus.COMPLETE,
        errors: List<String> = emptyList()
    ): GattFeatures = gatt(
        naveeService to listOf(
            naveeNotify to setOf(GattProperty.NOTIFY),
            naveeWrite to setOf(GattProperty.WRITE, GattProperty.WRITE_NO_RESPONSE)
        ),
        status = status,
        errors = errors
    )

    private fun snapshot(
        address: String = "AA:BB:CC:DD:EE:FF",
        generation: Int = 7,
        discoveryGeneration: Int = 7,
        status: GattDiscoveryStatus = GattDiscoveryStatus.COMPLETE,
        services: List<GattServiceRecord> = listOf(
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
        error: String? = null
    ): GattLiveSnapshot = GattLiveSnapshot(
        address = address,
        generation = generation,
        services = services,
        discoveryStatus = status,
        discoveryError = error,
        discoveryGeneration = discoveryGeneration
    )

    private fun gattKinds(
        result: com.syrak.scooterlab.recognition.RecognitionResult
    ): List<String> = result.matchedFeatures
        .filter { it.kind.startsWith("GATT_") && it.grade.countsTowardModel }
        .map { it.kind }

    /* ============ 1. Advertisement-Erkennung ohne Verbindung ============ */

    @Test
    fun advertisementRecognitionWithoutConnection() {
        val result = engine.recognize(RecognitionInput(advertisement = adv("NAVEE GT3 Max")))
        assertTrue(result.deviceFound)
        assertNull(result.gatt)
        assertTrue(gattKinds(result).isEmpty())
        assertTrue(
            result.confidence == RecognitionConfidence.FAMILY_ONLY ||
                result.confidence == RecognitionConfidence.PROBABLE
        )
        assertNull(result.confirmedModel)
        assertFalse(result.hasReason(RecognitionReasonCode.GATT_EVIDENCE_ADDED))
    }

    /* ============ 2. GATT-Erkennung nach erfolgreicher Discovery ============ */

    @Test
    fun gattRecognitionAfterSuccessfulDiscovery() {
        val advOnly = engine.recognize(RecognitionInput(advertisement = adv("NAVEE GT3 Max")))
        val withGatt = engine.recognize(
            RecognitionInput(advertisement = adv("NAVEE GT3 Max"), gatt = naveeGatt())
        )
        assertEquals(GattDiscoveryStatus.COMPLETE, withGatt.gatt?.discoveryStatus)
        assertTrue(gattKinds(withGatt).isNotEmpty())
        assertTrue(
            "withGatt=${withGatt.confidence} advOnly=${advOnly.confidence} " +
                "reasons=${withGatt.reasonCodes()} kinds=${gattKinds(withGatt)}",
            withGatt.confidence.rank >= advOnly.confidence.rank
        )
        assertTrue(withGatt.hasReason(RecognitionReasonCode.GATT_EVIDENCE_ADDED))
        assertNotNull(withGatt.detectedModelFamily)
    }

    /* ============ 3. GATT-Discovery fehlgeschlagen ============ */

    @Test
    fun gattDiscoveryFailed() {
        val advOnly = engine.recognize(RecognitionInput(advertisement = adv("NAVEE GT3 Max")))
        val failed = engine.recognize(
            RecognitionInput(
                advertisement = adv("NAVEE GT3 Max"),
                gatt = GattFeatures.failed("GATT_ERROR_133")
            )
        )
        assertEquals(GattDiscoveryStatus.FAILED, failed.gatt?.discoveryStatus)
        assertTrue(gattKinds(failed).isEmpty())
        assertEquals(advOnly.confidence, failed.confidence)
        assertTrue(
            "reasons=${failed.reasonCodes()} unknown=${failed.unknownFeatures}",
            failed.hasReason(RecognitionReasonCode.GATT_NO_EVIDENCE)
        )
        assertTrue(failed.unknownFeatures.any { it.contains("GATT_ERROR_133") })
    }

    /* ============ 4. GATT-Discovery noch nicht gestartet ============ */

    @Test
    fun gattDiscoveryNotAttempted() {
        val advOnly = engine.recognize(RecognitionInput(advertisement = adv("NAVEE GT3 Max")))
        val notAttempted = engine.recognize(
            RecognitionInput(
                advertisement = adv("NAVEE GT3 Max"),
                gatt = GattFeatures.notAttempted()
            )
        )
        assertEquals(GattDiscoveryStatus.NOT_ATTEMPTED, notAttempted.gatt?.discoveryStatus)
        assertTrue(gattKinds(notAttempted).isEmpty())
        assertEquals(advOnly.confidence, notAttempted.confidence)
    }

    /* ============ 5. Leerer GATT-Snapshot ============ */

    @Test
    fun emptyGattSnapshotIsNotSuccessfulDiscovery() {
        val emptyComplete = GattFeatures.from(
            records = emptyList(),
            discoveryStatus = GattDiscoveryStatus.COMPLETE
        )
        assertFalse(emptyComplete.hasAnyData)
        assertTrue(emptyComplete.serviceUuids.isEmpty())

        val advOnly = engine.recognize(RecognitionInput(advertisement = adv("NAVEE GT3 Max")))
        val withEmpty = engine.recognize(
            RecognitionInput(advertisement = adv("NAVEE GT3 Max"), gatt = emptyComplete)
        )
        assertEquals(advOnly.confidence, withEmpty.confidence)
        assertTrue(gattKinds(withEmpty).isEmpty())
    }

    /* ============ 6. Unvollstaendige GATT-Daten ============ */

    @Test
    fun incompleteGattDataYieldsNoPositiveEvidence() {
        val advOnly = engine.recognize(RecognitionInput(advertisement = adv("NAVEE GT3 Max")))
        val incomplete = engine.recognize(
            RecognitionInput(
                advertisement = adv("NAVEE GT3 Max"),
                gatt = naveeGatt(status = GattDiscoveryStatus.INCOMPLETE)
            )
        )
        assertEquals(GattDiscoveryStatus.INCOMPLETE, incomplete.gatt?.discoveryStatus)
        assertTrue(gattKinds(incomplete).isEmpty())
        assertEquals(advOnly.confidence, incomplete.confidence)
        assertTrue(incomplete.hasReason(RecognitionReasonCode.GATT_NO_EVIDENCE))
    }

    /* ============ 7. Advertisement und GATT stimmen ueberein ============ */

    @Test
    fun advertisementAndGattAgree() {
        val result = engine.recognize(
            RecognitionInput(
                advertisement = adv("NAVEE GT3 Max", services = listOf(naveeService)),
                gatt = naveeGatt()
            )
        )
        assertTrue(result.contradictoryFeatures.isEmpty())
        assertTrue(gattKinds(result).isNotEmpty())
        assertTrue(
            "conf=${result.confidence} family=${result.detectedBrandFamily} " +
                "brand=${result.detectedBrand} reasons=${result.reasonCodes()}",
            result.confidence.rank >= RecognitionConfidence.FAMILY_ONLY.rank
        )
    }

    /* ============ 8. Advertisement und GATT widersprechen sich ============ */

    @Test
    fun advertisementAndGattContradict() {
        val result = engine.recognize(
            RecognitionInput(
                advertisement = adv("NAVEE GT3 Max", services = listOf(naveeService)),
                gatt = gatt(
                    ninebotService to listOf(
                        ninebotChar to setOf(GattProperty.READ, GattProperty.WRITE)
                    )
                )
            )
        )
        assertTrue(
            "Widerspruch muss sichtbar sein",
            result.contradictoryFeatures.isNotEmpty() ||
                result.confidence == RecognitionConfidence.UNKNOWN
        )
        assertNull(result.confirmedModel)
    }

    /* ============ 9. Zwei gleichzeitig sichtbare Scooter ============ */

    @Test
    fun twoSimultaneouslyVisibleScootersStaySeparate() {
        val session = RecognitionSession()
        val a = engine.recognize(
            RecognitionInput(advertisement = adv("NAVEE GT3 Max", address = "AA:AA:AA:AA:AA:AA"))
        )
        val b = engine.recognize(
            RecognitionInput(advertisement = adv("Ninebot Max", address = "BB:BB:BB:BB:BB:BB"))
        )
        session.observeScan("AA:AA:AA:AA:AA:AA", a)
        session.observeScan("BB:BB:BB:BB:BB:BB", b)

        assertEquals(2, session.scannedAddresses().size)
        assertEquals(a, session.scanResultFor("AA:AA:AA:AA:AA:AA"))
        assertEquals(b, session.scanResultFor("BB:BB:BB:BB:BB:BB"))
        assertFalse(
            session.scanResultFor("AA:AA:AA:AA:AA:AA") ==
                session.scanResultFor("BB:BB:BB:BB:BB:BB")
        )
    }

    /* ============ 10. Fremdes Scan-Ergebnis ueberschreibt aktive Verbindung nicht ============ */

    @Test
    fun foreignScanResultDoesNotOverwriteActiveConnection() {
        val session = RecognitionSession()
        val active = engine.recognize(
            RecognitionInput(
                advertisement = adv("NAVEE GT3 Max", address = "AA:AA:AA:AA:AA:AA"),
                gatt = naveeGatt()
            )
        )
        assertTrue(session.bindActive("AA:AA:AA:AA:AA:AA", active))

        val foreign = engine.recognize(
            RecognitionInput(advertisement = adv("Ninebot Max", address = "CC:CC:CC:CC:CC:CC"))
        )
        session.observeScan("CC:CC:CC:CC:CC:CC", foreign)

        assertEquals(active, session.activeResult)
        assertEquals("AA:AA:AA:AA:AA:AA", session.activeAddress)
        assertEquals(active, session.displayResult())
        assertEquals(foreign, session.scanResultFor("CC:CC:CC:CC:CC:CC"))
    }

    /* ============ 11. GATT-Daten werden der richtigen Adresse zugeordnet ============ */

    @Test
    fun gattDataBoundOnlyToMatchingAddress() {
        val session = RecognitionSession()
        val resultForB = engine.recognize(
            RecognitionInput(
                advertisement = adv("NAVEE GT3 Max", address = "BB:BB:BB:BB:BB:BB"),
                gatt = naveeGatt()
            )
        )
        assertFalse(session.bindActive("AA:AA:AA:AA:AA:AA", resultForB))
        assertNull(session.activeResult)
        assertNull(session.activeAddress)

        assertTrue(session.bindActive("BB:BB:BB:BB:BB:BB", resultForB))
        assertTrue(session.hasActiveResultFor("BB:BB:BB:BB:BB:BB"))
        assertFalse(session.hasActiveResultFor("AA:AA:AA:AA:AA:AA"))
        assertNull(session.activeResultFor("AA:AA:AA:AA:AA:AA"))
    }

    /* ============ 12. Disconnect setzt aktive Erkennung zurueck ============ */

    @Test
    fun disconnectResetsActiveRecognition() {
        val session = RecognitionSession()
        val active = engine.recognize(
            RecognitionInput(
                advertisement = adv("NAVEE GT3 Max", address = "AA:AA:AA:AA:AA:AA"),
                gatt = naveeGatt()
            )
        )
        session.observeScan("AA:AA:AA:AA:AA:AA", active)
        session.bindActive("AA:AA:AA:AA:AA:AA", active)

        session.archiveActive()
        assertNull(session.activeResult)
        assertNull(session.activeAddress)
        // Getrennte Historie bleibt erhalten, Scan-Historie ebenfalls.
        assertEquals(active, session.lastActiveResult)
        assertEquals("AA:AA:AA:AA:AA:AA", session.lastActiveAddress)
        assertEquals(active, session.scanResultFor("AA:AA:AA:AA:AA:AA"))

        session.clearActive()
        assertNull(session.activeResult)
    }

    /* ============ 13. Reconnect auf dasselbe Geraet ============ */

    @Test
    fun reconnectToSameDevice() {
        val session = RecognitionSession()
        val first = engine.recognize(
            RecognitionInput(
                advertisement = adv("NAVEE GT3 Max", address = "AA:AA:AA:AA:AA:AA"),
                gatt = naveeGatt()
            )
        )
        session.bindActive("AA:AA:AA:AA:AA:AA", first)
        session.archiveActive()

        val second = engine.recognize(
            RecognitionInput(
                advertisement = adv("NAVEE GT3 Max", address = "AA:AA:AA:AA:AA:AA"),
                gatt = naveeGatt()
            )
        )
        assertTrue(session.bindActive("AA:AA:AA:AA:AA:AA", second))
        assertEquals(second, session.activeResult)
        assertEquals("AA:AA:AA:AA:AA:AA", session.activeAddress)
        // Alte Historie bleibt getrennt, ueberschreibt aber nichts.
        assertEquals(first, session.lastActiveResult)
    }

    /* ============ 14. Reconnect auf ein anderes Geraet ============ */

    @Test
    fun reconnectToDifferentDeviceDiscardsOldData() {
        val session = RecognitionSession()
        val a = engine.recognize(
            RecognitionInput(
                advertisement = adv("NAVEE GT3 Max", address = "AA:AA:AA:AA:AA:AA"),
                gatt = naveeGatt()
            )
        )
        session.bindActive("AA:AA:AA:AA:AA:AA", a)
        session.archiveActive()

        val b = engine.recognize(
            RecognitionInput(
                advertisement = adv("Ninebot Max", address = "BB:BB:BB:BB:BB:BB"),
                gatt = gatt(
                    ninebotService to listOf(
                        ninebotChar to setOf(GattProperty.READ, GattProperty.WRITE)
                    )
                )
            )
        )
        assertTrue(session.bindActive("BB:BB:BB:BB:BB:BB", b))
        assertEquals("BB:BB:BB:BB:BB:BB", session.activeAddress)
        assertEquals(b, session.activeResult)
        assertFalse(session.hasActiveResultFor("AA:AA:AA:AA:AA:AA"))
        // Kein altes GATT des Vorgaengers im aktiven Ergebnis.
        assertEquals(ninebotService, session.activeResult?.gatt?.serviceUuids?.first())
    }

    /* ============ 15. Spaeter Callback einer alten Verbindung ============ */

    @Test
    fun lateCallbackOfOldConnectionIsDiscarded() {
        val stale = snapshot(
            generation = 8,
            discoveryGeneration = 7,
            status = GattDiscoveryStatus.COMPLETE
        )
        assertFalse(stale.discoveryIsFresh)
        assertEquals(GattDiscoveryStatus.NOT_ATTEMPTED, stale.effectiveDiscoveryStatus)
        assertTrue(stale.effectiveServices.isEmpty())
        assertNull(stale.effectiveDiscoveryError)

        val advOnly = engine.recognize(RecognitionInput(advertisement = adv("NAVEE GT3 Max")))
        val withStale = engine.recognize(
            RecognitionInput(
                advertisement = adv("NAVEE GT3 Max"),
                gatt = GattFeatures.from(
                    records = stale.effectiveServices,
                    discoveryStatus = stale.effectiveDiscoveryStatus,
                    errors = listOfNotNull(stale.effectiveDiscoveryError)
                )
            )
        )
        assertTrue(gattKinds(withStale).isEmpty())
        assertEquals(advOnly.confidence, withStale.confidence)
    }

    @Test
    fun freshSnapshotIsUsed() {
        val fresh = snapshot(generation = 8, discoveryGeneration = 8)
        assertTrue(fresh.discoveryIsFresh)
        assertEquals(GattDiscoveryStatus.COMPLETE, fresh.effectiveDiscoveryStatus)
        assertTrue(fresh.effectiveServices.isNotEmpty())
        assertTrue(fresh.matchesAddress("aa:bb:cc:dd:ee:ff"))
        assertFalse(fresh.matchesAddress("11:22:33:44:55:66"))
        assertFalse(fresh.matchesAddress(null))
    }

    /* ============ 16. Generische UUIDs bleiben ohne Modellbeweis ============ */

    @Test
    fun genericUuidsGiveNoModelProof() {
        val result = engine.recognize(
            RecognitionInput(
                advertisement = adv("Unbekannt"),
                gatt = gatt(
                    genericBattery to listOf(
                        "00002a19-0000-1000-8000-00805f9b34fb" to
                            setOf(GattProperty.READ, GattProperty.NOTIFY)
                    ),
                    genericDeviceInfo to listOf(
                        "00002a29-0000-1000-8000-00805f9b34fb" to setOf(GattProperty.READ)
                    )
                )
            )
        )
        assertTrue(gattKinds(result).isEmpty())
        assertNull(result.confirmedModel)
        assertNull(result.probableModel)
        assertTrue(
            result.confidence == RecognitionConfidence.UNKNOWN ||
                result.confidence == RecognitionConfidence.FAMILY_ONLY
        )
    }

    /* ============ 17. READ/WRITE/NOTIFY-Properties korrekt uebernommen ============ */

    @Test
    fun readWriteNotifyPropertiesAreAdopted() {
        val features = naveeGatt()
        val notify = features.characteristic(naveeNotify)
        val write = features.characteristic(naveeWrite)
        assertNotNull(notify)
        assertNotNull(write)

        assertTrue(notify!!.notifiable)
        assertFalse(notify.readable)
        assertFalse(notify.writable)
        assertEquals("NOTIFY", notify.propertyLabel())

        assertTrue(write!!.writable)
        assertFalse(write.notifiable)
        assertFalse(write.readable)
        assertEquals("WRITE|WRITE_NO_RESPONSE", write.propertyLabel())

        assertEquals(2, features.characteristicCount)
        assertEquals(listOf(naveeService), features.serviceUuids)
    }

    @Test
    fun indicateAndReadPropertiesAreAdopted() {
        val features = gatt(
            naveeService to listOf(
                naveeNotify to setOf(GattProperty.READ, GattProperty.INDICATE)
            )
        )
        val ch = features.characteristic(naveeNotify)!!
        assertTrue(ch.readable)
        assertTrue(ch.notifiable)
        assertFalse(ch.writable)
        assertEquals("READ|INDICATE", ch.propertyLabel())
    }

    /* ============ 18. Bestehende Erkennungs-Tests bleiben gueltig ============ */

    @Test
    fun legacySessionObserveStillWorks() {
        val session = RecognitionSession()
        val a = engine.recognize(
            RecognitionInput(advertisement = adv("NAVEE GT3 Max", address = "AA:AA:AA:AA:AA:AA"))
        )
        session.observe("AA:AA:AA:AA:AA:AA", a)
        assertEquals("AA:AA:AA:AA:AA:AA", session.currentAddress)
        assertEquals(a, session.current)
        assertTrue(session.hasResultFor("AA:AA:AA:AA:AA:AA"))
        assertTrue(session.hasScanResultFor("AA:AA:AA:AA:AA:AA"))

        val b = engine.recognize(
            RecognitionInput(advertisement = adv("Ninebot Max", address = "BB:BB:BB:BB:BB:BB"))
        )
        session.observe("BB:BB:BB:BB:BB:BB", b)
        assertEquals("BB:BB:BB:BB:BB:BB", session.currentAddress)
        assertFalse(session.hasResultFor("AA:AA:AA:AA:AA:AA"))
        // PHASE 17.2: das Scan-Ergebnis von A bleibt trotzdem getrennt erhalten.
        assertEquals(a, session.scanResultFor("AA:AA:AA:AA:AA:AA"))
    }

    @Test
    fun engineIsDeterministic() {
        val input = RecognitionInput(advertisement = adv("NAVEE GT3 Max"), gatt = naveeGatt())
        val first = engine.recognize(input)
        val second = engine.recognize(input)
        assertEquals(first.confidence, second.confidence)
        assertEquals(first.reasonCodes(), second.reasonCodes())
        assertEquals(
            first.matchedFeatures.map { it.label() },
            second.matchedFeatures.map { it.label() }
        )
    }

    /* ============ 19. Bestehende BLE-Vertraege bleiben gueltig ============ */

    @Test
    fun gattFeaturesDefaultStatusRemainsComplete() {
        // 17.1-Signatur: GattFeatures.from(records) ohne Status -> COMPLETE.
        val features = GattFeatures.from(
            listOf(GattServiceRecord(uuid = naveeService))
        )
        assertEquals(GattDiscoveryStatus.COMPLETE, features.discoveryStatus)
        assertTrue(features.hasAnyData)
    }

    @Test
    fun snapshotWithoutDiscoveryIsNotAttempted() {
        val snap = snapshot(
            status = GattDiscoveryStatus.NOT_ATTEMPTED,
            services = emptyList()
        )
        assertEquals(GattDiscoveryStatus.NOT_ATTEMPTED, snap.effectiveDiscoveryStatus)
        assertTrue(snap.effectiveServices.isEmpty())
    }

    /* ============ 20. JSON- und CSV-Export bleiben gueltig ============ */

    @Test
    fun jsonExportContainsComparisonAndStaysBackwardCompatible() {
        val scan = engine.recognize(RecognitionInput(advertisement = adv("NAVEE GT3 Max")))
        val final = engine.recognize(
            RecognitionInput(advertisement = adv("NAVEE GT3 Max"), gatt = naveeGatt())
        )
        val profile = RiderScooterProfile(
            brand = ScooterManufacturer.NAVEE,
            model = ScooterModel.NAVEE_GT3_MAX,
            serialNumber = "SN-SECRET-123"
        )
        val export = RecognitionExport.from(
            result = final,
            profile = profile,
            scanResult = scan,
            scanObservedAtMs = 1_000,
            gattObservedAtMs = 2_000
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
            recognition = export
        )
        val json = report.toJson()
        assertTrue(json.contains("\"schemaVersion\": 1"))
        assertTrue(json.contains("\"comparison\""))
        assertTrue(json.contains("\"scanConfidence\""))
        assertTrue(json.contains("\"finalConfidence\""))
        assertTrue(json.contains("\"gattDiscoveryStatus\""))
        assertTrue(json.contains("\"gattEvidenceAdded\""))
        // Bestehende Bloecke bleiben vorhanden.
        assertTrue(json.contains("\"userProfile\""))
        assertTrue(json.contains("\"hardwareDetection\""))
        assertTrue(json.contains("\"reasoning\""))
        // Datenschutz.
        assertFalse(json.contains("SN-SECRET-123"))
        assertTrue(json.contains("AA:BB:**:**:EE:FF"))
        assertEquals(scan.confidence.name, export.scanConfidence)
        assertEquals(final.confidence.name, export.finalConfidence)
    }

    @Test
    fun csvExportContainsComparisonSection() {
        val scan = engine.recognize(RecognitionInput(advertisement = adv("NAVEE GT3 Max")))
        val final = engine.recognize(
            RecognitionInput(advertisement = adv("NAVEE GT3 Max"), gatt = naveeGatt())
        )
        val report = HardwareTestReport(
            generatedAtEpochMs = 1,
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
            recognition = RecognitionExport.from(
                result = final,
                profile = null,
                scanResult = scan,
                scanObservedAtMs = 1_000,
                gattObservedAtMs = 2_000
            )
        )
        val csv = report.toCsv()
        assertTrue(csv.contains("recognition_compare,scanConfidence"))
        assertTrue(csv.contains("recognition_compare,finalConfidence"))
        assertTrue(csv.contains("recognition_compare,gattDiscoveryStatus,COMPLETE"))
        assertTrue(csv.contains("recognition_compare,scanObservedAtMs,1000"))
        assertTrue(csv.contains("recognition_compare,gattObservedAtMs,2000"))
        // Bestehende Sektionen bleiben erhalten.
        assertTrue(csv.contains("recognition_user,brand"))
        assertTrue(csv.contains("recognition_hw,confidence"))
        assertTrue(csv.contains("recognition_reason,reasons"))
    }

    /* ============ Zusatz: keine Prozente, MAC maskiert ============ */

    @Test
    fun noArtificialPercentagesInReasoning() {
        val result = engine.recognize(
            RecognitionInput(advertisement = adv("NAVEE GT3 Max"), gatt = naveeGatt())
        )
        assertFalse(result.summarySentence().contains("%"))
        assertFalse(result.reasons.any { it.detail.contains("%") })
    }

    @Test
    fun macAddressIsMaskedInExport() {
        val result = engine.recognize(
            RecognitionInput(advertisement = adv("NAVEE GT3 Max", address = "11:22:33:44:55:66"))
        )
        val export = RecognitionExport.from(result, null)
        assertEquals("11:22:**:**:55:66", export.deviceAddressMasked)
        assertFalse(export.deviceAddressMasked.contains("33:44"))
    }
}
