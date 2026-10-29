package com.syrak.scooterlab

import android.app.Application
import com.syrak.scooterlab.di.AppContainer
import timber.log.Timber

/**
 * Application entry point.
 *
 * WICHTIG (Ressourcen-Fix):
 *   [AppContainer] erzeugt einen `BleManager` (Handler + GATT-State) und einen
 *   app-weiten CoroutineScope. Dieser Container wird vom ausgelieferten
 *   Produktionspfad (`MainActivity` -> `BleEngine`) NICHT konsumiert. Er wurde
 *   zuvor bei jedem App-Start eifrig aufgebaut und blieb ungenutzt liegen.
 *   Jetzt ist er `by lazy`: er wird erst erzeugt, wenn ihn tatsächlich jemand
 *   anspricht. Die öffentliche API (`container`) bleibt unverändert.
 */
class SyrakApp : Application() {

    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContainer(this)
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.i("Syrak ScooterLab initialised")
    }
}
