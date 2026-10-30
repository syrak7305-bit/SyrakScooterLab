package com.syrak.scooterlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PHASE 9 — Robustheit des Decoders gegen ungueltige und fehlende Daten.
 *
 * Diese Tests ergaenzen [TelemetryDecoderTest] (bestehende Tests bleiben
 * unveraendert). Sie belegen: Sentinels, unplausible Werte und abgeschnittene
 * Felder fuehren zu null — niemals zu einem erfundenen Wert.
 */
class TelemetryDecoderRobustnessTest {

    private fun putU16(payload: ByteArray, offset: Int, value: Int) {
        payload[offset] = (value and 0xFF).toByte()
        payload[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun naveePayload(size: Int = 48): ByteArray {
        val p = ByteArray(size)
        // gueltige Grundwerte
        putU16(p, 0, 200)      // 20.0 km/h
        putU16(p, 2, 4800)     // 48.00 V
        putU16(p, 4, 500)      // 5.00 A
        p[6] = 80              // 80 %
        p[7] = 30              // 30 C
        for (i in 0 until 13) {
            val off = 8 + i * 2
            if (off + 1 < size) putU16(p, off, 3700) // 3.700 V
        }
        return p
    }

    private fun ninebotPayload(size: Int = 52): ByteArray {
        val p = ByteArray(size)
        putU16(p, 4, 200)      // 20.0 km/h
        putU16(p, 6, 3600)     // 36.00 V
        putU16(p, 8, 500)      // 5.00 A
        p[10] = 90             // 90 %
        p[11] = 25             // 25 C
        for (i in 0 until 10) {
            val off = 12 + i * 2
            if (off + 1 < size) putU16(p, off, 3700)
        }
        return p
    }

    /* ----------------------------- Navee ----------------------------- */

    @Test
    fun `navee invalid battery percent is dropped`() {
        val p = naveePayload().also { it[6] = 200.toByte() }
        val t = TelemetryDecoder.decodeNavee(NaveeFrames.buildFrame(0x20, p))
        assertNotNull(t)
        assertNull(t!!.batteryPercent)
    }

    @Test
    fun `navee implausible temperature is dropped`() {
        // 200 als signed byte = -56 C -> ausserhalb -40..125
        val p = naveePayload().also { it[7] = 200.toByte() }
        val t = TelemetryDecoder.decodeNavee(NaveeFrames.buildFrame(0x20, p))
        assertNotNull(t)
        assertNull(t!!.motorTempC)
    }

    @Test
    fun `navee speed sentinel is dropped`() {
        val p = naveePayload().also { putU16(it, 0, 0xFFFF) }
        val t = TelemetryDecoder.decodeNavee(NaveeFrames.buildFrame(0x20, p))
        assertNull(t!!.speedKmh)
    }

    @Test
    fun `navee zero voltage is dropped`() {
        val p = naveePayload().also { putU16(it, 2, 0) }
        val t = TelemetryDecoder.decodeNavee(NaveeFrames.buildFrame(0x20, p))
        assertNull(t!!.voltageV)
    }

    @Test
    fun `navee current sentinel is dropped`() {
        val p = naveePayload().also { putU16(it, 4, 0x8000) } // -32768
        val t = TelemetryDecoder.decodeNavee(NaveeFrames.buildFrame(0x20, p))
        assertNull(t!!.currentA)
    }

    @Test
    fun `navee out of range cell array is rejected entirely`() {
        val p = naveePayload().also { putU16(it, 8 + 5 * 2, 500) } // 0.5 V
        val t = TelemetryDecoder.decodeNavee(NaveeFrames.buildFrame(0x20, p))
        assertNull(t!!.cellVoltages)
        assertNull(t.cellMinV)
        assertNull(t.cellMaxV)
    }

    @Test
    fun `navee short payload keeps core values but no capacity`() {
        val p = naveePayload(size = 12)
        val t = TelemetryDecoder.decodeNavee(NaveeFrames.buildFrame(0x20, p))
        assertNotNull(t)
        assertEquals(20.0f, t!!.speedKmh!!, 0.01f)
        assertNull(t.designCapacityMah)
        assertNull(t.fullChargeCapacityMah)
        assertNull(t.cycleCount)
    }

    /* ----------------------------- Ninebot ----------------------------- */

    @Test
    fun `ninebot invalid battery percent is dropped`() {
        val p = ninebotPayload().also { it[10] = 200.toByte() }
        val t = TelemetryDecoder.decodeNinebot(ScooterFrames.buildFrame(0x20, 0x01, p))
        assertNotNull(t)
        assertNull(t!!.batteryPercent)
    }

    @Test
    fun `ninebot out of range cell array is rejected entirely`() {
        val p = ninebotPayload().also { putU16(it, 12 + 3 * 2, 4800) } // 4.8 V
        val t = TelemetryDecoder.decodeNinebot(ScooterFrames.buildFrame(0x20, 0x01, p))
        assertNull(t!!.cellVoltages)
    }

    @Test
    fun `ninebot short payload keeps core values but no capacity`() {
        val p = ninebotPayload(size = 12)
        val t = TelemetryDecoder.decodeNinebot(ScooterFrames.buildFrame(0x20, 0x01, p))
        assertNotNull(t)
        assertEquals(20.0f, t!!.speedKmh!!, 0.01f)
        assertNull(t.designCapacityMah)
        assertNull(t.cycleCount)
    }

    @Test
    fun `ninebot current sentinel is dropped`() {
        val p = ninebotPayload().also { putU16(it, 8, 0x8000) }
        val t = TelemetryDecoder.decodeNinebot(ScooterFrames.buildFrame(0x20, 0x01, p))
        assertNull(t!!.currentA)
    }

    @Test
    fun `wrong header never decodes`() {
        val p = naveePayload()
        val frame = NaveeFrames.buildFrame(0x20, p)
        frame[1] = 0x00
        assertNull(TelemetryDecoder.decodeNavee(frame))
    }

    @Test
    fun `empty and one byte frames never throw`() {
        assertNull(TelemetryDecoder.decodeNavee(ByteArray(0)))
        assertNull(TelemetryDecoder.decodeNinebot(ByteArray(1)))
    }
}
