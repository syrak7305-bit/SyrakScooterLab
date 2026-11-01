package com.syrak.scooterlab

import com.syrak.scooterlab.diagnostics.HardwareErrorKind
import com.syrak.scooterlab.diagnostics.HardwareTestCollector
import com.syrak.scooterlab.diagnostics.ScanRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 16.3.2 — Scan, Geraeteerkennung, Namensaufloesung und ehrlicher
 * Verbindungsstatus.
 *
 * Diese Tests belegen die in Phase 16.3.2 neu eingefuehrte Logik:
 *
 *  A. Advertisement-Auswertung (Service-UUID / Company-ID / Service-Data)
 *  B. Namensaufloesung inkl. Herkunft und Platzhalter-Verwerfung
 *  C. Scan-Aggregation (mehrere Geraete, Duplikate, Namens-Regression)
 *  D. Ehrliche Trennung GATT-Verbindung vs. tatsaechliche Telemetrie
 *  E. Poll-Gate als Ursache fuer "0 Frames trotz erfolgreicher Discovery"
 *  F. Fehlerzaehlung im Hardware-Testbericht
 *
 * WICHTIG: Kein Test behauptet Protokoll-Kompatibilitaet. Ein
 * Advertisement-Hinweis macht ein Geraet hoechstens zum KANDIDATEN.
 */
class Phase1632ScanDetectionTest {

    // ---- Test-Hilfen --------------------------------------------------------

    private val e003 = "0000e003-0000-1000-8000-00805f9b34fb"
    private val ffe0 = "0000ffe0-0000-1000-8000-00805f9b34fb"
    private val fe95 = "0000fe95-0000-1000-8000-00805f9b34fb"

    private fun obs(
        address: String = "AA:BB:CC:DD:EE:FF",
        gatt: String? = null,
        adv: String? = null,
        services: List<String> = emptyList(),
        manufacturers: List<Int> = emptyList(),
        serviceData: List<String> = emptyList(),
        rssi: Int = -60,
        at: Long = 1_000L
    ) = ScanObservation(
        address = address,
        gattDeviceName = gatt,
        advertisedName = adv,
        serviceUuids = services,
        manufacturerIds = manufacturers,
        serviceDataUuids = serviceData,
        rssi = rssi,
        timestampMs = at
    )

    // ========================================================================
    //  UUID-Normalisierung
    // ========================================================================

    @Test
    fun uuid16BitShortFormIsExpanded() {
        assertEquals(e003, ScanObservation.normalizeUuid("e003"))
        assertEquals(e003, ScanObservation.normalizeUuid("0xE003"))
    }

    @Test
    fun uuidFullFormIsPassedThroughLowercased() {
        assertEquals(e003, ScanObservation.normalizeUuid("0000E003-0000-1000-8000-00805F9B34FB"))
    }

    @Test
    fun uuidNullAndBlankYieldNull() {
        assertNull(ScanObservation.normalizeUuid(null))
        assertNull(ScanObservation.normalizeUuid("   "))
    }

    @Test
    fun uuid32BitShortFormIsExpanded() {
        assertEquals(
            "12345678-0000-1000-8000-00805f9b34fb",
            ScanObservation.normalizeUuid("12345678")
        )
    }

    // ========================================================================
    //  B. Namensaufloesung
    // ========================================================================

    @Test
    fun gattNameWinsOverAdvertisedName() {
        val r = DeviceNameResolver.resolve(obs(gatt = "Ninebot Max G30", adv = "NB-G30"))
        assertEquals("Ninebot Max G30", r.name)
        assertEquals(DeviceNameSource.GATT_DEVICE_NAME, r.source)
    }

    @Test
    fun advertisedNameUsedWhenGattNameMissing() {
        val r = DeviceNameResolver.resolve(obs(gatt = null, adv = "NAVEE GT3"))
        assertEquals("NAVEE GT3", r.name)
        assertEquals(DeviceNameSource.ADVERTISEMENT, r.source)
    }

    @Test
    fun legacyPlaceholderIsNeverTreatedAsRealName() {
        // "Unknown Device" darf NICHT als Name durchgehen (alter Defekt R3/R4).
        val r = DeviceNameResolver.resolve(obs(gatt = "Unknown Device", adv = null))
        assertNull(r.name)
        assertEquals(DeviceNameSource.NONE, r.source)
        assertFalse(r.hasName)
        assertEquals("Name nicht verfuegbar", r.display())
    }

