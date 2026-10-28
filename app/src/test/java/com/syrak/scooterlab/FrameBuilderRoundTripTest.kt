package com.syrak.scooterlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stellt sicher, dass JEDER vom Produktionspfad erzeugbare Outbound-Frame
 * strukturell gültig ist (Header, Längenfeld, Prüfsumme) und die erwartete
 * Kommando-ID trägt. Das ist die Voraussetzung dafür, dass die BleEngine einen
 * echten ACK überhaupt erkennen kann. Reine Kotlin-Logik — kein Gerät nötig.
 */
class FrameBuilderRoundTripTest {

    private fun assertNinebot(frame: ByteArray, expectedCmd: Int) {
        assertTrue("Ninebot frame must validate: ${ScooterFrames.toHex(frame)}", FrameCodec.isValidFrame(frame))
        assertEquals(expectedCmd, FrameCodec.commandIdOf(frame))
    }

    private fun assertNavee(frame: ByteArray, expectedCmd: Int) {
        assertTrue("Navee frame must validate: ${NaveeFrames.toHex(frame)}", FrameCodec.isValidFrame(frame))
        assertEquals(expectedCmd, FrameCodec.commandIdOf(frame))
    }

    @Test
    fun `every ninebot outbound builder yields a valid frame`() {
        assertNinebot(ScooterFrames.zeroStart(6), 0x72)
        assertNinebot(ScooterFrames.maxSpeed(25), 0x71)
        assertNinebot(ScooterFrames.maxPower(), 0x73)
        assertNinebot(ScooterFrames.lock(true), 0x7A)
        assertNinebot(ScooterFrames.germanManeuver(), 0x78)
        assertNinebot(ScooterFrames.policeMode(20), 0x79)
        assertNinebot(ScooterFrames.kers(2), 0x7B)
        assertNinebot(ScooterFrames.cruise(true), 0x7C)
        assertNinebot(ScooterFrames.motorTempGuard(80), 0x7D)
        assertNinebot(ScooterFrames.emergencyCut(), 0x7E)
        assertNinebot(ScooterFrames.overspeedAlarmMute(true), 0x7F)
        assertNinebot(ScooterFrames.readTelemetry(), 0x01)
    }

    @Test
    fun `every navee outbound builder yields a valid frame`() {
        assertNavee(NaveeFrames.handshake(), 0x01)
        assertNavee(NaveeFrames.speedCeiling(25), 0x10)
        assertNavee(NaveeFrames.zeroStart(6), 0x11)
        assertNavee(NaveeFrames.regionToggle(0x02), 0x12)
        assertNavee(NaveeFrames.maxPower(), 0x13)
        assertNavee(NaveeFrames.overspeedAlarmMute(true), 0x14)
        assertNavee(NaveeFrames.germanManeuver(), 0x18)
        assertNavee(NaveeFrames.lock(true), 0x1A)
        assertNavee(NaveeFrames.kers(2), 0x1B)
        assertNavee(NaveeFrames.cruise(true), 0x1C)
        assertNavee(NaveeFrames.motorTempGuard(80), 0x1D)
        assertNavee(NaveeFrames.emergencyCut(), 0x1E)
        assertNavee(NaveeFrames.policeMode(20), 0x1F)
        assertNavee(NaveeFrames.readStatus(), 0x20)
    }

    @Test
    fun `ninebot to navee translation produces valid frames for every mapped command`() {
        val ninebotFrames = listOf(
            ScooterFrames.maxSpeed(25),
            ScooterFrames.zeroStart(6),
            ScooterFrames.maxPower(),
            ScooterFrames.germanManeuver(),
            ScooterFrames.policeMode(20),
            ScooterFrames.lock(true),
            ScooterFrames.kers(2),
            ScooterFrames.cruise(true),
            ScooterFrames.motorTempGuard(80),
            ScooterFrames.emergencyCut(),
            ScooterFrames.overspeedAlarmMute(true),
            ScooterFrames.readTelemetry(),
        )
        for (nb in ninebotFrames) {
            val translated = NaveeFrames.fromNinebot(nb)
            assertNotNull("Translation must not be null for cmd ${FrameCodec.commandIdOf(nb)}", translated)
            assertTrue("Translated frame must validate", FrameCodec.isValidFrame(translated!!))
        }
    }

    @Test
    fun `alternate 55 AA encoding is structurally valid`() {
        val alt = NaveeFrames.buildFrameAlt(NaveeFrames.CMD_SPEED, byteArrayOf(25))
        assertEquals(0x55, alt[0].toInt() and 0xFF)
        assertEquals(0xAA, alt[1].toInt() and 0xFF)
        assertTrue(FrameCodec.isValidFrame(alt))
        assertEquals(0x10, FrameCodec.commandIdOf(alt))
    }

    @Test
    fun `unknown command is not translated and invalid input is rejected`() {
        // 0x55 ist keine gemappte Ninebot-CMD
        val unknown = ScooterFrames.buildFrame(0x20, 0x55, byteArrayOf(0x00))
        assertEquals(null, NaveeFrames.fromNinebot(unknown))
        // Fremd-Header -> null
        assertEquals(null, NaveeFrames.fromNinebot(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05)))
        assertFalse(FrameCodec.isValidFrame(ByteArray(0)))
    }
}
