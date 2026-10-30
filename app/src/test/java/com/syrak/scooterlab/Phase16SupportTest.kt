package com.syrak.scooterlab

import com.syrak.scooterlab.diagnostics.FrameDirection
import com.syrak.scooterlab.diagnostics.HardwareTestCollector
import com.syrak.scooterlab.diagnostics.HardwareTestReport
import com.syrak.scooterlab.diagnostics.ReadRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 16 – Unit-Tests fuer BLE-Read-Pfad, UUID-Registry, CompatibilityLadder,
 * Firmware-Identitaetssicherung und Hardware-Test-Collector.
 *
 * Diese Tests laufen vollstaendig auf der JVM (kein echtes Geraet). Sie belegen
 * Implementierung und Fehlerbehandlung, NICHT die Funktion an realer Hardware.
 */
class Phase16SupportTest {

    /* ---------------- BleUuids ---------------- */

    @Test
    fun `uuid registry has unique keys and uuids`() {
        val defs = BleUuids.all()
        assertTrue(defs.isNotEmpty())
        assertEquals(defs.size, defs.map { it.key }.toSet().size)
        // Ein UUID-Wert darf nur einmal definiert sein (kein stiller Widerspruch).
        assertEquals(defs.size, defs.map { it.uuid.lowercase() }.toSet().size)
    }

    @Test
    fun `uuid registry reports no internal conflicts`() {
        assertTrue(BleUuids.conflicts().isEmpty())
    }

    @Test
    fun `cccd is documented but no scooter uuid is hardware confirmed`() {
        assertEquals(UuidProvenance.DOCUMENTED, BleUuids.provenanceOf(BleUuids.CCCD))
        // Ehrlichkeit: aktuell ist KEINE Scooter-UUID am realen Geraet bestaetigt.
        assertEquals(0, BleUuids.confirmedOnHardwareCount())
        assertTrue(BleUuids.unprovenCount() > 0)
    }

    @Test
    fun `active uuids are attributed to the active ble engine`() {
        val active = BleUuids.activeUuids()
        assertTrue(active.isNotEmpty())
        assertTrue(active.all { def -> def.usedBy.any { it.contains("BleEngine") } })
    }

    @Test
    fun `transport contradiction is documented honestly`() {
        val notes = BleUuids.transportContradictions()
        assertTrue(notes.isNotEmpty())
        // Die offene Frage muss die beiden konkurrierenden Definitionen nennen.
        val joined = notes.joinToString(" ")
        assertTrue(joined.contains("6e400001") || joined.contains("UART"))
        assertTrue(joined.contains("e003") || joined.contains("0000e003"))
    }

    /* ---------------- ReadClassifier ---------------- */

    @Test
    fun `read callback success requires gatt success and bytes`() {
        assertEquals(ReadStatus.SUCCESS, ReadClassifier.fromCallback(0, 0, 4))
        assertEquals(ReadStatus.EMPTY_VALUE, ReadClassifier.fromCallback(0, 0, 0))
        assertEquals(ReadStatus.GATT_ERROR, ReadClassifier.fromCallback(5, 0, 4))
    }

    @Test
    fun `read request classification covers all failure modes`() {
        assertEquals(ReadRequestResult.NOT_CONNECTED, ReadClassifier.fromRequest(false, true, true, true))
        assertEquals(ReadRequestResult.NO_CHARACTERISTIC, ReadClassifier.fromRequest(true, false, true, true))
        assertEquals(ReadRequestResult.NOT_READABLE, ReadClassifier.fromRequest(true, true, false, true))
        assertEquals(ReadRequestResult.REJECTED, ReadClassifier.fromRequest(true, true, true, false))
        assertEquals(ReadRequestResult.STARTED, ReadClassifier.fromRequest(true, true, true, true))
    }

    @Test
    fun `read outcome success mirrors status`() {
        val ok = ReadOutcome("u", ReadStatus.SUCCESS, 0, "AA", 1, 10)
        val err = ReadOutcome("u", ReadStatus.GATT_ERROR, 5)
        assertTrue(ok.success)
        assertFalse(err.success)
    }

