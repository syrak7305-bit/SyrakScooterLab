package com.syrak.scooterlab

import com.syrak.scooterlab.diagnostics.ConnectionRecord
import com.syrak.scooterlab.diagnostics.FrameDirection
import com.syrak.scooterlab.diagnostics.GattCharacteristicRecord
import com.syrak.scooterlab.diagnostics.GattProperty
import com.syrak.scooterlab.diagnostics.GattServiceRecord
import com.syrak.scooterlab.diagnostics.HardwareErrorKind
import com.syrak.scooterlab.diagnostics.HardwareTestCollector
import com.syrak.scooterlab.diagnostics.HardwareTestReport
import com.syrak.scooterlab.diagnostics.ScanRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 11 — Tests der Hardware-Test-/Export-Schicht und der zentralen
 * Schreib-/Read-only-Sperre.
 *
 * Kernaussagen:
 *  - Der Bericht enthaelt ausschliesslich beobachtete Daten und ist korrekt
 *    serialisierbar (JSON/CSV), inkl. Escaping.
 *  - Der Collector begrenzt den Speicher und fuehrt Scan-Treffer zusammen.
 *  - Unbekannte Modelle sind IMMER read-only; Schreiben nur bei Modell- UND
 *    Hardware-Verifikation mit modellspezifischem, schreibfaehigem Adapter.
 */
class Phase11HardwareTest {

    private fun sampleReport(
        readOnly: Boolean = true,
        deviceName: String? = "Navee GT3 Max",
        notes: List<String> = emptyList()
    ) = HardwareTestReport(
        generatedAtEpochMs = 1_700_000_000_000L,
        appVersion = "1.0-test",
        deviceName = deviceName,
        address = "AA:BB:CC:DD:EE:FF",
        manufacturer = "Navee",
        detectedModel = "Navee GT3 Max",
        confidence = "sicher erkannt",
        supportStatus = "Diagnose unterstützt",
        readOnly = readOnly,
        protocol = "Navee/Brightway",
        adapterId = "navee-gt3-max",
        connection = ConnectionRecord(
            connectedAtMs = 1000,
            readyAtMs = 1800,
            disconnectedAtMs = 5000,
            mtu = 247,
            protocol = "Navee/Brightway",
            adapterId = "navee-gt3-max",
            disconnectUnexpected = true
        ),
        scanResults = listOf(
            ScanRecord("Navee GT3 Max", "AA:BB:CC:DD:EE:FF", -55, "Navee", "Navee GT3 Max", "sicher erkannt", 100, 900, 4)
        ),
        services = listOf(
            GattServiceRecord(
                uuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
                label = "Navee/Brightway (Projekt-UUID)",
                characteristics = listOf(
                    GattCharacteristicRecord(
                        uuid = "0000ffe1-0000-1000-8000-00805f9b34fb",
                        properties = setOf(GattProperty.NOTIFY, GattProperty.READ)
                    ),
                    GattCharacteristicRecord(
                        uuid = "0000ffe2-0000-1000-8000-00805f9b34fb",
                        properties = setOf(GattProperty.WRITE, GattProperty.WRITE_NO_RESPONSE)
                    )
                )
            )
        ),
        frames = listOf(
            com.syrak.scooterlab.diagnostics.FrameRecord(FrameDirection.TX, "AA55010101", 1200, "handshake"),
            com.syrak.scooterlab.diagnostics.FrameRecord(FrameDirection.RX, "AA55012000", 1300, "notification")
        ),
        errors = listOf(
            com.syrak.scooterlab.diagnostics.HardwareErrorRecord(HardwareErrorKind.DISCONNECT, "Verbindung unerwartet getrennt.", 5000)
        ),
        notes = notes
    )

    /* ------------------------------- JSON ------------------------------- */

    @Test
    fun `json contains device, support and connection data`() {
        val json = sampleReport().toJson()
        assertTrue(json.contains("\"schemaVersion\": 1"))
        assertTrue(json.contains("\"detectedModel\": \"Navee GT3 Max\""))
        assertTrue(json.contains("\"supportStatus\": \"Diagnose unterstützt\""))
        assertTrue(json.contains("\"readOnly\": true"))
        assertTrue(json.contains("\"readyLatencyMs\": 800"))
        assertTrue(json.contains("\"sessionDurationMs\": 4000"))
        assertTrue(json.contains("\"mtu\": 247"))
        assertTrue(json.contains("\"disconnectUnexpected\": true"))
        assertTrue(json.contains("AA55010101"))
        assertTrue(json.contains("\"direction\": \"RX\""))
    }

