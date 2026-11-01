package com.syrak.scooterlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 16.3 — Segway F3 Pro: BLE-Notify-Kette & Telemetrie-Empfang.
 *
 * Diese Tests belegen die NEUEN Bausteine der Notify-Aktivierungs-Diagnose:
 *   • [TelemetryDiagnostics.recordDiscovery] / [recordNotifySetup] /
 *     [recordCccdResult] — vollstaendige Aktivierungs-Kette.
 *   • [TelemetryDiagnosis.cause] — unterscheidet die SECHS geforderten
 *     Fehlerzustaende plus "dekodiert (UNVERIFIED)".
 *   • [TelemetryDiagnosticsSnapshot.summary] — kennzeichnet einen nur
 *     angenommenen Adapter ehrlich mit "(angenommen)".
 *   • [GattCharacteristicSelector] — Notify-Auswahl waehlt nie ein
 *     nicht-notify-faehiges Characteristic.
 *
 * EHRLICHKEITSREGEL: Diese Tests belegen NUR die Software-Logik. Sie sind
 * KEIN Nachweis, dass ein echter Segway F3 Pro Telemetrie liefert. Dafuer ist
 * ein echter Mitschnitt noetig (weiterhin UNVERIFIED).
 */
class Phase163SegwayNotifyTest {

    /* ================== Notify-Kette: Zaehler & Reset ================== */

    @Test
    fun `recordDiscovery stores discovery state and adapter evidence`() {
        val d = TelemetryDiagnostics()
        d.recordDiscovery(adapterConfirmed = false)
        val s = d.snapshot("ninebot-generic", 0, false, true)
        assertTrue(s.discoveryDone)
        assertFalse(s.adapterConfirmed)
    }

    @Test
    fun `recordNotifySetup stores full chain`() {
        val d = TelemetryDiagnostics()
        d.recordDiscovery(true)
        d.recordNotifySetup(
            notifyCharFound = true,
            notifyCharUuid = "0000ffe1-0000-1000-8000-00805f9b34fb",
            cccdPresent = true,
            cccdWriteRequested = true,
            cccdWriteAccepted = true,
            issue = null
        )
        val s = d.snapshot("navee-gt3max", 0, false, true)
        assertTrue(s.notifyCharFound)
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb", s.notifyCharUuid)
        assertTrue(s.cccdPresent)
        assertTrue(s.cccdWriteRequested)
        assertTrue(s.cccdWriteAccepted)
        assertNull(s.cccdWriteStatus)
        assertNull(s.notifySetupIssue)
    }

    @Test
    fun `recordCccdResult stores descriptor write status`() {
        val d = TelemetryDiagnostics()
        d.recordDiscovery(true)
        d.recordCccdResult(TelemetryDiagnostics.GATT_SUCCESS)
        val s = d.snapshot("x", 0, true, true)
        assertEquals(TelemetryDiagnostics.GATT_SUCCESS, s.cccdWriteStatus)
    }

    @Test
    fun `reset clears all phase 16_3 fields`() {
        val d = TelemetryDiagnostics()
        d.recordDiscovery(true)
        d.recordNotifySetup(true, "uuid", true, true, true, "issue")
        d.recordCccdResult(133)
        d.reset()
        val s = d.snapshot("", 0, false, false)
        assertFalse(s.discoveryDone)
        assertFalse(s.adapterConfirmed)
        assertFalse(s.notifyCharFound)
        assertEquals("", s.notifyCharUuid)
        assertFalse(s.cccdPresent)
        assertFalse(s.cccdWriteRequested)
        assertFalse(s.cccdWriteAccepted)
        assertNull(s.cccdWriteStatus)
        assertNull(s.notifySetupIssue)
    }

    /* ============ Ursachen-Logik: die sechs geforderten Zustaende ============ */

    @Test
    fun `state 1 - no notify characteristic found`() {
        val s = TelemetryDiagnosticsSnapshot(
            connected = true, discoveryDone = true, notifyCharFound = false
        )
        assertTrue(TelemetryDiagnosis.cause(s).contains("Keine Notify-/Indicate-Characteristic"))
    }

