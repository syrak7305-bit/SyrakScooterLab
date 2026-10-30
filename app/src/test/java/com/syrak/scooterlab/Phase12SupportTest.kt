package com.syrak.scooterlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 12 — Tests fuer die neuen, Android-freien Logikbausteine.
 *
 * Geprueft wird ausschliesslich Verhalten, das ohne Geraet reproduzierbar ist.
 * Hardware-Verhalten (echte BLE-Verbindungen) kann hier NICHT getestet werden.
 */
class Phase12SupportTest {

    /* ---------------- 12.1 Laender-Hinweise ---------------- */

    @Test
    fun `country catalog contains verified entries with source and date`() {
        val de = CountryLegalCatalog.forCountryCode("DE")
        assertNotNull(de)
        assertEquals(20, de!!.legalSpeedKmh)
        assertTrue(de.verified)
        assertTrue(de.source.isNotBlank())
        assertTrue(de.sourceDate.isNotBlank())
        assertTrue(de.points.isNotEmpty())
    }

    @Test
    fun `country catalog does not invent speed limits for unknown countries`() {
        val world = CountryLegalCatalog.forCountryCode("ZZ")
        assertNotNull(world)
        assertNull(world!!.legalSpeedKmh)
        assertFalse(world.verified)
        assertTrue(world.displayName.isNotBlank())
    }

    @Test
    fun `country catalog covers all known regions`() {
        Region.values().forEach { region ->
            val notice = CountryLegalCatalog.forRegion(region)
            assertNotNull("Region ${region.code} fehlt im Katalog", notice)
            assertEquals(region.code, notice!!.code)
        }
    }

    @Test
    fun `us notice is explicitly marked as not verified`() {
        val us = CountryLegalCatalog.forCountryCode("US")
        assertNotNull(us)
        assertFalse(us!!.verified)
        assertNull(us.legalSpeedKmh)
    }

    /* ---------------- 12.3 Scan-Klassifikation ---------------- */

    @Test
    fun `known scooter name is classified as known scooter`() {
        val c = ScanClassifier.classify("NAVEE GT3 Max", "AA:BB:CC:DD:EE:01")
        assertEquals(ScanCategory.KNOWN_SCOOTER, c.category)
        assertTrue(c.isScooterLike)
        assertTrue(c.manufacturerKnown)
    }

    @Test
    fun `unknown bluetooth device is not treated as scooter`() {
        val c = ScanClassifier.classify("Mi Band 7", "AA:BB:CC:DD:EE:02")
        assertEquals(ScanCategory.UNKNOWN_DEVICE, c.category)
        assertFalse(c.isScooterLike)
    }

    @Test
    fun `null name does not crash classification`() {
        val c = ScanClassifier.classify(null, "AA:BB:CC:DD:EE:03")
        assertEquals(ScanCategory.UNKNOWN_DEVICE, c.category)
    }

    /* ---------------- 12.3 Signalqualitaet ---------------- */

    @Test
    fun `signal quality maps rssi monotonically`() {
        val strong = SignalQualityScale.fromRssi(-50)
        val weak = SignalQualityScale.fromRssi(-95)
        assertTrue(strong.bars >= weak.bars)
        assertTrue(strong.bars in 0..4)
        assertTrue(weak.bars in 0..4)
    }

    @Test
    fun `signal quality provides accessible label`() {
        val q = SignalQualityScale.fromRssi(-70)
        assertTrue(q.label.isNotBlank())
        assertTrue(q.accessibleLabel.contains(q.label))
    }

    /* ---------------- 12.4 Poll-Gate ---------------- */

    @Test
    fun `poll allowed only when all prerequisites are met`() {
        assertEquals(
            PollDecision.ALLOWED,
            PollGate.evaluate(true, true, true, true)
        )
        assertEquals(
            PollDecision.BLOCKED_NO_WRITE_CHAR,
            PollGate.evaluate(true, false, true, true)
        )
        assertEquals(
            PollDecision.BLOCKED_UNKNOWN_MODEL,
            PollGate.evaluate(true, true, false, true)
        )
        assertEquals(
            PollDecision.BLOCKED_UNSUPPORTED,
            PollGate.evaluate(true, true, true, false)
        )
        assertEquals(
            PollDecision.BLOCKED_NOT_READY,
            PollGate.evaluate(false, true, true, true)
        )
    }