    @Test
    fun `json escapes quotes and newlines`() {
        val json = sampleReport(notes = listOf("Zeile \"eins\"\nZeile zwei")).toJson()
        assertTrue(json.contains("Zeile \\\"eins\\\"\\nZeile zwei"))
        // Kein roher Zeilenumbruch innerhalb des Strings.
        assertFalse(json.contains("\"Zeile \"eins\""))
    }

    @Test
    fun `json renders null device name`() {
        val json = sampleReport(deviceName = null).toJson()
        assertTrue(json.contains("\"name\": null"))
    }

    @Test
    fun `json includes gatt properties`() {
        val json = sampleReport().toJson()
        assertTrue(json.contains("\"properties\": [\"READ\", \"NOTIFY\"]"))
        assertTrue(json.contains("\"properties\": [\"WRITE\", \"WRITE_NO_RESPONSE\"]"))
    }

    /* -------------------------------- CSV ------------------------------- */

    @Test
    fun `csv has header and section rows`() {
        val csv = sampleReport().toCsv()
        val lines = csv.trim().lines()
        assertEquals("section,field,value", lines.first())
        assertTrue(lines.any { it.startsWith("device,detectedModel,") })
        assertTrue(lines.any { it.startsWith("connection,mtu,247") })
        assertTrue(lines.any { it.startsWith("gatt,characteristic,") })
        assertTrue(lines.any { it.startsWith("frame,TX,") })
        assertTrue(lines.any { it.startsWith("error,DISCONNECT,") })
    }

    @Test
    fun `csv escapes commas and quotes`() {
        val csv = sampleReport(notes = listOf("a,b \"c\"")).toCsv()
        assertTrue(csv.contains("\"a,b \"\"c\"\"\""))
    }

    /* ------------------------------ Summary ----------------------------- */

    @Test
    fun `summary states read-only and counts`() {
        val summary = sampleReport(readOnly = true).toSummary()
        assertTrue(summary.contains("NUR LESEN (read-only)"))
        assertTrue(summary.contains("Frames: 2"))
        assertTrue(summary.contains("GATT: 1 Services / 2 Characteristics"))
        assertTrue(summary.contains("UNERWARTETER DISCONNECT"))
        assertTrue(summary.contains("NICHT automatisch validierte Protokoll-Unterstützung"))
    }

    /* ------------------------------ Collector --------------------------- */

    @Test
    fun `collector caps frames at maximum`() {
        val c = HardwareTestCollector(maxFrames = 3)
        repeat(5) { c.recordFrame(FrameDirection.TX, "AA$it", it.toLong()) }
        assertEquals(3, c.frameCount())
        // Die aeltesten wurden verworfen.
        assertEquals("AA2", c.frames().first().hex)
        assertEquals("AA4", c.frames().last().hex)
    }

    @Test
    fun `collector caps errors at maximum`() {
        val c = HardwareTestCollector(maxErrors = 2)
        repeat(4) { c.recordError(HardwareErrorKind.OTHER, "e$it", it.toLong()) }
        assertEquals(2, c.errorCount())
    }

    @Test
    fun `collector merges scan results by address`() {
        val c = HardwareTestCollector()
        val base = ScanRecord("Navee", "AA:BB", -60, "Navee", "Navee GT3 Max", "vermutet", 100, 100, 1)
        c.recordScan(base)
        c.recordScan(base.copy(rssi = -50, lastSeenMs = 500))
        c.recordScan(base.copy(address = "CC:DD", name = "Xiaomi"))
        val results = c.scanResults()
        assertEquals(2, results.size)
        val navee = results.first { it.address == "AA:BB" }
        assertEquals(2, navee.seenCount)
        assertEquals(-50, navee.rssi)
        assertEquals(500, navee.lastSeenMs)
    }

    /* --------------------------- GATT records --------------------------- */

