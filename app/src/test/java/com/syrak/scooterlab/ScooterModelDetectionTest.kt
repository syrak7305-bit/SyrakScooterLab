package com.syrak.scooterlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Regression tests for model detection.
 *
 * The single most important invariant: a generic device whose name merely
 * contains "NAVEE" (or the bare token "GT3") must NOT be classified as the
 * Navee GT3 Max. Only an explicit "gt3 max" token may do that.
 */
class ScooterModelDetectionTest {

    @Test
    fun `bare NAVEE name is not classified as GT3 Max`() {
        assertEquals(ScooterModel.UNKNOWN, ScooterModel.detect("NAVEE", ""))
        assertEquals(ScooterModel.UNKNOWN, ScooterModel.detect("Navee Scooter", ""))
        assertEquals(ScooterModel.UNKNOWN, ScooterModel.detect("navee-1234", ""))
    }

    @Test
    fun `bare GT3 token is not classified as GT3 Max`() {
        assertEquals(ScooterModel.UNKNOWN, ScooterModel.detect("GT3", ""))
    }

    @Test
    fun `explicit GT3 Max name is classified correctly`() {
        assertEquals(ScooterModel.NAVEE_GT3_MAX, ScooterModel.detect("Navee GT3 Max", ""))
        assertEquals(ScooterModel.NAVEE_GT3_MAX, ScooterModel.detect("NAVEE GT3 MAX 2024", ""))
    }

    @Test
    fun `GT3 Pro is not confused with GT3 Max`() {
        val detected = ScooterModel.detect("Navee GT3 Pro", "")
        assertEquals(ScooterModel.NAVEE_GT3_PRO, detected)
        assertNotEquals(ScooterModel.NAVEE_GT3_MAX, detected)
    }

    @Test
    fun `other Navee models are not classified as GT3 Max`() {
        assertNotEquals(ScooterModel.NAVEE_GT3_MAX, ScooterModel.detect("Navee S65", ""))
        assertNotEquals(ScooterModel.NAVEE_GT3_MAX, ScooterModel.detect("Navee V40", ""))
        assertNotEquals(ScooterModel.NAVEE_GT3_MAX, ScooterModel.detect("Navee N65", ""))
    }

    @Test
    fun `ninebot detection still works`() {
        assertEquals(ScooterModel.NINEBOT_MAX_G30, ScooterModel.detect("Segway Ninebot Max G30", ""))
        assertEquals(ScooterModel.NINEBOT_MAX_G2, ScooterModel.detect("Ninebot Max G2", ""))
    }

    @Test
    fun `empty name is unknown`() {
        assertEquals(ScooterModel.UNKNOWN, ScooterModel.detect("", ""))
    }
}
