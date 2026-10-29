package com.syrak.scooterlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryMathTest {

    /* ---------------- TripAccumulator ---------------- */

    @Test
    fun `normal 30 second delta is integrated`() {
        val dt = TripAccumulator.deltaHours(1_000L, 1_000L + 30_000L)
        assertEquals(30_000.0 / 3_600_000.0, dt, 1e-12)
    }

    @Test
    fun `first sample without predecessor yields zero`() {
        assertEquals(0.0, TripAccumulator.deltaHours(0L, 5_000L), 0.0)
    }

    @Test
    fun `backwards clock yields zero instead of negative distance`() {
        assertEquals(0.0, TripAccumulator.deltaHours(10_000L, 9_000L), 0.0)
    }

    @Test
    fun `identical tick yields zero`() {
        assertEquals(0.0, TripAccumulator.deltaHours(10_000L, 10_000L), 0.0)
    }

    @Test
    fun `gap above max is not integrated`() {
        val gap = TripAccumulator.MAX_GAP_MILLIS + 1
        assertEquals(0.0, TripAccumulator.deltaHours(1_000L, 1_000L + gap), 0.0)
    }

    @Test
    fun `gap exactly at max is integrated`() {
        val dt = TripAccumulator.deltaHours(1L, 1L + TripAccumulator.MAX_GAP_MILLIS)
        assertEquals(TripAccumulator.MAX_GAP_MILLIS / 3_600_000.0, dt, 1e-12)
    }

    /* ---------------- RangeEstimator ---------------- */

    @Test
    fun `unknown model never yields an estimate`() {
        assertNull(RangeEstimator.estimateKm(ScooterModel.UNKNOWN, 80))
    }

    @Test
    fun `missing battery never yields an estimate`() {
        assertNull(RangeEstimator.estimateKm(ScooterModel.NAVEE_GT3_MAX, null))
    }

    @Test
    fun `known model uses its own nominal voltage`() {
        // 48 V * 10 Ah * 50% = 240 Wh / 15 Wh/km = 16 km
        val km = RangeEstimator.estimateKm(ScooterModel.NAVEE_GT3_MAX, 50)
        assertEquals(16.0, km!!, 1e-9)
    }

    @Test
    fun `known 36 volt model stays unchanged`() {
        // 36 V * 10 Ah * 100% = 360 Wh / 15 = 24 km
        val km = RangeEstimator.estimateKm(ScooterModel.NINEBOT_MAX_G30, 100)
        assertEquals(24.0, km!!, 1e-9)
    }
}
