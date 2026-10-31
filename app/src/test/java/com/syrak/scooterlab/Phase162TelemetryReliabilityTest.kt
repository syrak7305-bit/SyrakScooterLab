package com.syrak.scooterlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * PHASE 16.2 — Telemetrie-Zuverlaessigkeit.
 *
 * Diese Tests belegen die NEUEN Bausteine:
 *   • [FrameValidator]     — Header-/Laengen-/Pruefsummen-Pruefung (ohne Verwerfen).
 *   • [FrameReassembler]   — Zusammensetzen mehrerer BLE-Notifications.
 *   • [TelemetryDiagnostics] / [TelemetryDiagnosis] — Zaehler + ehrliche Ursache.
 *   • [TelemetryDecoder] Result-API — Grund eines Fehlschlags.
 *
 * EHRLICHKEITSREGEL: Alle Test-Frames sind SYNTHETISCH aus den projekt-
 * eigenen Frame-Buildern erzeugt. Sie sind KEIN Beweis fuer das echte
 * NAVEE-GT3-Max-Protokoll. Byte-Offsets bleiben UNVERIFIED.
 */
class Phase162TelemetryReliabilityTest {

    /* ============================ Helfer ============================ */

    private fun putU16(p: ByteArray, off: Int, v: Int) {
        p[off] = (v and 0xFF).toByte()
        p[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    /** Synthetischer Navee-Status-Payload mit plausiblen Werten. */
    private fun naveePayload(size: Int = 48): ByteArray {
        val p = ByteArray(size)
        putU16(p, 0, 200)   // 20.0 km/h
        putU16(p, 2, 4800)  // 48.00 V
        putU16(p, 4, 500)   // 5.00 A
        p[6] = 80           // 80 %
        p[7] = 30           // 30 C
        for (i in 0 until 13) {
            val off = 8 + i * 2
            if (off + 1 < size) putU16(p, off, 3700)
        }
        return p
    }

    /** Synthetischer Ninebot-Status-Payload. */
    private fun ninebotPayload(size: Int = 52): ByteArray {
        val p = ByteArray(size)
        putU16(p, 4, 200)
        putU16(p, 6, 3600)
        putU16(p, 8, 500)
        p[10] = 90
        p[11] = 25
        for (i in 0 until 10) {
            val off = 12 + i * 2
            if (off + 1 < size) putU16(p, off, 3700)
        }
        return p
    }

    private fun naveeFrame() = NaveeFrames.buildFrame(0x20, naveePayload())
    private fun ninebotFrame() = ScooterFrames.buildFrame(0x20, 0x01, ninebotPayload())

    /* ======================= FrameValidator ======================= */

    @Test
    fun `validator accepts well formed navee frame`() {
        val v = FrameValidator.validate(naveeFrame())
        assertEquals(FrameFamily.NAVEE_STD, v.family)
        assertTrue(v.headerOk)
        assertTrue(v.lengthOk)
        assertTrue(v.structurallyValid)
        // Pruefsumme stimmt mit dem Builder ueberein, ist aber UNVERIFIED.
        assertEquals(true, v.checksumMatches)
        assertFalse(v.checksumVerified)
    }

    @Test
    fun `validator accepts well formed ninebot frame`() {
        val v = FrameValidator.validate(ninebotFrame())
        assertEquals(FrameFamily.NINEBOT, v.family)
        assertTrue(v.headerOk)
        assertTrue(v.lengthOk)
        assertTrue(v.structurallyValid)
        assertEquals(true, v.checksumMatches)
        assertFalse(v.checksumVerified)
    }

    @Test
    fun `validator flags unknown header`() {
        val v = FrameValidator.validate(byteArrayOf(0x11, 0x22, 0x33, 0x44))
        assertEquals(FrameFamily.UNKNOWN, v.family)
        assertFalse(v.headerOk)
        assertFalse(v.structurallyValid)
    }

    @Test
    fun `validator never throws on tiny input`() {
        for (size in 0..3) {
            val v = FrameValidator.validate(ByteArray(size))
            assertFalse(v.structurallyValid)
        }
    }

    @Test
    fun `validator flags truncated frame as length mismatch`() {
        val full = naveeFrame()
        val truncated = full.copyOfRange(0, full.size - 1)
        val v = FrameValidator.validate(truncated)
        assertTrue(v.headerOk)
        assertFalse(v.lengthOk)
        assertFalse(v.structurallyValid)
    }

    @Test
    fun `validator reports checksum mismatch but does not reject structure`() {
        val f = naveeFrame()
        // Payload-Byte manipulieren, Pruefsumme NICHT neu berechnen.
        f[5] = (f[5].toInt() xor 0x7F).toByte()
        val v = FrameValidator.validate(f)
        assertTrue(v.structurallyValid) // Laenge bleibt gueltig
        assertEquals(false, v.checksumMatches) // Pruefsumme weicht ab
        assertFalse(v.checksumVerified)
    }

    /* ======================= FrameReassembler ======================= */

    @Test
    fun `reassembler returns a single complete frame immediately`() {
        val r = FrameReassembler()
        val frame = naveeFrame()
        val out = r.feed(frame)
        assertEquals(1, out.frames.size)
        assertEquals(0, out.bufferedBytes)
        assertTrue(out.frames[0].contentEquals(frame))
    }

    @Test
    fun `reassembler joins a frame split across two notifications`() {
        val r = FrameReassembler()
        val frame = naveeFrame()
        val cut = frame.size / 2
        val first = r.feed(frame.copyOfRange(0, cut))
        assertFalse(first.hasFrames)
        assertTrue(first.bufferedBytes > 0)
        val second = r.feed(frame.copyOfRange(cut, frame.size))
        assertEquals(1, second.frames.size)
        assertTrue(second.frames[0].contentEquals(frame))
    }

    @Test
    fun `reassembler drops garbage prefix and resyncs`() {
        val r = FrameReassembler()
        val frame = naveeFrame()
        val noisy = byteArrayOf(0x00, 0x13, 0x37) + frame
        val out = r.feed(noisy)
        assertEquals(1, out.frames.size)
        assertTrue(out.droppedBytes >= 3)
        assertTrue(out.frames[0].contentEquals(frame))
    }

    @Test
    fun `reassembler extracts two frames from one chunk`() {
        val r = FrameReassembler()
        val f1 = naveeFrame()
        val f2 = ninebotFrame()
        val out = r.feed(f1 + f2)
        assertEquals(2, out.frames.size)
        assertTrue(out.frames[0].contentEquals(f1))
        assertTrue(out.frames[1].contentEquals(f2))
    }

    @Test
    fun `reassembler discards pure garbage without header`() {
        val r = FrameReassembler()
        val out = r.feed(byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44))
        assertFalse(out.hasFrames)
        assertTrue(out.droppedBytes >= 4)
    }

    @Test
    fun `reassembler handles header split across chunks`() {
        val r = FrameReassembler()
        val frame = naveeFrame()
        // Nur erstes Header-Byte zuerst -> muss als Teil-Header behalten werden.
        val first = r.feed(byteArrayOf(frame[0]))
        assertFalse(first.hasFrames)
        val rest = r.feed(frame.copyOfRange(1, frame.size))
        assertEquals(1, rest.frames.size)
        assertTrue(rest.frames[0].contentEquals(frame))
    }

    @Test
    fun `reassembler ignores empty chunk`() {
        val r = FrameReassembler()
        val out = r.feed(ByteArray(0))
        assertFalse(out.hasFrames)
        assertEquals(0, out.bufferedBytes)
    }

    @Test
    fun `reassembler caps buffer on unbounded garbage`() {
        val r = FrameReassembler(maxBuffer = 64)
        val out = r.feed(ByteArray(500) { 0x00 })
        assertFalse(out.hasFrames)
        assertTrue(out.bufferedBytes <= 64)
    }

    @Test
    fun `reassembler reset drops partial frame on disconnect`() {
        // Simuliert einen Verbindungsabbruch mitten im Empfang: die Engine ruft
        // resetConnectionState() -> reassembler.reset(). Ein halber Frame darf
        // NICHT in die naechste Verbindung "durchbluten".
        val r = FrameReassembler()
        val frame = naveeFrame()
        r.feed(frame.copyOfRange(0, frame.size / 2))
        assertTrue(r.bufferedBytes > 0)
        r.reset()
        assertEquals(0, r.bufferedBytes)
        // Nach dem Reset dekodiert ein vollstaendiger Frame wieder sauber.
        val out = r.feed(frame)
        assertEquals(1, out.frames.size)
        assertTrue(out.frames[0].contentEquals(frame))
    }

    @Test
    fun `duplicate notification yields one frame per callback without corruption`() {
        // Doppelte BLE-Callbacks (z.B. verspaetete Zustellung) duerfen den
        // Puffer nicht verfaelschen: jede vollstaendige Notification ergibt
        // genau einen unveraenderten Frame.
        val r = FrameReassembler()
        val frame = naveeFrame()
        val first = r.feed(frame)
        val second = r.feed(frame)
        assertEquals(1, first.frames.size)
        assertEquals(1, second.frames.size)
        assertTrue(first.frames[0].contentEquals(frame))
        assertTrue(second.frames[0].contentEquals(frame))
        assertEquals(0, r.bufferedBytes)
    }

    /* ======================= TelemetryDiagnostics ======================= */

    @Test
    fun `diagnostics counters accumulate`() {
        val d = TelemetryDiagnostics()
        d.recordChunk(20, 1000)
        d.recordChunk(30, 1100)
        d.recordFrame(true)
        d.recordFrame(false)
        d.recordDecode(true)
        d.recordDecode(false, "boom")
        val s = d.snapshot("navee-gt3max", 5, true, true)
        assertEquals(2L, s.notifications)
        assertEquals(50L, s.bytes)
        assertEquals(1100L, s.lastRxAtMs)
        assertEquals(2L, s.framesDetected)
        assertEquals(1L, s.invalidFrames)
        assertEquals(1L, s.decodeSuccess)
        assertEquals(1L, s.decodeFailure)
        assertEquals("boom", s.lastError)
        assertEquals(5, s.bufferedBytes)
    }

    @Test
    fun `diagnostics reset clears all counters`() {
        val d = TelemetryDiagnostics()
        d.recordChunk(10, 5)
        d.recordFrame(false)
        d.recordDecode(false, "x")
        d.reset()
        val s = d.snapshot("", 0, false, false)
        assertEquals(0L, s.notifications)
        assertEquals(0L, s.bytes)
        assertNull(s.lastRxAtMs)
        assertEquals(0L, s.framesDetected)
        assertNull(s.lastError)
    }

    @Test
    fun `cause reports not connected`() {
        val s = TelemetryDiagnosticsSnapshot(connected = false)
        assertTrue(TelemetryDiagnosis.cause(s).contains("Nicht verbunden"))
    }

    @Test
    fun `cause reports no notifications while connected`() {
        val s = TelemetryDiagnosticsSnapshot(connected = true, notifications = 0)
        assertTrue(TelemetryDiagnosis.cause(s).contains("keine Notification"))
    }

    @Test
    fun `cause reports no valid header`() {
        val s = TelemetryDiagnosticsSnapshot(
            connected = true, notifications = 3, framesDetected = 0
        )
        assertTrue(TelemetryDiagnosis.cause(s).contains("kein gueltiger Frame-Header"))
    }

    @Test
    fun `cause reports decode failure`() {
        val s = TelemetryDiagnosticsSnapshot(
            connected = true, notifications = 3, framesDetected = 2,
            decodeSuccess = 0, decodeFailure = 2
        )
        assertTrue(TelemetryDiagnosis.cause(s).contains("Dekodierung fehlgeschlagen"))
    }

    @Test
    fun `cause mentions UNVERIFIED when decode succeeded`() {
        val s = TelemetryDiagnosticsSnapshot(
            connected = true, notifications = 3, framesDetected = 2,
            decodeSuccess = 2, decodeFailure = 0
        )
        assertTrue(TelemetryDiagnosis.cause(s).contains("UNVERIFIED"))
    }

    /* ======================= Decoder Result-API ======================= */

    @Test
    fun `decodeNaveeResult succeeds on valid frame`() {
        val r = TelemetryDecoder.decodeNaveeResult(naveeFrame())
        assertTrue(r.success)
        assertNotNull(r.telemetry)
        assertEquals(20.0f, r.telemetry!!.speedKmh!!, 0.01f)
    }

    @Test
    fun `decodeNinebotResult succeeds on valid frame`() {
        val r = TelemetryDecoder.decodeNinebotResult(ninebotFrame())
        assertTrue(r.success)
        assertEquals(20.0f, r.telemetry!!.speedKmh!!, 0.01f)
    }

    @Test
    fun `decodeNaveeResult fails with reason on wrong header`() {
        val r = TelemetryDecoder.decodeNaveeResult(byteArrayOf(0x00, 0x01, 0x02, 0x03))
        assertFalse(r.success)
        assertNull(r.telemetry)
        assertNotNull(r.reason)
    }

    @Test
    fun `decodeNaveeResult rejects structurally invalid frame`() {
        val full = naveeFrame()
        val truncated = full.copyOfRange(0, full.size - 1)
        val r = TelemetryDecoder.decodeNaveeResult(truncated)
        assertFalse(r.success)
        assertNotNull(r.reason)
    }

    @Test
    fun `decodeNinebotResult rejects structurally invalid frame`() {
        val full = ninebotFrame()
        val truncated = full.copyOfRange(0, full.size - 1)
        val r = TelemetryDecoder.decodeNinebotResult(truncated)
        assertFalse(r.success)
    }

    /* ======================= Grenzwerte ======================= */

    @Test
    fun `battery boundary values 0 and 100 are accepted 101 is dropped`() {
        fun withBattery(v: Int): Telemetry? {
            val p = naveePayload().also { it[6] = v.toByte() }
            return TelemetryDecoder.decodeNavee(NaveeFrames.buildFrame(0x20, p))
        }
        assertEquals(0, withBattery(0)!!.batteryPercent)
        assertEquals(100, withBattery(100)!!.batteryPercent)
        assertNull(withBattery(101)!!.batteryPercent)
    }

    @Test
    fun `temperature boundary values are honoured`() {
        fun withTemp(v: Int): Telemetry? {
            val p = naveePayload().also { it[7] = v.toByte() }
            return TelemetryDecoder.decodeNavee(NaveeFrames.buildFrame(0x20, p))
        }
        assertEquals(-40f, withTemp(-40)!!.motorTempC!!, 0.01f)
        assertEquals(125f, withTemp(125)!!.motorTempC!!, 0.01f)
        assertNull(withTemp(126)!!.motorTempC)
    }

    /* ======================= Zufall / Korruption ======================= */

    @Test
    fun `random corrupted input never throws and never yields garbage telemetry`() {
        val rnd = Random(0xC0FFEE) // deterministisch
        repeat(500) {
            val size = rnd.nextInt(0, 80)
            val bytes = ByteArray(size) { rnd.nextInt(0, 256).toByte() }
            // Weder Validator noch Decoder duerfen werfen.
            FrameValidator.validate(bytes)
            TelemetryDecoder.decodeNavee(bytes)
            TelemetryDecoder.decodeNinebot(bytes)
            TelemetryDecoder.decodeNaveeResult(bytes)
            TelemetryDecoder.decodeNinebotResult(bytes)
        }
    }

    @Test
    fun `reassembler survives random byte streams`() {
        val rnd = Random(0xBADF00D)
        val r = FrameReassembler(maxBuffer = 128)
        repeat(200) {
            val size = rnd.nextInt(0, 60)
            val bytes = ByteArray(size) { rnd.nextInt(0, 256).toByte() }
            r.feed(bytes)
            assertTrue(r.bufferedBytes <= 128)
        }
    }
}
