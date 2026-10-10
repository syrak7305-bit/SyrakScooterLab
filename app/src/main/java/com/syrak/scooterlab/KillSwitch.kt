package com.syrak.scooterlab.feature.safety

import com.syrak.scooterlab.core.protocol.NinebotProtocol
import com.syrak.scooterlab.core.protocol.NinebotSession
import com.syrak.scooterlab.core.protocol.RegisterMap
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * ============================================================================
 *  EMERGENCY KILLSWITCH — SAFETY-CRITICAL PATH.
 * ============================================================================
 *  Purpose: bring the drivetrain to a safe, non-propelling state as fast as the
 *  link allows, then latch so an accidental re-apply cannot spin the motor.
 *
 *  Strategy (defence in depth):
 *    1. Cruise control OFF      — remove any latched throttle.
 *    2. Speed limit -> 0        — the firmware refuses to command motion.
 *    3. Latch internally        — further throttle commands are blocked locally.
 *
 *  This does NOT replace the physical brake. It is an electronic containment
 *  measure for bench/diagnostic use. The UI must present it as such.
 * ============================================================================
 */
class KillSwitch(
    private val session: NinebotSession,
) {
    @Volatile
    var isEngaged: Boolean = false
        private set

    /** Engage containment. Idempotent. */
    suspend fun engage(
        escAddress: Int = NinebotProtocol.ADDR_ESC,
        onStep: (String) -> Unit = {},
    ): Boolean {
        if (isEngaged) return true
        isEngaged = true
        Timber.w("KILLSWITCH ENGAGED")

        return try {
            onStep("Disabling cruise control")
            session.send(NinebotProtocol.buildWriteByte(escAddress, RegisterMap.CRUISE_CONTROL, 0x00))
            delay(INTER_STEP_DELAY_MS)

            onStep("Zeroing speed limit")
            session.send(NinebotProtocol.buildWriteByte(escAddress, RegisterMap.SPEED_LIMIT, 0x00))
            delay(INTER_STEP_DELAY_MS)

            onStep("Containment latched")
            true
        } catch (e: Exception) {
            Timber.e(e, "Killswitch engage failed")
            false
        }
    }

    /** Release containment and restore the supplied speed limit. */
    suspend fun release(
        escAddress: Int = NinebotProtocol.ADDR_ESC,
        restoreSpeedLimitKmh: Int = 25,
        onStep: (String) -> Unit = {},
    ): Boolean {
        return try {
            onStep("Restoring speed limit")
            session.send(
                NinebotProtocol.buildWriteByte(
                    escAddress,
                    RegisterMap.SPEED_LIMIT,
                    restoreSpeedLimitKmh.coerceIn(5, 45),
                ),
            )
            delay(INTER_STEP_DELAY_MS)
            isEngaged = false
            Timber.i("Killswitch released")
            true
        } catch (e: Exception) {
            Timber.e(e, "Killswitch release failed")
            false
        }
    }

    private companion object {
        const val INTER_STEP_DELAY_MS = 40L
    }
}
