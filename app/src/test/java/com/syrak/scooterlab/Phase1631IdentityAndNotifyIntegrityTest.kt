package com.syrak.scooterlab

import com.syrak.scooterlab.diagnostics.DeviceIdentityRecord
import com.syrak.scooterlab.diagnostics.GattServiceRecord
import com.syrak.scooterlab.diagnostics.HardwareReportIdentity
import com.syrak.scooterlab.diagnostics.HardwareTestReport
import com.syrak.scooterlab.diagnostics.NotifyDiagnosticsRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 16.3.1 \u2014 Geraeteidentitaet & Notify-Integritaet.
 *
 * Belegt die neun geforderten Szenarien:
 *  1. fehlende aktive Geraeteidentitaet
 *  2. Unterschied Scan-Geraet vs. verbundenes Geraet
 *  3. veraltete GATT-Daten nach Disconnect
 *  4. Reconnect auf ein anderes Geraet
 *  5. lokale Notify-Registrierung fehlgeschlagen
 *  6. CCCD-Write abgelehnt
 *  7. Descriptor-Callback mit Fehlerstatus
 *  8. erfolgreicher Descriptor-Callback ohne Notifications
 *  9. Notifications ohne gueltige Frames
 *
 * Zusaetzlich: das erweiterte Berichtsschema (Identitaet + Notify-Diagnose)
 * bleibt rueckwaertskompatibel (schemaVersion unveraendert).
 */
class Phase1631IdentityAndNotifyIntegrityTest {

    private val knownUuid = "0000ffe1-0000-1000-8000-00805f9b34fb"

    private fun report(
        identity: DeviceIdentityRecord? = null,
        notify: NotifyDiagnosticsRecord? = null,
        services: List<GattServiceRecord> = emptyList()
    ) = HardwareTestReport(
        generatedAtEpochMs = 1_700_000_000_000L,
        appVersion = "1.0-test",
        deviceName = identity?.activeName,
        address = identity?.activeAddress ?: "\u2014",
        manufacturer = "unbekannt",
        detectedModel = "unbekannt",
        confidence = "OFFEN",
        supportStatus = "OFFEN",
        readOnly = true,
        protocol = "",
        adapterId = "",
        identity = identity,
        notifyDiagnostics = notify,
        services = services
    )

    // ---- 1) fehlende aktive Identitaet -------------------------------------
    @Test
    fun `missing active identity is reported as unknown`() {
        val r = HardwareReportIdentity.resolve(
            HardwareReportIdentity.Inputs(
                activeAddress = null,
                activeName = null,
                connected = false,
                lastUsedAddress = "AA:BB:CC:DD:EE:FF",
                lastUsedName = "Letzter Scooter",
                cachedGattAddress = null,
                hasCachedGatt = false
            )
        )
        assertNull(r.deviceName)
        assertEquals("\u2014", r.address)
        assertFalse(r.identity.activeConnection)
        assertNull(r.identity.activeAddress)
        // zuletzt genutzt bleibt getrennt erhalten
        assertEquals("AA:BB:CC:DD:EE:FF", r.identity.lastUsedAddress)
        assertEquals("Letzter Scooter", r.identity.lastUsedName)
        assertFalse(r.useCachedGatt)
    }

    // ---- 2) Scan-Geraet != verbundenes Geraet ------------------------------
    @Test
    fun `scan hit is never reported as the active connection`() {
        val r = HardwareReportIdentity.resolve(
            HardwareReportIdentity.Inputs(
                activeAddress = "C1:6B:5E:D7:87:87",
                activeName = "1EFHG2604C1296",
                connected = true,
                lastUsedAddress = "11:22:33:44:55:66",
                lastUsedName = "Anderer Scan-Treffer",
                cachedGattAddress = null,
                hasCachedGatt = false
            )
        )
        assertEquals("C1:6B:5E:D7:87:87", r.address)
        assertEquals("1EFHG2604C1296", r.deviceName)
        assertTrue(r.identity.activeConnection)
        // Der Scan-/zuletzt-Treffer taucht NUR als lastUsed auf, nie als aktiv.
        assertEquals("11:22:33:44:55:66", r.identity.lastUsedAddress)
    }

    // ---- 3) veraltete GATT-Daten nach Disconnect ---------------------------
    @Test
    fun `stale gatt is not attached to a disconnected session`() {
        val r = HardwareReportIdentity.resolve(
            HardwareReportIdentity.Inputs(
                activeAddress = null,
                activeName = null,
                connected = false,
                lastUsedAddress = "C1:6B:5E:D7:87:87",
                lastUsedName = "1EFHG2604C1296",
                cachedGattAddress = "C1:6B:5E:D7:87:87",
                hasCachedGatt = true
            )
        )
        assertFalse(r.useCachedGatt)
        assertEquals("\u2014", r.address)
    }

