package com.syrak.scooterlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The telemetry decoder must never crash and must never invent values. These
 * tests feed it valid frames (expected fields decoded) and hostile input
 * (truncated / wrong header -> null).
 */
class TelemetryDecoderTest {

    @Test
    fun `navee frame decodes core values`() {
        val payload = ByteArray(12)
        // speed 250 (0.1 km/h) -> 25.0 km/h at payload offset 0 (LE)
        payload[0] = 250.toByte(); payload[1] = 0
        // voltage 4800 (0.01 V) -> 48.0 V at offset 2
        payload[2] = (4800 and 0xFF).toByte(); payload[3] = (4800 shr 8).toByte()
        // battery 80 % at offset 6
        payload[6] = 80
        // temperature 30 C at offset 7
        payload[7] = 30

        val frame = NaveeFrames.buildFrame(0x20, payload)
        val t = TelemetryDecoder.decodeNavee(frame)
        assertNotNull(t)
        assertEquals(25.0f, t!!.speedKmh!!, 0.01f)
        assertEquals(48.0f, t.voltageV!!, 0.01f)
        assertEquals(80, t.batteryPercent)
        assertEquals(30f, t.motorTempC!!, 0.01f)
        assertEquals(BleProtocol.NAVEE_BRIGHTWAY, t.source)
    }

    @Test
    fun `navee wrong header returns null`() {
        val frame = NaveeFrames.buildFrame(0x20, ByteArray(12))
        frame[0] = 0x00
        assertNull(TelemetryDecoder.decodeNavee(frame))
    }

    @Test
    fun `navee truncated frame returns null`() {
        assertNull(TelemetryDecoder.decodeNavee(byteArrayOf(0xAA.toByte(), 0x55, 0x20)))
    }

    @Test
    fun `ninebot frame decodes core values`() {
        val payload = ByteArray(12)
        // speed 300 (0.1 km/h) -> 30.0 km/h at payload offset 4
        payload[4] = 300.toByte(); payload[5] = (300 shr 8).toByte()
        // voltage 3600 (0.01 V) -> 36.0 V at offset 6
        payload[6] = (3600 and 0xFF).toByte(); payload[7] = (3600 shr 8).toByte()
        // battery 90 % at offset 10
        payload[10] = 90
        // temperature 25 C at offset 11
        payload[11] = 25

        val frame = ScooterFrames.buildFrame(0x20, 0x01, payload)
        val t = TelemetryDecoder.decodeNinebot(frame)
        assertNotNull(t)
        assertEquals(30.0f, t!!.speedKmh!!, 0.01f)
        assertEquals(36.0f, t.voltageV!!, 0.01f)
        assertEquals(90, t.batteryPercent)
        assertEquals(25f, t.motorTempC!!, 0.01f)
        assertEquals(BleProtocol.NINEBOT, t.source)
    }

    @Test
    fun `ninebot truncated frame returns null`() {
        assertNull(TelemetryDecoder.decodeNinebot(byteArrayOf(0x5A, 0xA5.toByte(), 0x01)))
    }

    @Test
    fun `empty frame never throws`() {
        assertNull(TelemetryDecoder.decodeNavee(ByteArray(0)))
        assertNull(TelemetryDecoder.decodeNinebot(ByteArray(0)))
    }
}
