package com.syrak.scooterlab

import android.app.Application
import com.syrak.scooterlab.di.AppContainer
import timber.log.Timber

class SyrakApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        container = AppContainer(this)
        Timber.i("Syrak ScooterLab initialised")
    }
}