    // ---- 4) Reconnect auf ein anderes Geraet -------------------------------
    @Test
    fun `reconnect to a different device drops the old gatt`() {
        val other = HardwareReportIdentity.resolve(
            HardwareReportIdentity.Inputs(
                activeAddress = "B",
                activeName = "Neu",
                connected = true,
                lastUsedAddress = "A",
                lastUsedName = "Alt",
                cachedGattAddress = "A",
                hasCachedGatt = true
            )
        )
        assertFalse(other.useCachedGatt)
        assertEquals("B", other.address)

        val same = HardwareReportIdentity.resolve(
            HardwareReportIdentity.Inputs(
                activeAddress = "B",
                activeName = "Neu",
                connected = true,
                lastUsedAddress = "A",
                lastUsedName = "Alt",
                cachedGattAddress = "B",
                hasCachedGatt = true
            )
        )
        assertTrue(same.useCachedGatt)
    }

    // ---- 5) lokale Notify-Registrierung fehlgeschlagen ---------------------
    @Test
    fun `local notify registration failure is diagnosed`() {
        val d = TelemetryDiagnostics()
        d.recordDiscovery(adapterConfirmed = false)
        d.recordNotifySetup(
            notifyCharFound = true,
            notifyCharUuid = knownUuid,
            cccdPresent = true,
            cccdWriteRequested = true,
            cccdWriteAccepted = true,
            issue = null,
            localNotifyRegistrationAttempted = true,
            localNotifyRegistered = false
        )
        val s = d.snapshot("ninebot-generic", 0, notifyActive = false, connected = true)
        val cause = TelemetryDiagnosis.cause(s)
        assertTrue(cause.contains("Lokale Notify-Registrierung fehlgeschlagen"))
        assertFalse(s.notifyChainComplete)
    }

    // ---- 6) CCCD-Write abgelehnt ------------------------------------------
    @Test
    fun `cccd write rejected is diagnosed`() {
        val d = TelemetryDiagnostics()
        d.recordDiscovery(adapterConfirmed = false)
        d.recordNotifySetup(
            notifyCharFound = true,
            notifyCharUuid = knownUuid,
            cccdPresent = true,
            cccdWriteRequested = true,
            cccdWriteAccepted = false,
            issue = null,
            localNotifyRegistrationAttempted = true,
            localNotifyRegistered = true
        )
        val s = d.snapshot("ninebot-generic", 0, notifyActive = false, connected = true)
        assertTrue(TelemetryDiagnosis.cause(s).contains("vom Stack abgelehnt"))
    }

    // ---- 7) Descriptor-Callback mit Fehlerstatus --------------------------
    @Test
    fun `descriptor callback error status is diagnosed`() {
        val d = TelemetryDiagnostics()
        d.recordDiscovery(adapterConfirmed = false)
        d.recordNotifySetup(
            notifyCharFound = true,
            notifyCharUuid = knownUuid,
            cccdPresent = true,
            cccdWriteRequested = true,
            cccdWriteAccepted = true,
            issue = null,
            localNotifyRegistrationAttempted = true,
            localNotifyRegistered = true
        )
        d.recordCccdResult(133)
        val s = d.snapshot("ninebot-generic", 0, notifyActive = false, connected = true)
        val cause = TelemetryDiagnosis.cause(s)
        assertTrue(cause.contains("CCCD-Aktivierung fehlgeschlagen"))
        assertTrue(cause.contains("status=133"))
        assertTrue(s.descriptorCallbackReceived)
        assertFalse(s.descriptorCallbackSuccess)
    }

    // ---- 8) erfolgreicher Descriptor-Callback ohne Notifications ----------
    @Test
    fun `successful descriptor callback without notifications is diagnosed`() {
        val d = TelemetryDiagnostics()
        d.recordDiscovery(adapterConfirmed = false)
        d.recordNotifySetup(
            notifyCharFound = true,
            notifyCharUuid = knownUuid,
            cccdPresent = true,
            cccdWriteRequested = true,
            cccdWriteAccepted = true,
            issue = null,
            localNotifyRegistrationAttempted = true,
            localNotifyRegistered = true
        )
        d.recordCccdResult(0)
        val s = d.snapshot("ninebot-generic", 0, notifyActive = true, connected = true)
        assertTrue(s.notifyChainComplete)
        assertTrue(s.descriptorCallbackSuccess)
        assertTrue(TelemetryDiagnosis.cause(s).contains("keine Notification"))
    }