    @Test
    fun `characteristic short flags and labels`() {
        val c = GattCharacteristicRecord(
            "x", setOf(GattProperty.READ, GattProperty.WRITE, GattProperty.NOTIFY, GattProperty.INDICATE)
        )
        assertEquals("RW", c.shortFlags().take(2))
        assertTrue(c.shortFlags().contains('N'))
        assertTrue(c.shortFlags().contains('I'))
        assertEquals("READ|WRITE|NOTIFY|INDICATE", c.propertyLabel())
        assertTrue(c.readable)
        assertTrue(c.writable)
        assertTrue(c.notifiable)
    }

    @Test
    fun `characteristic with no properties is unknown`() {
        val c = GattCharacteristicRecord("x", emptySet())
        assertEquals("—", c.propertyLabel())
        assertFalse(c.readable)
        assertFalse(c.writable)
    }

    /* -------------------------- ConnectionRecord ------------------------ */

    @Test
    fun `connection latencies computed only when valid`() {
        val c = ConnectionRecord(connectedAtMs = 100, readyAtMs = 250, disconnectedAtMs = 1000)
        assertEquals(150L, c.readyLatencyMs)
        assertEquals(900L, c.sessionDurationMs)

        val open = ConnectionRecord(connectedAtMs = 100, readyAtMs = 250)
        assertNull(open.sessionDurationMs)

        val weird = ConnectionRecord(connectedAtMs = 500, readyAtMs = 100)
        assertNull(weird.readyLatencyMs)
    }

    /* ------------------------------ WriteGuard -------------------------- */

    @Test
    fun `unknown model is always read-only`() {
        val decision = WriteGuard.evaluate(ScooterModel.UNKNOWN, hardwareVerified = true)
        assertEquals(WriteDecision.BLOCKED_UNKNOWN_MODEL, decision)
        assertFalse(decision.allowed)
        assertTrue(WriteGuard.isReadOnly(ScooterModel.UNKNOWN, hardwareVerified = true))
        assertNotNull(WriteGuard.blockReason(ScooterModel.UNKNOWN, hardwareVerified = true))
    }

    @Test
    fun `known model without hardware verification is blocked`() {
        val decision = WriteGuard.evaluate(ScooterModel.NAVEE_GT3_MAX, hardwareVerified = false)
        assertEquals(WriteDecision.BLOCKED_HARDWARE_NOT_VERIFIED, decision)
        assertFalse(decision.allowed)
    }

    @Test
    fun `known verified model with exact adapter is allowed`() {
        val decision = WriteGuard.evaluate(ScooterModel.NAVEE_GT3_MAX, hardwareVerified = true)
        assertEquals(WriteDecision.ALLOWED, decision)
        assertTrue(decision.allowed)
        assertNull(WriteGuard.blockReason(ScooterModel.NAVEE_GT3_MAX, hardwareVerified = true))
    }

    @Test
    fun `known verified model without adapter is blocked`() {
        // SoFlow ist bewusst NICHT in den Ninebot-Capabilities enthalten.
        val decision = WriteGuard.evaluate(ScooterModel.SOFLOW_SO4, hardwareVerified = true)
        assertEquals(WriteDecision.BLOCKED_NO_MODEL_ADAPTER, decision)
        assertFalse(decision.allowed)
        assertNotNull(WriteGuard.blockReason(ScooterModel.SOFLOW_SO4, hardwareVerified = true))
    }

    @Test
    fun `navee gt3 pro has no exact adapter and stays blocked`() {
        val decision = WriteGuard.evaluate(ScooterModel.NAVEE_GT3_PRO, hardwareVerified = true)
        assertEquals(WriteDecision.BLOCKED_NO_MODEL_ADAPTER, decision)
    }

    @Test
    fun `status line reflects decision`() {
        assertTrue(
            WriteGuard.statusLine(ScooterModel.NAVEE_GT3_MAX, hardwareVerified = true)
                .contains("freigegeben")
        )
        assertTrue(
            WriteGuard.statusLine(ScooterModel.UNKNOWN, hardwareVerified = true)
                .contains("GESPERRT")
        )
    }

    @Test
    fun `existing tuning safety gate is preserved`() {
        assertFalse(TuningSafetyGate.canSendTuning(hardwareVerified = false, modelKnown = true))
        assertFalse(TuningSafetyGate.canSendTuning(hardwareVerified = true, modelKnown = false))
        assertTrue(TuningSafetyGate.canSendTuning(hardwareVerified = true, modelKnown = true))
    }
}