    @Test
    fun emptyIncomingNameNeverDisplacesKnownName() {
        val known = ResolvedName("Ninebot", DeviceNameSource.GATT_DEVICE_NAME)
        val empty = ResolvedName(null, DeviceNameSource.NONE)
        val merged = DeviceNameResolver.prefer(known, empty)
        assertEquals("Ninebot", merged.name)
        assertEquals(DeviceNameSource.GATT_DEVICE_NAME, merged.source)
    }

    @Test
    fun higherPrioritySourceWins() {
        val adv = ResolvedName("NB", DeviceNameSource.ADVERTISEMENT)
        val gatt = ResolvedName("Ninebot Max", DeviceNameSource.GATT_DEVICE_NAME)
        val merged = DeviceNameResolver.prefer(adv, gatt)
        assertEquals("Ninebot Max", merged.name)
        assertEquals(DeviceNameSource.GATT_DEVICE_NAME, merged.source)
    }

    @Test
    fun lowerPrioritySourceDoesNotOverwriteHigher() {
        val gatt = ResolvedName("Ninebot Max", DeviceNameSource.GATT_DEVICE_NAME)
        val adv = ResolvedName("NB", DeviceNameSource.ADVERTISEMENT)
        val merged = DeviceNameResolver.prefer(gatt, adv)
        assertEquals("Ninebot Max", merged.name)
    }

    // ========================================================================
    //  A. Advertisement-Analyse
    // ========================================================================

    @Test
    fun unnamedDeviceWithE003BecomesStrongHintOnly() {
        val ev = AdvertisementAnalyzer.analyze(obs(services = listOf(e003)))
        assertTrue(ev.hasHint)
        assertTrue(ev.isStrong)
        assertTrue(ev.justifiesCandidate)
        assertEquals(EvidenceKind.SERVICE_UUID, ev.kind)
        assertEquals(ScooterManufacturer.NINEBOT, ev.manufacturer)
    }

    @Test
    fun unnamedDeviceWithFfe0BecomesStrongNaveeHint() {
        val ev = AdvertisementAnalyzer.analyze(obs(services = listOf(ffe0)))
        assertEquals(EvidenceKind.SERVICE_UUID, ev.kind)
        assertEquals(ScooterManufacturer.NAVEE, ev.manufacturer)
        assertTrue(ev.justifiesCandidate)
    }

    @Test
    fun deviceWithoutAnyHintStaysWithoutEvidence() {
        val ev = AdvertisementAnalyzer.analyze(obs())
        assertFalse(ev.hasHint)
        assertFalse(ev.justifiesCandidate)
        assertEquals(ScooterManufacturer.UNKNOWN, ev.manufacturer)
    }

    @Test
    fun weakXiaomiServiceDataDoesNotJustifyCandidate() {
        // fe95 (MiBeacon) allein ist KEIN Scooter-Hinweis.
        val ev = AdvertisementAnalyzer.analyze(obs(serviceData = listOf(fe95)))
        assertTrue(ev.hasHint)
        assertFalse(ev.isStrong)
        assertFalse(ev.justifiesCandidate)
        assertEquals(EvidenceStrength.WEAK, ev.strength)
        assertEquals(EvidenceKind.SERVICE_DATA, ev.kind)
    }

    @Test
    fun nameHintIsStrongEvidence() {
        val ev = AdvertisementAnalyzer.analyze(obs(gatt = "Ninebot Max G30"))
        assertEquals(EvidenceKind.NAME, ev.kind)
        assertEquals(ScooterManufacturer.NINEBOT, ev.manufacturer)
        assertTrue(ev.justifiesCandidate)
    }

    @Test
    fun companyIdRegistryIsDeliberatelyEmpty() {
        // Kein belegbarer SIG-Company-ID im Projekt -> nichts wird geraten.
        assertTrue(CompanyIdRegistry.isEmpty())
        val ev = AdvertisementAnalyzer.analyze(obs(manufacturers = listOf(0x004C)))
        assertFalse(ev.hasHint)
    }

    @Test
    fun documentedCompanyIdOverrideIsHonoured() {
        // Der Mechanismus ist vollstaendig; ein belegter Wert genuegt.
        val ev = AdvertisementAnalyzer.analyze(
            obs(manufacturers = listOf(0x1234)),
            companyIds = mapOf(0x1234 to ScooterManufacturer.SEGWAY)
        )
        assertEquals(EvidenceKind.MANUFACTURER_ID, ev.kind)
        assertEquals(ScooterManufacturer.SEGWAY, ev.manufacturer)
        assertTrue(ev.justifiesCandidate)
    }

