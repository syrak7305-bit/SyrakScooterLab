package com.syrak.scooterlab.feature.telemetry

import com.syrak.scooterlab.BleProtocol

/**
 * Telemetry data for a scooter. Default values represent "no data yet" where
 * appropriate; some fields intentionally nullable to distinguish "no reading"
 * from a real zero value.
 *
 * This model is shared between the BLE layer (real frames) and the simulator
 * so it contains a broad superset of fields observed across controller
 * families.
 */
data class Telemetry(
    val speedKmh: Float = 0f,
    val voltageV: Float = 0f,
    val currentA: Float = 0f,
    val batteryPercent: Int? = null,
    val motorTempC: Float? = null,
    val batteryTempC: Float? = null,
    val cellMinV: Float? = null,
    val cellMaxV: Float? = null,
    val odometerKm: Float = 0f,

    // Optional, additional diagnostics from controller frames
    val cellVoltages: List<Float>? = null,
    val designCapacityMah: Int? = null,
    val fullChargeCapacityMah: Int? = null,
    val cycleCount: Int? = null,
    val rawHex: String? = null,
    val source: BleProtocol? = null,
) {

    /** Instantaneous electrical power (W). */
    val powerW: Float
        get() = voltageV * currentA

    /** Cell voltage spread in mV (non-negative). */
    val cellDeltaMv: Float
        get() = if (cellMinV != null && cellMaxV != null)
            (cellMaxV - cellMinV).coerceAtLeast(0f) * 1000f
        else 0f

    /**
     * Simple estimated remaining range in km.
     *
     * Heuristic:
     *  - Prefer energy-based estimate when fullChargeCapacityMah is available:
     *      energy_wh = (fullChargeCapacityMah / 1000) * voltageV
     *      available_wh = energy_wh * (batteryPercent/100)
     *      range_km = available_wh * KM_PER_WH
     *  - Fallback to a coarse-percent heuristic when only batteryPercent is known:
     *      range_km = batteryPercent * KM_PER_PERCENT
     *  - Otherwise 0.
     *
     * Constants are intentionally conservative and configurable here.
     */
    val estimatedRangeKm: Float
        get() {
            val pct = batteryPercent
            if (fullChargeCapacityMah != null && fullChargeCapacityMah > 0 && pct != null && pct > 0) {
                val voltage = if (voltageV > 0f) voltageV else AVERAGE_VOLTAGE
                val energyWh = (fullChargeCapacityMah.toFloat() / 1000f) * voltage
                val availableWh = energyWh * (pct / 100f)
                return availableWh * KM_PER_WH
            }
            if (pct != null) {
                return pct * KM_PER_PERCENT
            }
            return 0f
        }

    /** Battery health state derived from batteryPercent. */
    val health: BatteryHealth
        get() = when {
            batteryPercent == null -> BatteryHealth.UNKNOWN
            batteryPercent <= 0 -> BatteryHealth.CRITICAL // treat explicit 0% as critical
            batteryPercent < 10 -> BatteryHealth.CRITICAL
            batteryPercent < 30 -> BatteryHealth.DEGRADED
            else -> BatteryHealth.NOMINAL
        }

    companion object {
        /** Empty sentinel used when no telemetry is available. */
        val Empty = Telemetry()

        // Heuristic tuning constants
        private const val KM_PER_WH = 0.04f          // km per watt-hour (conservative)
        private const val KM_PER_PERCENT = 0.2f      // km per 1% battery (fallback)
        private const val AVERAGE_VOLTAGE = 36.0f    // typical pack voltage if unknown
    }
}

/** Battery health categories used by the UI. */
enum class BatteryHealth {
    UNKNOWN,
    NOMINAL,
    DEGRADED,
    CRITICAL
}
