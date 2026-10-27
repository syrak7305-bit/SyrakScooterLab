package com.syrak.scooterlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the frame metadata layer (command extraction, checksum validation,
 * ACK matching) for both protocol families. These are pure-Kotlin functions and
 * are the backbone of the write/ACK logic in the BLE engine, so they must be
 * provably correct before any real device is touched.
 */
class FrameCodecTest {

    @Test
    fun `navee read-status frame is structurally valid`() {
        val frame = NaveeFrames.readStatus()
        assertTrue("Navee read-status must validate", FrameCodec.isValidFrame(frame))
        assertEquals(0x20, FrameCodec.commandIdOf(frame))
    }

    @Test
    fun `navee speed frame carries command id 0x10`() {
        val frame = NaveeFrames.speedCeiling(25)
        assertTrue(FrameCodec.isValidFrame(frame))
        assertEquals(0x10, FrameCodec.commandIdOf(frame))
    }

    @Test
    fun `navee handshake is AA 55 01 01 01`() {
        val frame = NaveeFrames.handshake()
        assertEquals(0xAA, frame[0].toInt() and 0xFF)
        assertEquals(0x55, frame[1].toInt() and 0xFF)
        assertEquals(0x01, frame[2].toInt() and 0xFF)
        assertTrue(FrameCodec.isValidFrame(frame))
    }

    @Test
    fun `corrupted checksum fails validation`() {
        val frame = NaveeFrames.readStatus()
        frame[frame.size - 1] = (frame[frame.size - 1] + 1).toByte()
        assertFalse(FrameCodec.isValidFrame(frame))
    }

    @Test
    fun `ninebot read frame is valid and carries command id`() {
        val frame = ScooterFrames.readTelemetry()
        assertTrue(FrameCodec.isValidFrame(frame))
        assertEquals(0x01, FrameCodec.commandIdOf(frame))
    }

    @Test
    fun `ninebot speed frame carries command id 0x71`() {
        val frame = ScooterFrames.maxSpeed(25)
        assertTrue(FrameCodec.isValidFrame(frame))
        assertEquals(0x71, FrameCodec.commandIdOf(frame))
    }

    @Test
    fun `ack matches only on same command id and valid frame`() {
        val sent = NaveeFrames.speedCeiling(25)
        val ack = NaveeFrames.speedCeiling(25)
        assertTrue(ProtocolAdapters.forProtocol(BleProtocol.NAVEE_BRIGHTWAY).isAckFor(sent, ack))

        val wrongCmd = NaveeFrames.readStatus()
        assertFalse(ProtocolAdapters.forProtocol(BleProtocol.NAVEE_BRIGHTWAY).isAckFor(sent, wrongCmd))
    }

    @Test
    fun `short or foreign frames are rejected`() {
        assertFalse(FrameCodec.isValidFrame(byteArrayOf(0xAA.toByte(), 0x55)))
        assertFalse(FrameCodec.isValidFrame(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00)))
        assertEquals(null, FrameCodec.commandIdOf(byteArrayOf(0x12, 0x34)))
    }
}