    // ========================================================================
    //  ScanClassifier auf Basis einer Beobachtung
    // ========================================================================

    @Test
    fun namedNinebotIsKnownScooter() {
        val c = ScanClassifier.classify(obs(gatt = "Ninebot Max G30"))
        assertEquals(ScanCategory.KNOWN_SCOOTER, c.category)
        assertEquals(DeviceNameSource.GATT_DEVICE_NAME, c.nameSource)
    }

    @Test
    fun unnamedE003IsOnlyProbableNeverKnown() {
        val c = ScanClassifier.classify(obs(services = listOf(e003)))
        assertEquals(ScanCategory.PROBABLE_SCOOTER, c.category)
        // Aus einer UUID wird NIE ein Modell abgeleitet.
        assertEquals("unbekannt", c.modelLabel)
        assertEquals("Hinweis (Advertisement)", c.confidenceLabel)
        assertEquals(DeviceNameSource.NONE, c.nameSource)
    }

    @Test
    fun unnamedDeviceWithoutHintStaysUnknown() {
        val c = ScanClassifier.classify(obs())
        assertEquals(ScanCategory.UNKNOWN_DEVICE, c.category)
        assertFalse(c.isScooterLike)
    }

    @Test
    fun weakHintAloneDoesNotCreateCandidate() {
        val c = ScanClassifier.classify(obs(serviceData = listOf(fe95)))
        assertEquals(ScanCategory.UNKNOWN_DEVICE, c.category)
    }

    // ========================================================================
    //  C. Scan-Aggregation
    // ========================================================================

    @Test
    fun firstObservationStartsAggregate() {
        val agg = ScanAggregator()
        val a = agg.observe(obs(gatt = "Ninebot", at = 1_000L, rssi = -50))
        assertEquals(1, a.seenCount)
        assertEquals(1_000L, a.firstSeenMs)
        assertEquals(1_000L, a.lastSeenMs)
        assertEquals(-50, a.rssi)
        assertTrue(a.hasRealName)
    }

    @Test
    fun duplicateObservationUpdatesCountAndRssiButKeepsFirstSeen() {
        val agg = ScanAggregator()
        agg.observe(obs(gatt = "Ninebot", at = 1_000L, rssi = -50))
        val a = agg.observe(obs(gatt = "Ninebot", at = 2_000L, rssi = -70))
        assertEquals(2, a.seenCount)
        assertEquals(1_000L, a.firstSeenMs)
        assertEquals(2_000L, a.lastSeenMs)
        assertEquals(-70, a.rssi)
        assertEquals(1_000L, a.durationMs)
    }

    @Test
    fun laterObservationWithoutNameKeepsKnownName() {
        // Namens-Regression (R3): ein Callback ohne Namen darf den guten Namen
        // nicht ueberschreiben.
        val agg = ScanAggregator()
        agg.observe(obs(gatt = "Ninebot Max G30", at = 1_000L))
        val a = agg.observe(obs(gatt = null, adv = null, at = 2_000L))
        assertEquals("Ninebot Max G30", a.name)
        assertEquals(DeviceNameSource.GATT_DEVICE_NAME, a.nameSource)
        assertTrue(a.hasRealName)
    }

    @Test
    fun serviceUuidsAreUnionedAcrossObservations() {
        val agg = ScanAggregator()
        agg.observe(obs(services = listOf(e003), at = 1_000L))
        val a = agg.observe(obs(services = listOf(ffe0), at = 2_000L))
        assertTrue(a.serviceUuids.contains(e003))
        assertTrue(a.serviceUuids.contains(ffe0))
    }

    @Test
    fun strongerEvidenceIsNeverReplacedByWeaker() {
        val agg = ScanAggregator()
        val strong = AdvertisementAnalyzer.analyze(obs(services = listOf(e003)))
        val weak = AdvertisementAnalyzer.analyze(obs(serviceData = listOf(fe95)))
        agg.observe(obs(services = listOf(e003), at = 1_000L), strong)
        val a = agg.observe(obs(serviceData = listOf(fe95), at = 2_000L), weak)
        assertTrue(a.evidence.isStrong)
        assertEquals(EvidenceKind.SERVICE_UUID, a.evidence.kind)
    }