    @Test
    fun `blocked poll decisions have a reason text`() {
        PollDecision.values().forEach { d ->
            if (d == PollDecision.ALLOWED) {
                assertNull(PollGate.reason(d))
            } else {
                assertNotNull("Grund fuer $d fehlt", PollGate.reason(d))
                assertTrue(PollGate.reason(d)!!.isNotBlank())
            }
        }
    }

    @Test
    fun `shouldPoll mirrors evaluate`() {
        assertTrue(PollGate.shouldPoll(true, true, true, true))
        assertFalse(PollGate.shouldPoll(true, false, true, true))
    }

    /* ---------------- 12.1 Timer ---------------- */

    @Test
    fun `trip clock formats hours minutes seconds`() {
        assertEquals("00:00:00", TripClock.format(0L))
        assertEquals("00:00:59", TripClock.format(59_000L))
        assertEquals("00:01:00", TripClock.format(60_000L))
        assertEquals("01:00:00", TripClock.format(3_600_000L))
        assertEquals("02:03:04", TripClock.format((2 * 3600 + 3 * 60 + 4) * 1000L))
    }

    @Test
    fun `trip clock handles negative input defensively`() {
        assertEquals("00:00:00", TripClock.format(-5_000L))
    }

    /* ---------------- 12.7 Diagnose-Historie ---------------- */

    @Test
    fun `history codec round trips entries`() {
        val entries = listOf(
            DiagnosticHistoryEntry(1_700_000_000_000L, "NAVEE GT3 Max", "DIAGNOSE", 1, 2, "Frames 10"),
            DiagnosticHistoryEntry(1_700_000_100_000L, "unbekannt", "DISCOVERED", 0, 0, "Frames 0 | MTU 23")
        )
        val encoded = DiagnosticHistoryCodec.encode(entries)
        val decoded = DiagnosticHistoryCodec.decode(encoded)
        assertEquals(entries, decoded)
    }

    @Test
    fun `history codec escapes separator and newlines`() {
        val entry = DiagnosticHistoryEntry(1L, "A|B", "C\nD", 3, 4, "x\\y")
        val decoded = DiagnosticHistoryCodec.decode(DiagnosticHistoryCodec.encode(listOf(entry)))
        assertEquals(1, decoded.size)
        assertEquals("A|B", decoded[0].model)
        assertEquals("C\nD", decoded[0].supportStatus)
        assertEquals("x\\y", decoded[0].summary)
    }

    @Test
    fun `history codec tolerates empty and corrupt input`() {
        assertTrue(DiagnosticHistoryCodec.decode(null).isEmpty())
        assertTrue(DiagnosticHistoryCodec.decode("").isEmpty())
        assertTrue(DiagnosticHistoryCodec.decode("kaputt").isEmpty())
        assertTrue(DiagnosticHistoryCodec.decode("keine-zahl|a|b|1|2|c").isEmpty())
    }

    @Test
    fun `compare reports deltas`() {
        val old = DiagnosticHistoryEntry(1L, "A", "DISCOVERED", 2, 1, "")
        val new = DiagnosticHistoryEntry(2L, "A", "DIAGNOSE", 0, 3, "")
        val text = DiagnosticHistoryCodec.compare(old, new)
        assertTrue(text.contains("Fehler: 2 -> 0"))
        assertTrue(text.contains("-2"))
        assertTrue(text.contains("Warnungen: 1 -> 3"))
        assertTrue(text.contains("+2"))
    }

    /* ---------------- 12.7 Systemstatus ---------------- */

    @Test
    fun `system status has seven rows and reflects input`() {
        val rows = SystemStatus.build(
            SystemStatusInput(
                bluetoothAvailable = true,
                permissionsGranted = false,
                connected = true,
                gattServiceKnown = true,
                protocolVerified = false,
                telemetryAvailable = true,
                diagnosticsPossible = true
            )
        )
        assertEquals(7, rows.size)
        assertTrue(rows.first { it.label.startsWith("Bluetooth") }.ok)
        assertFalse(rows.first { it.label.startsWith("Berechtigungen") }.ok)
        assertFalse(rows.first { it.label.startsWith("Protokoll") }.ok)
    }

    @Test
    fun `system status rows carry a textual state word`() {
        val rows = SystemStatus.build(
            SystemStatusInput(false, false, false, false, false, false, false)
        )
        rows.forEach {
            assertEquals("OFFEN", it.stateWord)
            assertTrue(it.detail.isNotBlank())
        }
    }
}
