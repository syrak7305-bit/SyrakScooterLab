package com.syrak.scooterlab

import com.syrak.scooterlab.diagnostics.ConnectionRecord
import com.syrak.scooterlab.diagnostics.FrameDirection
import com.syrak.scooterlab.diagnostics.FrameRecord
import com.syrak.scooterlab.diagnostics.HardwareTestCollector
import com.syrak.scooterlab.diagnostics.HardwareTestReport
import com.syrak.scooterlab.diagnostics.NotifyDiagnosticsRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 16.3.4 — Tests fuer BLE-Beobachtbarkeit und Fehlerdiagnose.
 *
 * Deckt die sechs Teilziele ab:
 *  A1 UUID je Notification (Telemetrie + Bericht + Frame)
 *  A2 Disconnect-Statuscode im Bericht
 *  A3 Diagnosezaehler ueberleben einen Disconnect (Snapshot ist Kopie)
 *  A4 Geraetename und Modell getrennt
 *  A5 Scan-Klassifizierung mit vollstaendiger ScanObservation
 *  A6 Ninebot-Notify-Praeferenz: Write-only-UUID wird nie als Notify gewaehlt
 */
class Phase1634ObservabilityTest {

    private val uuidA = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
    private val uuidB = "6e400004-0000-0000-006e-696e65626f74"

    private fun report(
        connection: ConnectionRecord = ConnectionRecord(),
        notify: NotifyDiagnosticsRecord? = null,
        frames: List<FrameRecord> = emptyList()
    ) = HardwareTestReport(
        generatedAtEpochMs = 1_700_000_000_000L,
        appVersion = "test",
        deviceName = "F3 Pro",
        address = "AA:BB:CC:DD:EE:FF",
        manufacturer = "Ninebot",
        detectedModel = "unbekannt",
        confidence = "unbekannt",
        supportStatus = "Nur Diagnose",
        readOnly = true,
        protocol = "Ninebot",
        adapterId = "ninebot",
        connection = connection,
        notifyDiagnostics = notify,
        frames = frames
    )

    /* ----------------------------- A1 ----------------------------- */

    @Test
    fun `recordChunk zaehlt Empfang je Characteristic-UUID`() {
        val d = TelemetryDiagnostics()
        d.recordChunk(10, 1_000L, uuidA)
        d.recordChunk(5, 1_100L, uuidA)
        d.recordChunk(3, 1_200L, uuidB)

        val s = d.snapshot("adapter", bufferedBytes = 0, notifyActive = true, connected = true)
        assertEquals(3L, s.notifications)
        assertEquals(18L, s.bytes)
        assertEquals(mapOf(uuidA to 2L, uuidB to 1L), s.notificationsByUuid)
        assertTrue("Summary muss rx/Char enthalten", s.summary().contains("rx/Char"))
        assertTrue(s.summary().contains("$uuidA=2"))
    }

    @Test
    fun `recordChunk ohne UUID zaehlt nicht in die UUID-Karte`() {
        val d = TelemetryDiagnostics()
        d.recordChunk(4, 1L)
        val s = d.snapshot("adapter", 0, false, true)
        assertEquals(1L, s.notifications)
        assertTrue(s.notificationsByUuid.isEmpty())
    }

    @Test
    fun `reset leert die UUID-Zaehler`() {
        val d = TelemetryDiagnostics()
        d.recordChunk(4, 1L, uuidA)
        d.reset()
        val s = d.snapshot("adapter", 0, false, true)
        assertEquals(0L, s.notifications)
        assertTrue(s.notificationsByUuid.isEmpty())
    }

    @Test
    fun `NotifyDiagnosticsRecord gibt UUID-Zaehler in JSON CSV und Summary aus`() {
        val rec = NotifyDiagnosticsRecord(
            selectedNotifyCharUuid = uuidA,
            notificationsReceived = 3,
            notificationsByUuid = mapOf(uuidA to 2L, uuidB to 1L)
        )
        val r = report(notify = rec)
        val json = r.toJson()
        assertTrue(json.contains("\"notificationsByUuid\""))
        assertTrue(json.contains("\"$uuidA\": 2"))
        assertTrue(json.contains("\"$uuidB\": 1"))

        val csv = r.toCsv()
        assertTrue(csv.contains("notify,notificationsByUuid:$uuidA,2"))
        assertTrue(csv.contains("notify,notificationsByUuid:$uuidB,1"))

        val summary = r.toSummary()
        assertTrue(summary.contains("Notify je UUID"))
    }

    @Test
    fun `FrameRecord traegt die Quell-UUID und serialisiert sie`() {
        val collector = HardwareTestCollector()
        collector.recordFrame(FrameDirection.RX, "AABB", 100L, "notification", uuidA)
        val frame = collector.frames().single()
        assertEquals(uuidA, frame.characteristicUuid)

        val r = report(frames = collector.frames())
        assertTrue(r.toJson().contains("\"characteristicUuid\": \"$uuidA\""))
        assertTrue(r.toCsv().contains("|$uuidA"))
    }

    @Test
    fun `FrameRecord ohne UUID bleibt null`() {
        val collector = HardwareTestCollector()
        collector.recordFrame(FrameDirection.TX, "CCDD", 100L, "write")
        assertNull(collector.frames().single().characteristicUuid)
    }

    /* ----------------------------- A2 ----------------------------- */

