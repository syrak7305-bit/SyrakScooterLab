package com.syrak.scooterlab.feature.tuning

import com.syrak.scooterlab.core.protocol.NinebotProtocol
import com.syrak.scooterlab.core.protocol.NinebotSession
import com.syrak.scooterlab.core.protocol.RegisterMap
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * The "German Maneuver": a *volatile* RAM patch that raises the speed ceiling by
 * rewriting the region + speed-limit registers in the ESC's working memory.
 *
 * Key properties:
 *  - NON-PERSISTENT: the patch lives in RAM and reverts on the next power cycle.
 *  - REVERSIBLE: the previous values are read first and can be restored at any time.
 *  - GUARDED: only registers flagged writable by [RegisterMap] are ever touched.
 *
 * Sequence:
 *   read baseline -> write region -> write speed limit -> read-back verify.
 */
class GermanManeuver(
    private val session: NinebotSession,
) {

    sealed interface Progress {
        data object ReadingBaseline : Progress
        data class Writing(val registerName: String) : Progress
        data object Verifying : Progress
        data class Complete(val result: Result) : Progress
        data class Failed(val reason: String) : Progress
    }

    data class Result(
        val previousRegion: Int?,
        val previousSpeedLimit: Int?,
        val newSpeedLimit: Int,
        val verified: Boolean,
        val volatile: Boolean = true,
    )

    /**
     * Apply the maneuver.
     *
     * @param escAddress bus address of the ESC (usually [NinebotProtocol.ADDR_ESC])
     * @param targetSpeedKmh desired ceiling; clamped to a sane 5..45 km/h
     * @param onProgress lifecycle callback for the UI
     */
    suspend fun execute(
        escAddress: Int = NinebotProtocol.ADDR_ESC,
        targetSpeedKmh: Int = 30,
        onProgress: (Progress) -> Unit = {},
    ): kotlin.Result<Result> {
        val target = targetSpeedKmh.coerceIn(5, 45)

        // 1. Baseline capture (best-effort; a missing baseline does not abort).
        onProgress(Progress.ReadingBaseline)
        val prevRegion = session.readRegister(escAddress, RegisterMap.REGION)
            ?.firstOrNull()?.toInt()?.and(0xFF)
        val prevSpeed = session.readRegister(escAddress, RegisterMap.SPEED_LIMIT)
            ?.firstOrNull()?.toInt()?.and(0xFF)

        if (!RegisterMap.isWritable(RegisterMap.REGION) ||
            !RegisterMap.isWritable(RegisterMap.SPEED_LIMIT)
        ) {
            return kotlin.Result.failure(IllegalStateException("Target registers are not writable"))
        }

        // 2. Region -> Global (raises the firmware ceiling).
        onProgress(Progress.Writing(RegisterMap.nameOf(RegisterMap.REGION)))
        session.send(NinebotProtocol.buildWriteByte(escAddress, RegisterMap.REGION, RegisterMap.Region.GLOBAL))
        delay(INTER_WRITE_DELAY_MS)

        // 3. Speed limit -> target.
        onProgress(Progress.Writing(RegisterMap.nameOf(RegisterMap.SPEED_LIMIT)))
        session.send(NinebotProtocol.buildWriteByte(escAddress, RegisterMap.SPEED_LIMIT, target))
        delay(INTER_WRITE_DELAY_MS)

        // 4. Read-back verification.
        onProgress(Progress.Verifying)
        val readBack = session.readRegister(escAddress, RegisterMap.SPEED_LIMIT)
            ?.firstOrNull()?.toInt()?.and(0xFF)
        val verified = readBack == target

        if (!verified) {
            Timber.w("Maneuver verification failed: expected %d, read %s", target, readBack)
        }

        val result = Result(
            previousRegion = prevRegion,
            previousSpeedLimit = prevSpeed,
            newSpeedLimit = target,
            verified = verified,
        )
        onProgress(Progress.Complete(result))
        return kotlin.Result.success(result)
    }

    /** Restore the pre-maneuver values captured by [execute]. */
    suspend fun revert(
        escAddress: Int = NinebotProtocol.ADDR_ESC,
        result: Result,
        onProgress: (Progress) -> Unit = {},
    ): kotlin.Result<Unit> {
        onProgress(Progress.Writing("RESTORE"))
        result.previousRegion?.let {
            session.send(NinebotProtocol.buildWriteByte(escAddress, RegisterMap.REGION, it))
            delay(INTER_WRITE_DELAY_MS)
        }
        result.previousSpeedLimit?.let {
            session.send(NinebotProtocol.buildWriteByte(escAddress, RegisterMap.SPEED_LIMIT, it))
            delay(INTER_WRITE_DELAY_MS)
        }
        onProgress(Progress.Complete(result))
        return kotlin.Result.success(Unit)
    }

    private companion object {
        const val INTER_WRITE_DELAY_MS = 60L
    }
}
