package com.syrak.scooterlab.feature.telemetry

/**
 * A single telemetry sample decoded from the ESC/BMS registers.
 * All units are normalised to SI-friendly display units.
 */
data class Telemetry(
    val speedKmh: Float = 0f,
    val voltageV: Float = 0f,
    val currentA: Float = 0f,
    val batteryPercent: Int = 0,
    val motorTempC: Float = 0f,
    val batteryTempC: Float = 0f,
    val cellMinV: Float = 0f,
    val cellMaxV: Float = 0f,
    val odometerKm: Float = 0f,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    /** Instantaneous electrical power draw. */
    val powerW: Float get() = voltageV * currentA

    /** Cell imbalance in millivolts — a health signal. */
    val cellDeltaMv: Float get() = (cellMaxV - cellMinV) * 1000f

    /** Naive remaining-range estimate; refined once a real consumption model exists. */
    val estimatedRangeKm: Float
        get() = if (batteryPercent <= 0) 0f else (batteryPercent / 100f) * NOMINAL_RANGE_KM

    /** Battery health heuristic for the UI status chip. */
    val health: BatteryHealth
        get() = when {
            cellDeltaMv > 120f || batteryTempC > 55f -> BatteryHealth.CRITICAL
            cellDeltaMv > 60f || batteryTempC > 45f -> BatteryHealth.DEGRADED
            else -> BatteryHealth.NOMINAL
        }

    companion object {
        const val NOMINAL_RANGE_KM = 30f
        val Empty = Telemetry()
    }
}

enum class BatteryHealth { NOMINAL, DEGRADED, CRITICAL }