    @Test
    fun `Disconnect-Status wird in JSON CSV und Summary ausgegeben`() {
        val r = report(
            connection = ConnectionRecord(
                disconnectedAtMs = 5_000L,
                disconnectUnexpected = true,
                disconnectStatus = 8
            )
        )
        assertTrue(r.toJson().contains("\"disconnectStatus\": 8"))
        assertTrue(r.toCsv().contains("connection,disconnectStatus,8"))
        assertTrue(r.toSummary().contains("Status=8"))
    }

    @Test
    fun `Disconnect-Status null wird als null serialisiert`() {
        val r = report(connection = ConnectionRecord())
        assertTrue(r.toJson().contains("\"disconnectStatus\": null"))
    }

    /* ----------------------------- A3 ----------------------------- */

    @Test
    fun `Snapshot ist eine Kopie und ueberlebt einen reset`() {
        val d = TelemetryDiagnostics()
        d.recordChunk(4, 1L, uuidA)
        val before = d.snapshot("adapter", 0, true, true)
        // Simuliert den Disconnect: Zaehler werden fuer den Bericht bewahrt,
        // der Live-Zustand wird erst beim NAECHSTEN Verbindungsaufbau geleert.
        d.reset()
        val after = d.snapshot("adapter", 0, false, false)

        assertEquals(1L, before.notifications)
        assertEquals(mapOf(uuidA to 1L), before.notificationsByUuid)
        assertEquals(0L, after.notifications)
        assertTrue(after.notificationsByUuid.isEmpty())
    }

    /* ----------------------------- A4 ----------------------------- */

    @Test
    fun `deviceName faellt ehrlich auf keinen Namen zurueck`() {
        assertEquals(DeviceDisplayLabels.NO_NAME, DeviceDisplayLabels.deviceName(null))
        assertEquals(DeviceDisplayLabels.NO_NAME, DeviceDisplayLabels.deviceName("   "))
        assertEquals("F3 Pro", DeviceDisplayLabels.deviceName("  F3 Pro "))
    }

    @Test
    fun `modelLabel trennt Modell und Sicherheit und raet nicht`() {
        val unknown = DeviceDisplayLabels.modelLabel(ScooterModel.UNKNOWN, DetectionConfidence.UNKNOWN)
        assertTrue(unknown.startsWith("Modell unbekannt"))
        assertTrue(unknown.contains(DetectionConfidence.UNKNOWN.label))

        val known = DeviceDisplayLabels.modelLabel(
            ScooterModel.NINEBOT_MAX_G30, DetectionConfidence.CONFIRMED
        )
        assertTrue(known.contains(ScooterModel.NINEBOT_MAX_G30.displayName))
        assertTrue(known.contains(DetectionConfidence.CONFIRMED.label))
        assertNotEquals(unknown, known)
    }

    /* ----------------------------- A5 ----------------------------- */

    @Test
    fun `vollstaendige Beobachtung klassifiziert besser als der Name allein`() {
        // Neutraler Name, aber starkes Advertisement (Ninebot-Transport e003).
        val obs = ScanObservation(
            address = "AA:BB:CC:DD:EE:FF",
            gattDeviceName = null,
            advertisedName = null,
            serviceUuids = listOf(BleUuids.NINEBOT_SERVICE),
            rssi = -60,
            timestampMs = 1L
        )
        val nameOnly = ScanClassifier.classify("Device-123", obs.address)
        val full = ScanClassifier.classify(obs)

        assertEquals(ScanCategory.UNKNOWN_DEVICE, nameOnly.category)
        assertEquals(ScanCategory.PROBABLE_SCOOTER, full.category)
        assertTrue(full.isScooterLike)
    }

    @Test
    fun `ohne jeden Hinweis bleibt es UNKNOWN`() {
        val obs = ScanObservation(
            address = "AA:BB:CC:DD:EE:FF",
            gattDeviceName = "Kopfhoerer",
            advertisedName = null,
            rssi = -70,
            timestampMs = 1L
        )
        assertEquals(ScanCategory.UNKNOWN_DEVICE, ScanClassifier.classify(obs).category)
    }

    /* ----------------------------- A6 ----------------------------- */

    @Test
    fun `write-only UUID wird nie als Notify gewaehlt`() {
        // Ninebot-Praeferenz enthaelt FFF1/FFF2 (Write). FFF1 ist hier NICHT
        // notifiable; die einzige notifiable Char ist eine andere UUID.
        val fff1 = BleUuids.NINEBOT_CHAR_WRITE
        val fff2 = BleUuids.NINEBOT_CHAR_WRITE_ALT
        val notifyOnly = uuidA
        val candidates = listOf(
            GattCharacteristicSelector.Candidate(fff1, writable = true, notifiable = false),
            GattCharacteristicSelector.Candidate(fff2, writable = true, notifiable = false),
            GattCharacteristicSelector.Candidate(notifyOnly, writable = false, notifiable = true)
        )
        val notify = GattCharacteristicSelector.pickNotify(candidates, listOf(fff1, fff2))
        assertEquals(notifyOnly, notify?.uuid)
        assertNotEquals(fff1, notify?.uuid)
        assertNotEquals(fff2, notify?.uuid)
    }

    @Test
    fun `notifiable bevorzugte UUID gewinnt`() {
        // FFF1 traegt hier tatsaechlich Notify (dual-role) -> darf gewaehlt werden.
        val fff1 = BleUuids.NINEBOT_CHAR_WRITE
        val other = uuidA
        val candidates = listOf(
            GattCharacteristicSelector.Candidate(other, writable = false, notifiable = true),
            GattCharacteristicSelector.Candidate(fff1, writable = true, notifiable = true)
        )
        val notify = GattCharacteristicSelector.pickNotify(candidates, listOf(fff1))
        assertEquals(fff1, notify?.uuid)
    }
}