    @Test
    fun `state 1b - notify capable but none selected`() {
        val s = TelemetryDiagnosticsSnapshot(
            connected = true, discoveryDone = true,
            notifyCharFound = true, notifyCharUuid = ""
        )
        assertTrue(TelemetryDiagnosis.cause(s).contains("keine ausgewaehlt"))
    }

    @Test
    fun `state 2 - cccd missing`() {
        val s = TelemetryDiagnosticsSnapshot(
            connected = true, discoveryDone = true,
            notifyCharFound = true, notifyCharUuid = "uuid-1",
            cccdPresent = false
        )
        assertTrue(TelemetryDiagnosis.cause(s).contains("CCCD-Deskriptor (0x2902) fehlt"))
    }

    @Test
    fun `state 3a - cccd write rejected by stack`() {
        val s = TelemetryDiagnosticsSnapshot(
            connected = true, discoveryDone = true,
            notifyCharFound = true, notifyCharUuid = "uuid-1",
            cccdPresent = true, cccdWriteRequested = true, cccdWriteAccepted = false
        )
        assertTrue(TelemetryDiagnosis.cause(s).contains("vom Stack abgelehnt"))
    }

    @Test
    fun `state 3b - cccd activation failed with status`() {
        val s = TelemetryDiagnosticsSnapshot(
            connected = true, discoveryDone = true,
            notifyCharFound = true, notifyCharUuid = "uuid-1",
            cccdPresent = true, cccdWriteRequested = true, cccdWriteAccepted = true,
            cccdWriteStatus = 133
        )
        assertTrue(TelemetryDiagnosis.cause(s).contains("CCCD-Aktivierung fehlgeschlagen"))
        assertTrue(TelemetryDiagnosis.cause(s).contains("status=133"))
    }

    @Test
    fun `state 3c - cccd requested but not confirmed`() {
        val s = TelemetryDiagnosticsSnapshot(
            connected = true, discoveryDone = true,
            notifyCharFound = true, notifyCharUuid = "uuid-1",
            cccdPresent = true, cccdWriteRequested = true, cccdWriteAccepted = true,
            cccdWriteStatus = null, notifyActive = false
        )
        assertTrue(TelemetryDiagnosis.cause(s).contains("noch nicht bestaetigt"))
    }

    @Test
    fun `state 4 - notify active but no data`() {
        val s = TelemetryDiagnosticsSnapshot(
            connected = true, discoveryDone = true,
            notifyCharFound = true, notifyCharUuid = "uuid-1",
            cccdPresent = true, cccdWriteRequested = true, cccdWriteAccepted = true,
            cccdWriteStatus = TelemetryDiagnostics.GATT_SUCCESS,
            notifyActive = true, notifications = 0
        )
        val c = TelemetryDiagnosis.cause(s)
        assertTrue(c.contains("Notify aktiviert, aber keine Daten"))
        assertTrue(c.contains("keine Notification"))
    }

    @Test
    fun `state 5a - data received but no valid header`() {
        val s = TelemetryDiagnosticsSnapshot(
            connected = true, discoveryDone = true,
            notifyCharFound = true, notifyCharUuid = "uuid-1",
            cccdPresent = true, cccdWriteRequested = true, cccdWriteAccepted = true,
            cccdWriteStatus = 0, notifyActive = true,
            notifications = 4, framesDetected = 0
        )
        assertTrue(TelemetryDiagnosis.cause(s).contains("kein gueltiger Frame-Header"))
    }

    @Test
    fun `state 5b - data received but all frames invalid`() {
        val s = TelemetryDiagnosticsSnapshot(
            connected = true, discoveryDone = true,
            notifyCharFound = true, notifyCharUuid = "uuid-1",
            cccdPresent = true, cccdWriteRequested = true, cccdWriteAccepted = true,
            cccdWriteStatus = 0, notifyActive = true,
            notifications = 4, framesDetected = 3, invalidFrames = 3, decodeSuccess = 0
        )
        assertTrue(TelemetryDiagnosis.cause(s).contains("alle Frames ungueltig"))
    }