    /* ---------------- ReadPolicy ---------------- */

    @Test
    fun `auto read only allowed for known and verified hardware`() {
        assertTrue(ReadPolicy.mayAutoRead(modelKnown = true, hardwareVerified = true))
        assertFalse(ReadPolicy.mayAutoRead(modelKnown = true, hardwareVerified = false))
        assertFalse(ReadPolicy.mayAutoRead(modelKnown = false, hardwareVerified = true))
        assertNotNull(ReadPolicy.autoReadBlockReason(modelKnown = false, hardwareVerified = false))
        assertNull(ReadPolicy.autoReadBlockReason(modelKnown = true, hardwareVerified = true))
        assertTrue(ReadPolicy.requiresManualConfirmation(modelKnown = false))
    }

    /* ---------------- ReadTracker ---------------- */

    @Test
    fun `tracker allows only one in flight read`() {
        val t = ReadTracker()
        assertTrue(t.begin("u1", 1, 1000))
        assertFalse(t.begin("u2", 1, 1000))
        assertTrue(t.isPending())
        assertEquals("u1", t.pendingUuid())
        assertNotNull(t.complete())
        assertFalse(t.isPending())
        assertTrue(t.begin("u2", 1, 1000))
    }

    @Test
    fun `tracker matches uuid and generation`() {
        val t = ReadTracker()
        t.begin("ABCD", 3, 1000)
        assertTrue(t.matches("abcd", 3))
        assertFalse(t.matches("abcd", 4))
        assertFalse(t.matches("other", 3))
    }

    @Test
    fun `tracker reports timeout only after limit`() {
        val t = ReadTracker()
        t.begin("u", 1, 1000)
        assertNull(t.timeout(1500, 1000))
        val timedOut = t.timeout(2000, 1000)
        assertNotNull(timedOut)
        assertEquals("u", timedOut!!.uuid)
        assertFalse(t.isPending())
    }

    @Test
    fun `tracker abort clears pending read`() {
        val t = ReadTracker()
        t.begin("u", 1, 1000)
        assertNotNull(t.abort())
        assertFalse(t.isPending())
        assertNull(t.abort())
    }

    /* ---------------- ReadSelector ---------------- */

    @Test
    fun `read selector prefers readable characteristic`() {
        val facts = listOf(
            CharacteristicFacts("w", readable = false, writable = true),
            CharacteristicFacts("r1", readable = true),
            CharacteristicFacts("r2", readable = true)
        )
        assertEquals(listOf("r1", "r2"), ReadSelector.readableUuids(facts))
        assertEquals("r2", ReadSelector.pick(facts, "r2"))
        assertEquals("r1", ReadSelector.pick(facts))
        assertNull(ReadSelector.pick(emptyList()))
    }

    /* ---------------- CompatibilityLadder (7 Stufen) ---------------- */

    @Test
    fun `ladder separates read property from successful read`() {
        val onlyProperty = CompatibilityLadder.evaluate(
            CompatibilityEvidence(
                detected = true,
                gattDiscovered = true,
                readPropertyFound = true,
                readOnlyTested = false
            )
        )
        assertEquals(CompatibilityStage.READ_PROPERTY_FOUND, onlyProperty.highest)
        assertFalse(onlyProperty.stages.first { it.stage == CompatibilityStage.READ_ONLY_TESTED }.reached)

        val readOk = CompatibilityLadder.evaluate(
            CompatibilityEvidence(
                detected = true,
                gattDiscovered = true,
                readPropertyFound = true,
                readOnlyTested = true
            )
        )
        assertEquals(CompatibilityStage.READ_ONLY_TESTED, readOk.highest)
    }

    @Test
    fun `read success without read property does not unlock stage`() {
        // Widerspruch: Read-Erfolg behauptet, aber keine lesbare Characteristic.
        val report = CompatibilityLadder.evaluate(
            CompatibilityEvidence(
                detected = true,
                gattDiscovered = true,
                readPropertyFound = false,
                readOnlyTested = true
            )
        )
        assertEquals(CompatibilityStage.GATT_DISCOVERED, report.highest)
    }

