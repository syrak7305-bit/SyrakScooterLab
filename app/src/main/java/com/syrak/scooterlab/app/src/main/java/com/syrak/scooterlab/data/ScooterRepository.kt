
package com.syrak.scooterlab.data

import com.syrak.scooterlab.core.ble.BleManager
import kotlinx.coroutines.CoroutineScope

/**
 * Verbindet die App mit dem BLE-Manager.
 * Diese Klasse sendet keine erfundenen Tuning-Befehle.
 */
class ScooterRepository(
    val bleManager: BleManager,
    val scope: CoroutineScope
) {
    val scanResults = bleManager.scanResults
    val connectionState = bleManager.connectionState
    val gattEvents = bleManager.gattEvents
    val incoming = bleManager.incoming

    fun startScan(filtered: Boolean = true) {
        bleManager.startScan(filtered)
    }

    fun stopScan() {
        bleManager.stopScan()
    }

    suspend fun connect(
        scooter: com.syrak.scooterlab.core.ble.DiscoveredScooter
    ): Result<Unit> {
        return bleManager.connect(scooter)
    }

    fun disconnect() {
        bleManager.disconnect()
    }

    suspend fun sendFrame(frame: ByteArray) {
        bleManager.write(frame)
    }

    fun release() {
        bleManager.release()
    }
}
