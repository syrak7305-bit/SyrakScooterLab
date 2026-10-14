package com.syrak.scooterlab.di

import android.content.Context
import com.syrak.scooterlab.core.ble.BleManager
import com.syrak.scooterlab.data.ScooterRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {

    val applicationScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val bleManager: BleManager =
        BleManager(context.applicationContext, applicationScope)

    val repository: ScooterRepository =
        ScooterRepository(bleManager, applicationScope)
}