    /* ---------------- FirmwareImportGuard ---------------- */

    @Test
    fun `firmware import requires matching package hash and model`() {
        assertEquals(
            FirmwareImportDecision.ALLOWED,
            FirmwareImportGuard.evaluate("p1", "p1", true, true, true)
        )
        assertEquals(
            FirmwareImportDecision.NO_VALIDATION,
            FirmwareImportGuard.evaluate("p1", "p1", false, true, true)
        )
        assertEquals(
            FirmwareImportDecision.PACKAGE_MISMATCH,
            FirmwareImportGuard.evaluate("p1", "p2", true, true, true)
        )
        assertEquals(
            FirmwareImportDecision.PACKAGE_MISMATCH,
            FirmwareImportGuard.evaluate("p1", null, true, true, true)
        )
        assertEquals(
            FirmwareImportDecision.HASH_NOT_VERIFIED,
            FirmwareImportGuard.evaluate("p1", "p1", true, false, true)
        )
        assertEquals(
            FirmwareImportDecision.MODEL_MISMATCH,
            FirmwareImportGuard.evaluate("p1", "p1", true, true, false)
        )
    }

    @Test
    fun `firmware import rejection has an honest reason`() {
        val reason = FirmwareImportGuard.reason(FirmwareImportDecision.PACKAGE_MISMATCH)
        assertTrue(reason.contains("anderen Paket") || reason.contains("different package"))
    }

    /* ---------------- HardwareTestCollector (Reads / RSSI) ---------------- */

    @Test
    fun `collector records reads and rssi series`() {
        val c = HardwareTestCollector()
        c.recordRead(ReadRecord("u1", "SUCCESS", true, 4, 12, 100))
        c.recordRead(ReadRecord("u2", "TIMEOUT", false, 0, 3000, 200))
        c.recordRead(ReadRecord("u3", "GATT_ERROR", false, 0, 5, 300))
        c.recordRssi(-60, 100)
        c.recordRssi(-65, 200)

        assertEquals(3, c.readCount())
        assertEquals(2, c.rssiCount())
        assertEquals(3, c.reads().size)
        assertEquals(2, c.rssiSamples().size)
    }

    @Test
    fun `report serialises reads and rssi honestly`() {
        val report = HardwareTestReport(
            generatedAtEpochMs = 1L,
            appVersion = "test",
            deviceName = "Scooter",
            address = "AA:BB",
            manufacturer = "Navee",
            detectedModel = "unbekannt",
            confidence = "niedrig",
            supportStatus = "DISCOVERED",
            readOnly = true,
            protocol = "NAVEE",
            adapterId = "navee-gt3max",
            reads = listOf(
                ReadRecord("u1", "SUCCESS", true, 4, 12, 100),
                ReadRecord("u2", "TIMEOUT", false, 0, 3000, 200)
            ),
            rssiSamples = listOf(
                com.syrak.scooterlab.diagnostics.RssiSample(-60, 100)
            )
        )
        assertEquals(1, report.successfulReads)
        assertEquals(0, report.failedReads)
        assertEquals(1, report.readTimeouts)

        val json = report.toJson()
        assertTrue(json.contains("\"reads\""))
        assertTrue(json.contains("\"rssiSeries\""))
        assertTrue(json.contains("\"schemaVersion\": 1"))

        val csv = report.toCsv()
        assertTrue(csv.contains("read,u1"))
        assertTrue(csv.contains("rssi,sample"))

        val summary = report.toSummary()
        assertTrue(summary.contains("Reads:"))
    }

    @Test
    fun `collector clear resets reads and rssi`() {
        val c = HardwareTestCollector()
        c.recordRead(ReadRecord("u", "SUCCESS", true, 1, 1, 1))
        c.recordRssi(-50, 1)
        c.recordFrame(FrameDirection.RX, "AA", 1)
        c.clear()
        assertEquals(0, c.readCount())
        assertEquals(0, c.rssiCount())
        assertEquals(0, c.frameCount())
    }
}