    @Test
    fun multipleDevicesAreTrackedSeparately() {
        val agg = ScanAggregator()
        agg.observe(obs(address = "AA:AA", gatt = "Ninebot", at = 1_000L))
        agg.observe(obs(address = "BB:BB", gatt = "NAVEE", at = 1_000L))
        assertEquals(2, agg.size())
        assertEquals("Ninebot", agg.get("AA:AA")?.name)
        assertEquals("NAVEE", agg.get("BB:BB")?.name)
    }

    @Test
    fun clearResetsAllAggregates() {
        val agg = ScanAggregator()
        agg.observe(obs(gatt = "Ninebot"))
        agg.clear()
        assertEquals(0, agg.size())
        assertNull(agg.get("AA:BB:CC:DD:EE:FF"))
    }

    // ========================================================================
    //  D. Ehrlicher Verbindungsstatus (GATT != Telemetrie)
    // ========================================================================

    private fun diag(
        notifications: Long = 0,
        decodeSuccess: Long = 0,
        discoveryDone: Boolean = false,
        notifyChainComplete: Boolean = false
    ) = TelemetryDiagnosticsSnapshot(
        notifications = notifications,
        decodeSuccess = decodeSuccess,
        discoveryDone = discoveryDone,
        localNotifyRegistrationAttempted = notifyChainComplete,
        localNotifyRegistered = notifyChainComplete,
        cccdWriteAccepted = notifyChainComplete,
        descriptorCallbackSuccess = notifyChainComplete
    )

    @Test
    fun engineReadyWithoutFramesIsNotSuccess() {
        // Kernauftrag D: erfolgreicher GATT-Connect darf NICHT als
        // erfolgreiche Telemetrie gelten.
        val stage = ConnectionStagePolicy.evaluate(
            engineReady = true,
            linkState = BleLinkState.READY,
            lastConnectFailed = false,
            isScanning = false,
            deviceFound = true,
            diagnostics = diag()
        )
        assertEquals(ConnectionStage.CONNECTED_NO_TELEMETRY, stage)
        assertEquals(StageSeverity.WARNING, stage.severity)
    }

    @Test
    fun decodedTelemetryIsSuccess() {
        val stage = ConnectionStagePolicy.evaluate(
            engineReady = true,
            linkState = BleLinkState.READY,
            lastConnectFailed = false,
            isScanning = false,
            deviceFound = true,
            diagnostics = diag(notifications = 5, decodeSuccess = 3, discoveryDone = true)
        )
        assertEquals(ConnectionStage.TELEMETRY_ACTIVE, stage)
        assertEquals(StageSeverity.SUCCESS, stage.severity)
    }

    @Test
    fun receivedDataWithoutDecodeIsWarning() {
        val stage = ConnectionStagePolicy.evaluate(
            engineReady = true,
            linkState = BleLinkState.READY,
            lastConnectFailed = false,
            isScanning = false,
            deviceFound = true,
            diagnostics = diag(notifications = 2, decodeSuccess = 0, discoveryDone = true)
        )
        assertEquals(ConnectionStage.DATA_RECEIVED_DECODE_UNCLEAR, stage)
    }

    @Test
    fun completeNotifyChainWithoutDataIsWarning() {
        val stage = ConnectionStagePolicy.evaluate(
            engineReady = true,
            linkState = BleLinkState.READY,
            lastConnectFailed = false,
            isScanning = false,
            deviceFound = true,
            diagnostics = diag(discoveryDone = true, notifyChainComplete = true)
        )
        assertEquals(ConnectionStage.NOTIFY_ACTIVE_NO_TELEMETRY, stage)
    }

    @Test
    fun incompleteNotifyChainIsReported() {
        val stage = ConnectionStagePolicy.evaluate(
            engineReady = true,
            linkState = BleLinkState.READY,
            lastConnectFailed = false,
            isScanning = false,
            deviceFound = true,
            diagnostics = diag(discoveryDone = true, notifyChainComplete = false)
        )
        assertEquals(ConnectionStage.NOTIFY_CHAIN_INCOMPLETE, stage)
    }

    @Test
    fun connectedLinkWithoutEngineIsInvestigating() {
        val stage = ConnectionStagePolicy.evaluate(
            engineReady = false,
            linkState = BleLinkState.CONNECTED,
            lastConnectFailed = false,
            isScanning = false,
            deviceFound = true,
            diagnostics = null
        )
        assertEquals(ConnectionStage.SERVICES_INVESTIGATING, stage)
    }

