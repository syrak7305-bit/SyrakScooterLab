
package com.syrak.scooterlab.data

import com.syrak.scooterlab.core.ble.BleManager
import com.syrak.scooterlab.core.ble.ConnectionState
import com.syrak.scooterlab.core.ble.DiscoveredScooter
import com.syrak.scooterlab.core.ble.GattEvent
import com.syrak.scooterlab.feature.telemetry.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

class ScooterRepository(
    val bleManager: BleManager,
    val scope: CoroutineScope
) {
    val scanResults = bleManager.scanResults
    val connectionState = bleManager.connectionState
    val gattEvents = bleManager.gattEvents
    val incoming = bleManager.incoming

    private val _telemetry = MutableStateFlow(Telemetry.Empty)
    val telemetry: StateFlow<Telemetry> = _telemetry.asStateFlow()

    fun startSimulation() {
        // Keine simulierten Messwerte als echte Scooter-Daten ausgeben.
        _telemetry.value = Telemetry.Empty
    }

    fun startScan(filtered: Boolean = true) {
        bleManager.startScan(filtered)
    }

    fun stopScan() {
        bleManager.stopScan()
    }

    suspend fun connect(scooter: DiscoveredScooter): Result<Unit> {
        return bleManager.connect(scooter)
    }

    fun disconnect() {
        bleManager.disconnect()
        _telemetry.value = Telemetry.Empty
    }

    suspend fun sendFrame(frame: ByteArray) {
        bleManager.write(frame)
    }

    suspend fun applyGermanManeuver(
        targetSpeedKmh: Int,
        onProgress: (String) -> Unit
    ): Result<ManeuverResult> {
        onProgress("Nicht verfügbar: kein geprüfter Controller-Befehl implementiert")
        return Result.failure(
            UnsupportedOperationException(
                "German Maneuver ist für diesen Controller noch nicht implementiert."
            )
        )
    }

    suspend fun engageKillSwitch(onProgress: (String) -> Unit) {
        onProgress("Nicht verfügbar: kein geprüfter Kill-Switch-Befehl implementiert")
    }

    suspend fun releaseKillSwitch(onProgress: (String) -> Unit) {
        onProgress("Nicht verfügbar: kein geprüfter Kill-Switch-Befehl implementiert")
    }

    fun release() {
        bleManager.release()
    }
}

data class ManeuverResult(
    val newSpeedLimit: Int,
    val verified: Boolean
)