    // ---- 9) Notifications ohne gueltige Frames ----------------------------
    @Test
    fun `notifications without valid frames are diagnosed`() {
        val d = TelemetryDiagnostics()
        d.recordDiscovery(adapterConfirmed = false)
        d.recordNotifySetup(
            notifyCharFound = true,
            notifyCharUuid = knownUuid,
            cccdPresent = true,
            cccdWriteRequested = true,
            cccdWriteAccepted = true,
            issue = null,
            localNotifyRegistrationAttempted = true,
            localNotifyRegistered = true
        )
        d.recordCccdResult(0)
        repeat(3) { d.recordChunk(20, 1_000L + it) }
        val s = d.snapshot("ninebot-generic", 0, notifyActive = true, connected = true)
        assertEquals(3L, s.notifications)
        assertEquals(0L, s.framesDetected)
        assertTrue(TelemetryDiagnosis.cause(s).contains("kein gueltiger Frame-Header"))
    }

    // ---- notifyCharFound = ausgewaehlte, nicht "irgendeine" ---------------
    @Test
    fun `notifyCharFound reflects the selected characteristic`() {
        val d = TelemetryDiagnostics()
        d.recordDiscovery(adapterConfirmed = false)
        d.recordNotifySetup(
            notifyCharFound = false,
            notifyCharUuid = "",
            cccdPresent = false,
            cccdWriteRequested = false,
            cccdWriteAccepted = false,
            issue = "keine Notify-Char ausgewaehlt",
            localNotifyRegistrationAttempted = false,
            localNotifyRegistered = false,
            anyNotifiablePresent = true
        )
        val s = d.snapshot("ninebot-generic", 0, notifyActive = false, connected = true)
        assertFalse(s.notifyCharFound)
        assertTrue(s.anyNotifiablePresent)
        assertTrue(TelemetryDiagnosis.cause(s).contains("Keine Notify-/Indicate-Characteristic")
            || TelemetryDiagnosis.cause(s).contains("keine ausgewaehlt"))
    }

    // ---- Berichtsschema: rueckwaertskompatibel + additive Felder ----------
    @Test
    fun `report schema stays backward compatible and carries identity and notify`() {
        val identity = DeviceIdentityRecord(
            activeConnection = true,
            activeName = "1EFHG2604C1296",
            activeAddress = "C1:6B:5E:D7:87:87",
            lastUsedName = "1EFHG2604C1296",
            lastUsedAddress = "C1:6B:5E:D7:87:87"
        )
        val notify = NotifyDiagnosticsRecord(
            selectedNotifyCharUuid = knownUuid,
            selectedNotifyCharFound = true,
            anyNotifiablePresent = true,
            localRegistrationAttempted = true,
            localRegistrationSucceeded = true,
            cccdPresent = true,
            cccdWriteRequested = true,
            cccdWriteAccepted = true,
            descriptorCallbackReceived = true,
            descriptorCallbackSuccess = true,
            notificationsReceived = 4,
            notifyActive = true,
            issue = null
        )
        val r = report(identity, notify)
        val json = r.toJson()
        assertTrue(json.contains("\"schemaVersion\": 1"))
        assertTrue(json.contains("\"identity\""))
        assertTrue(json.contains("\"activeConnection\": true"))
        assertTrue(json.contains("\"notifyDiagnostics\""))
        assertTrue(json.contains("\"chainComplete\": true"))
        assertTrue(json.contains("\"localRegistrationSucceeded\": true"))
        assertTrue(json.contains("\"descriptorCallbackSuccess\": true"))

        val csv = r.toCsv()
        assertTrue(csv.contains("identity,activeAddress"))
        assertTrue(csv.contains("notify,chainComplete,true"))

        val summary = r.toSummary()
        assertTrue(summary.contains("Identitaet:"))
        assertTrue(summary.contains("Notify:"))
        assertTrue(summary.contains("Kette=vollstaendig"))
    }

    @Test
    fun `report without identity or notify stays valid`() {
        val r = report()
        val json = r.toJson()
        assertTrue(json.contains("\"identity\": null"))
        assertTrue(json.contains("\"notifyDiagnostics\": null"))
        assertEquals("\u2014", r.address)
        assertNull(r.deviceName)
    }
}