    @Test
    fun connectingStateIsReported() {
        val stage = ConnectionStagePolicy.evaluate(
            engineReady = false,
            linkState = BleLinkState.CONNECTING,
            lastConnectFailed = false,
            isScanning = false,
            deviceFound = false,
            diagnostics = null
        )
        assertEquals(ConnectionStage.CONNECTING, stage)
    }

    @Test
    fun failedConnectIsReported() {
        val stage = ConnectionStagePolicy.evaluate(
            engineReady = false,
            linkState = BleLinkState.IDLE,
            lastConnectFailed = true,
            isScanning = false,
            deviceFound = false,
            diagnostics = null
        )
        assertEquals(ConnectionStage.CONNECT_FAILED, stage)
        assertEquals(StageSeverity.DANGER, stage.severity)
    }

    @Test
    fun scanningAndIdleStatesAreReported() {
        val scanning = ConnectionStagePolicy.evaluate(
            false, BleLinkState.IDLE, false, true, false, null
        )
        assertEquals(ConnectionStage.SCANNING, scanning)

        val idle = ConnectionStagePolicy.evaluate(
            false, BleLinkState.IDLE, false, false, false, null
        )
        assertEquals(ConnectionStage.IDLE, idle)
        assertEquals(StageSeverity.MUTED, idle.severity)
    }

    // ========================================================================
    //  E. Poll-Gate als Ursache fuer "0 Frames trotz Discovery OK"
    // ========================================================================

    @Test
    fun unknownModelBlocksPoll() {
        val decision = PollGate.evaluate(
            engineReady = true,
            hasWriteChar = true,
            modelKnown = false,
            adapterSupportsRead = true
        )
        assertEquals(PollDecision.BLOCKED_UNKNOWN_MODEL, decision)
        assertFalse(PollGate.shouldPoll(true, true, false, true))
        assertNotNull(PollGate.reason(decision))
    }

    @Test
    fun knownModelWithWriteCharAllowsPoll() {
        assertTrue(PollGate.shouldPoll(true, true, true, true))
        assertEquals(PollDecision.ALLOWED, PollGate.evaluate(true, true, true, true))
    }

    // ========================================================================
    //  F. Fehlerzaehlung im Hardware-Testbericht
    // ========================================================================

    @Test
    fun collectorAggregatesDuplicateScanAndKeepsName() {
        val c = HardwareTestCollector()
        c.recordScan(
            ScanRecord("Ninebot", "AA:BB", -50, "Ninebot", "Max G30", "PROBABLE", 1_000L, 1_000L, 1)
        )
        c.recordScan(
            ScanRecord(null, "AA:BB", -70, "", "", "", 1_000L, 2_000L, 1)
        )
        val recs = c.scanResults()
        assertEquals(1, recs.size)
        assertEquals(2, recs[0].seenCount)
        assertEquals("Ninebot", recs[0].name)   // kein Rueckfall auf Platzhalter
        assertEquals(-70, recs[0].rssi)
        assertEquals(1_000L, recs[0].firstSeenMs)
        assertEquals(2_000L, recs[0].lastSeenMs)
    }

    @Test
    fun collectorCountsOnlyRecordedErrors() {
        val c = HardwareTestCollector()
        assertEquals(0, c.errorCount())
        c.recordError(HardwareErrorKind.DISCONNECT, "unerwartet", 1_000L)
        assertEquals(1, c.errorCount())
    }

    @Test
    fun nullNameScanRecordStaysNull() {
        val c = HardwareTestCollector()
        c.recordScan(ScanRecord(null, "CC:DD", -60, "", "", "", 1_000L, 1_000L, 1))
        assertNull(c.scanResults()[0].name)
    }

    // ========================================================================
    //  G. ScooterDevice-Anzeige
    // ========================================================================

    @Test
    fun scooterDeviceWithoutNameShowsHonestFallback() {
        val d = ScooterDevice(null, null, "AA:BB", -60, false)
        assertEquals("Name nicht verfuegbar", d.displayName())
        assertFalse(d.hasRealName)
    }

    @Test
    fun scooterDeviceWithRealNameIsMarked() {
        val d = ScooterDevice(null, "Ninebot", "AA:BB", -60, true, false, DeviceNameSource.GATT_DEVICE_NAME)
        assertEquals("Ninebot", d.displayName())
        assertTrue(d.hasRealName)
    }
}