    @Test
    fun `state 6 - frames detected but decoder failed`() {
        val s = TelemetryDiagnosticsSnapshot(
            connected = true, discoveryDone = true,
            notifyCharFound = true, notifyCharUuid = "uuid-1",
            cccdPresent = true, cccdWriteRequested = true, cccdWriteAccepted = true,
            cccdWriteStatus = 0, notifyActive = true,
            notifications = 4, framesDetected = 3, invalidFrames = 0,
            decodeSuccess = 0, decodeFailure = 3
        )
        assertTrue(TelemetryDiagnosis.cause(s).contains("Dekodierung fehlgeschlagen"))
    }

    @Test
    fun `state 7 - decoded but unverified`() {
        val s = TelemetryDiagnosticsSnapshot(
            connected = true, discoveryDone = true,
            notifyCharFound = true, notifyCharUuid = "uuid-1",
            cccdPresent = true, cccdWriteRequested = true, cccdWriteAccepted = true,
            cccdWriteStatus = 0, notifyActive = true,
            notifications = 4, framesDetected = 3, decodeSuccess = 3
        )
        assertTrue(TelemetryDiagnosis.cause(s).contains("UNVERIFIED"))
    }

    /* ================== Ehrlichkeit: Adapter "angenommen" ================== */

    @Test
    fun `summary marks assumed adapter honestly`() {
        val d = TelemetryDiagnostics()
        d.recordDiscovery(adapterConfirmed = false)
        val s = d.snapshot("ninebot-generic", 0, false, true)
        assertTrue(s.summary().contains("(angenommen)"))
    }

    @Test
    fun `summary does not mark confirmed adapter`() {
        val d = TelemetryDiagnostics()
        d.recordDiscovery(adapterConfirmed = true)
        val s = d.snapshot("ninebot-generic", 0, false, true)
        assertFalse(s.summary().contains("(angenommen)"))
    }

    /* ================== Notify-Auswahl (Regression) ================== */

    @Test
    fun `pickNotify never selects non-notifiable characteristic`() {
        val candidates = listOf(
            GattCharacteristicSelector.Candidate("write-only", writable = true, notifiable = false),
            GattCharacteristicSelector.Candidate("notify-char", writable = false, notifiable = true),
        )
        val picked = GattCharacteristicSelector.pickNotify(candidates, listOf("write-only"))
        assertEquals("notify-char", picked?.uuid)
    }

    @Test
    fun `pickNotify prefers preferred uuid when notifiable`() {
        val candidates = listOf(
            GattCharacteristicSelector.Candidate("a", writable = false, notifiable = true),
            GattCharacteristicSelector.Candidate("b", writable = false, notifiable = true),
        )
        val picked = GattCharacteristicSelector.pickNotify(candidates, listOf("b"))
        assertEquals("b", picked?.uuid)
    }

    @Test
    fun `pickNotify returns null when nothing notifiable`() {
        val candidates = listOf(
            GattCharacteristicSelector.Candidate("w", writable = true, notifiable = false),
        )
        assertNull(GattCharacteristicSelector.pickNotify(candidates, listOf("w")))
    }

    /* ================== Regression: Phase-16.2-Vertraeglichkeit ================== */

    @Test
    fun `legacy default snapshot still reports no notifications`() {
        // Ein reiner Default-Schnappschuss (ohne Discovery) muss weiterhin die
        // Phase-16.2-Texte liefern — die 16.3-Zweige greifen erst bei discoveryDone.
        val s = TelemetryDiagnosticsSnapshot(connected = true, notifications = 0)
        assertTrue(TelemetryDiagnosis.cause(s).contains("keine Notification"))
    }

    @Test
    fun `legacy counters still accumulate`() {
        val d = TelemetryDiagnostics()
        d.recordChunk(20, 1000)
        d.recordChunk(30, 1100)
        d.recordFrame(true)
        d.recordDecode(true)
        val s = d.snapshot("navee-gt3max", 5, true, true)
        assertEquals(2L, s.notifications)
        assertEquals(50L, s.bytes)
        assertEquals(1100L, s.lastRxAtMs)
        assertEquals(1L, s.framesDetected)
        assertEquals(1L, s.decodeSuccess)
    }
}
