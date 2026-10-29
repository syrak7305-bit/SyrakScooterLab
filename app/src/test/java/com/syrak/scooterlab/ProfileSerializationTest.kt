package com.syrak.scooterlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileSerializationTest {

    @Test
    fun `simple name round trips unchanged`() {
        val p = ScooterProfile("Stadt", 20, 0, 1, true)
        assertEquals(p, ScooterRepository.decodeProfile(ScooterRepository.encodeProfile(p)))
    }

    @Test
    fun `name containing pipe does not shift fields`() {
        val p = ScooterProfile("Wald|Tour", 25, 1, 2, false)
        val decoded = ScooterRepository.decodeProfile(ScooterRepository.encodeProfile(p))
        assertEquals(p, decoded)
    }

    @Test
    fun `name with multiple pipes is preserved`() {
        val p = ScooterProfile("a|b|c|d", 30, 0, 0, true)
        val decoded = ScooterRepository.decodeProfile(ScooterRepository.encodeProfile(p))
        assertEquals("a|b|c|d", decoded!!.name)
        assertEquals(30, decoded.maxSpeed)
        assertEquals(0, decoded.zeroStart)
        assertEquals(0, decoded.kers)
        assertEquals(true, decoded.cruiseControl)
    }

    @Test
    fun `legacy entry without pipe in name still parses`() {
        val decoded = ScooterRepository.decodeProfile("Alt|22|1|2|true")
        assertEquals("Alt", decoded!!.name)
        assertEquals(22, decoded.maxSpeed)
        assertEquals(1, decoded.zeroStart)
        assertEquals(2, decoded.kers)
        assertEquals(true, decoded.cruiseControl)
    }

    @Test
    fun `too few fields is rejected`() {
        assertNull(ScooterRepository.decodeProfile("nur|drei|felder"))
    }

    @Test
    fun `non numeric speed is rejected`() {
        assertNull(ScooterRepository.decodeProfile("Name|schnell|0|1|true"))
    }

    /**
     * Regression fuer die fruehere Divergenz: [ScooterRepository.loadProfile]
     * nutzte einen naiven split("|") mit parts[0] als Namen und haette diesen
     * Eintrag (Name mit '|') verworfen bzw. falsch gelesen. Der kanonische
     * Parser [ScooterRepository.decodeProfile] muss ihn korrekt laden.
     */
    @Test
    fun `pipe name entry that old naive loader rejected now parses`() {
        val decoded = ScooterRepository.decodeProfile("Wald|Tour|25|1|2|false")
        assertNotNull(decoded)
        assertEquals("Wald|Tour", decoded!!.name)
        assertEquals(25, decoded.maxSpeed)
        assertEquals(1, decoded.zeroStart)
        assertEquals(2, decoded.kers)
        assertEquals(false, decoded.cruiseControl)
    }

    @Test
    fun `empty name keeps remaining fields`() {
        val p = ScooterProfile("", 18, 0, 1, false)
        val decoded = ScooterRepository.decodeProfile(ScooterRepository.encodeProfile(p))
        assertEquals("", decoded!!.name)
        assertEquals(18, decoded.maxSpeed)
    }
}
