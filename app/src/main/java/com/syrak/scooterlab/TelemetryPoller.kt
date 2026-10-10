package com.syrak.scooterlab.feature.telemetry

import com.syrak.scooterlab.core.protocol.NinebotProtocol
import com.syrak.scooterlab.core.protocol.NinebotSession
import com.syrak.scooterlab.core.protocol.RegisterMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.random.Random

/** Abstraction so the UI can run against real hardware or a development simulator. */
interface TelemetrySource {
    fun stream(intervalMs: Long = 250L): Flow<Telemetry>
}

/**
 * Reads ESC + BMS registers on a fixed cadence and decodes them into [Telemetry].
 *
 * Decoding scale factors are firmware-specific and documented inline; they are
 * centralised here so a per-model profile can override them later.
 *
 * All register reads are bounds-checked — a short or missing reply degrades that
 * single field to zero rather than crashing the poll loop.
 */
class NinebotTelemetrySource(
    private val session: NinebotSession,
    private val escAddress: Int = NinebotProtocol.ADDR_ESC,
    private val bmsAddress: Int = NinebotProtocol.ADDR_BMS,
) : TelemetrySource {

    override fun stream(intervalMs: Long): Flow<Telemetry> = flow {
        while (currentCoroutineContext().isActive) {
            emit(poll())
            delay(intervalMs)
        }
    }

    private suspend fun poll(): Telemetry {
        val speed = readEsc(RegisterMap.SPEED).u16() / 100f
        val voltage = readEsc(RegisterMap.VOLTAGE).u16() / 100f
        val current = readEsc(RegisterMap.CURRENT).i16() / 100f
        val motorTemp = readEsc(RegisterMap.MOTOR_TEMPERATURE).i16() / 10f
        val odo = readEsc(RegisterMap.ODOMETER).u32() / 1000f

        val battery = readBms(RegisterMap.BATTERY_LEVEL).firstByte()
        val cellMin = readBms(RegisterMap.CELL_VOLTAGE_MIN).u16() / 1000f
        val cellMax = readBms(RegisterMap.CELL_VOLTAGE_MAX).u16() / 1000f
        val battTemp = readBms(RegisterMap.BATTERY_TEMPERATURE).i16() / 10f

        return Telemetry(
            speedKmh = speed,
            voltageV = voltage,
            currentA = current,
            batteryPercent = battery,
            motorTempC = motorTemp,
            batteryTempC = battTemp,
            cellMinV = cellMin,
            cellMaxV = cellMax,
            odometerKm = odo,
        )
    }

    private suspend fun readEsc(register: Int): ByteArray? =
        session.readRegister(escAddress, register, timeoutMs = READ_TIMEOUT_MS)

    private suspend fun readBms(register: Int): ByteArray? =
        session.readRegister(bmsAddress, register, timeoutMs = READ_TIMEOUT_MS)

    /* ------------------------- Bounds-safe decoders ------------------------- */

    private fun ByteArray?.u16(): Int =
        if (this != null && size >= 2) NinebotProtocol.u16LE(this) else 0

    private fun ByteArray?.i16(): Int =
        if (this != null && size >= 2) NinebotProtocol.i16LE(this) else 0

    private fun ByteArray?.u32(): Long =
        if (this != null && size >= 4) NinebotProtocol.u32LE(this) else 0L

    private fun ByteArray?.firstByte(): Int =
        if (this != null && isNotEmpty()) this[0].toInt() and 0xFF else 0

    private companion object {
        /** Short timeout: a telemetry frame should never stall the whole dashboard. */
        const val READ_TIMEOUT_MS = 450L
    }
}

/**
 * Development-only simulator. Produces physically plausible, smoothly-varying
 * telemetry so the dashboard can be exercised without hardware attached.
 * It is NEVER selected while a real device is connected.
 */
class SimulatedTelemetrySource : TelemetrySource {
    private var phase = 0.0

    override fun stream(intervalMs: Long): Flow<Telemetry> = flow {
        var speed = 0f
        var battery = 87
        while (currentCoroutineContext().isActive) {
            phase += 0.08
            val target = (14f + 12f * kotlin.math.sin(phase)).toFloat()
            speed += (target - speed) * 0.25f
            val voltage = 40.8f - (speed / 30f) * 2.4f + Random.nextFloat() * 0.03f
            val current = (speed / 30f) * 11f + Random.nextFloat() * 0.4f
            battery = (battery - if (Random.nextFloat() > 0.985f) 1 else 0).coerceAtLeast(0)

            emit(
                Telemetry(
                    speedKmh = abs(speed),
                    voltageV = voltage,
                    currentA = current,
                    batteryPercent = battery,
                    motorTempC = 34f + speed * 0.5f,
                    batteryTempC = 28f + speed * 0.2f,
                    cellMinV = 3.62f + Random.nextFloat() * 0.01f,
                    cellMaxV = 3.68f + Random.nextFloat() * 0.01f,
                    odometerKm = 1284.6f,
                ),
            )
            delay(intervalMs)
        }
    }
}
