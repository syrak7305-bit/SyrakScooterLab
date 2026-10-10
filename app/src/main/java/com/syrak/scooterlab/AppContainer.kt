package com.syrak.scooterlab.di

import android.content.Context
import com.syrak.scooterlab.core.ble.BleManager
import com.syrak.scooterlab.data.ScooterRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Minimal, explicit dependency container.
 *
 * Deliberately framework-free: a single long-lived application scope owns the
 * BLE client and the repository, so the connection survives configuration
 * changes and Activity recreation. Swap for Hilt/Koin later without touching
 * consumers — the graph is defined in exactly one place.
 */
class AppContainer(context: Context) {

    /** Application-lifetime scope; never cancelled except at process death. */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val bleManager: BleManager = BleManager(context, applicationScope)

    val repository: ScooterRepository = ScooterRepository(bleManager, applicationScope)
}
