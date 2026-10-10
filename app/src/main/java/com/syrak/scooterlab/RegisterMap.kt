package com.syrak.scooterlab.core.protocol

/**
 * ============================================================================
 *  REGISTER MAP — READ THIS BEFORE ISSUING ANY WRITE.
 * ============================================================================
 *  Register addresses are NOT universal. They differ between ESC firmware
 *  revisions and between brands (Ninebot vs Xiaomi vs Navee). The values below
 *  are a *reference scaffold* with representative semantics; they MUST be
 *  confirmed against the exact firmware of the target controller (via the
 *  Diagnostics console + a known-good dump) before any write is attempted.
 *
 *  Writing to an unmapped address can corrupt calibration data or brick the
 *  controller. Every write path in this app is therefore gated behind
 *  [RegisterMap.isWritable] and an explicit user confirmation.
 * ============================================================================
 */
object RegisterMap {

    /* ------------------------------- Identity -------------------------------- */

    const val SERIAL_NUMBER = 0x10
    const val FIRMWARE_VERSION = 0x1A
    const val MODEL = 0x1B

    /* ------------------------------ Tuning (ESC) ------------------------------ */
    /** Region/locale selector. Changing this is the mechanism behind the German Maneuver. */
    const val REGION = 0x70
    /** Maximum speed limit, stored as km/h (single byte) on most firmwares. */
    const val SPEED_LIMIT = 0x74
    /** KERS / regenerative braking strength. */
    const val KERS_LEVEL = 0x7A
    /** Cruise-control enable flag. */
    const val CRUISE_CONTROL = 0x7B

    /* ------------------------------ Telemetry (ESC) --------------------------- */

    const val SPEED = 0xB0
    const val VOLTAGE = 0xB1
    const val CURRENT = 0xB2
    const val MOTOR_TEMPERATURE = 0xB3
    const val ODOMETER = 0xB4
    const val TRIP_DISTANCE = 0xB5

    /* ------------------------------ Telemetry (BMS) --------------------------- */

    const val BATTERY_LEVEL = 0xC0
    const val BATTERY_VOLTAGE = 0xC1
    const val CELL_VOLTAGE_MIN = 0xC2
    const val CELL_VOLTAGE_MAX = 0xC3
    const val BATTERY_TEMPERATURE = 0xC4

    /**
     * Region identifiers. 0x00 = Global/US (30 km/h ceiling), 0x01 = EU (25 km/h),
     * 0x02 = DE (20 km/h). Values are illustrative and firmware-dependent.
     */
    object Region {
        const val GLOBAL = 0x00
        const val EU = 0x01
        const val DE = 0x02
    }

    /** Addresses the app is permitted to write. Everything else is read-only. */
    private val WRITABLE = setOf(REGION, SPEED_LIMIT, KERS_LEVEL, CRUISE_CONTROL)

    fun isWritable(register: Int): Boolean = register in WRITABLE

    /** Human-readable name for the diagnostics console. */
    fun nameOf(register: Int): String = when (register) {
        SERIAL_NUMBER -> "SERIAL_NUMBER"
        FIRMWARE_VERSION -> "FIRMWARE_VERSION"
        MODEL -> "MODEL"
        REGION -> "REGION"
        SPEED_LIMIT -> "SPEED_LIMIT"
        KERS_LEVEL -> "KERS_LEVEL"
        CRUISE_CONTROL -> "CRUISE_CONTROL"
        SPEED -> "SPEED"
        VOLTAGE -> "VOLTAGE"
        CURRENT -> "CURRENT"
        MOTOR_TEMPERATURE -> "MOTOR_TEMPERATURE"
        ODOMETER -> "ODOMETER"
        TRIP_DISTANCE -> "TRIP_DISTANCE"
        BATTERY_LEVEL -> "BATTERY_LEVEL"
        BATTERY_VOLTAGE -> "BATTERY_VOLTAGE"
        CELL_VOLTAGE_MIN -> "CELL_VOLTAGE_MIN"
        CELL_VOLTAGE_MAX -> "CELL_VOLTAGE_MAX"
        BATTERY_TEMPERATURE -> "BATTERY_TEMPERATURE"
        else -> "REG_0x%02X".format(register)
    }
}
