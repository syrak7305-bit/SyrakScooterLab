package com.syrak.scooterlab.data

import com.syrak.scooterlab.core.ble.BleManager
import com.syrak.scooterlab.core.ble.ConnectionState
import com.syrak.scooterlab.core.ble.DiscoveredScooter
import com.syrak.scooterlab.core.ble.GattEvent
import com.syrak.scooterlab.core.protocol.NinebotProtocol
import com.syrak.scooterlab.core.protocol.NinebotSession
import com.syrak.scooterlab.core.protocol.RegisterMap
import com.syrak.scooterlab.feature.safety.KillSwitch
import com.syrak.scooterlab.feature.telemetry.NinebotTelemetrySource
import com.syrak.scooterlab.feature.telemetry.SimulatedTelemetrySource
import com.syrak.scooterlab.feature.telemetry.Telemetry
import com.syrak.scooterlab.feature.telemetry.TelemetrySource
import com.syrak.scooterlab.feature.tuning.GermanManeuver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Single façade the UI talks to. Owns the composition of transport + protocol +
 * features and exposes immutable streams of state. No Android UI types leak out.
 */
class ScooterRepository(
    private val ble: BleManager,
    private val scope: CoroutineScope,
) {
    private val session = NinebotSession(ble, scope)

    val germanManeuver = GermanManeuver(session)
    val killSwitch = KillSwitch(session)

    private val realTelemetry: TelemetrySource = NinebotTelemetrySource(session)
    private val simulatedTelemetry: TelemetrySource = SimulatedTelemetrySource()

    val scanResults: StateFlow<List<DiscoveredScooter>> = ble.scanResults
    val connectionState: StateFlow<ConnectionState> = ble.connectionState
    val gattEvents: SharedFlow<GattEvent> = ble.gattEvents

    private val _telemetry = MutableStateFlow(Telemetry.Empty)
    val telemetry: StateFlow<Telemetry> = _telemetry.asStateFlow()

    private var telemetryJob: Job? = null

    /* ------------------------------ Discovery -------------------------------- */

    fun startScan(filtered: Boolean = true) = ble.startScan(filtered)
    fun stopScan() = ble.stopScan()
    val isBluetoothEnabled: Boolean get() = ble.isBluetoothEnabled

    /* ------------------------------ Connection ------------------------------- */

    suspend fun connect(scooter: DiscoveredScooter): Result<Unit> {
        val result = ble.connect(scooter)
        if (result.isSuccess) startTelemetry(realTelemetry) else startTelemetry(simulatedTelemetry)
        return result
    }

    fun disconnect() {
        telemetryJob?.cancel()
        telemetryJob = null
        ble.disconnect()
        startTelemetry(simulatedTelemetry)
    }

    /** Drive the dashboard with synthetic data (development / demo only). */
    fun startSimulation() = startTelemetry(simulatedTelemetry)

    private fun startTelemetry(source: TelemetrySource) {
        telemetryJob?.cancel()
        telemetryJob = scope.launch {
            source.stream().collect { _telemetry.value = it }
        }
    }

    /* ------------------------------- Features -------------------------------- */

    suspend fun readRegister(
        destination: Int = NinebotProtocol.ADDR_ESC,
        register: Int,
    ): ByteArray? = session.readRegister(destination, register)

    suspend fun applyGermanManeuver(
        targetSpeedKmh: Int = 30,
        onProgress: (GermanManeuver.Progress) -> Unit = {},
    ): Result<GermanManeuver.Result> =
        germanManeuver.execute(targetSpeedKmh = targetSpeedKmh, onProgress = onProgress)

    suspend fun engageKillSwitch(onStep: (String) -> Unit = {}): Boolean =
        killSwitch.engage(onStep = onStep)

    suspend fun releaseKillSwitch(onStep: (String) -> Unit = {}): Boolean =
        killSwitch.release(onStep = onStep)

    val isKillSwitchEngaged: Boolean get() = killSwitch.isEngaged

    /** Read the controller serial number (diagnostics / auth key material). */
    suspend fun readSerial(): String? {
        val bytes = session.readRegister(NinebotProtocol.ADDR_ESC, RegisterMap.SERIAL_NUMBER) ?: return null
        return bytes.joinToString("") { "%02X".format(it) }
    }

    fun release() {
        telemetryJob?.cancel()
        ble.release()
    }
}
