package de.minipc.appopsshizuku

import android.app.Application

/**
 * Application-Einstieg. Die Material-You-Systemfarben werden NICHT mehr hier per
 * DynamicColors.applyToActivitiesIfAvailable() erzwungen: dessen Lifecycle-Callback
 * wuerde die Wahl des Nutzers ueberschreiben. Stattdessen ruft jede Activity
 * DesignSettings.applyDynamicColorsIfEnabled() auf (Einstellung "Systemfarben").
 *
 * Hier startet nur das lokale Fehlerprotokoll (Absturz-Handler).
 */
class AppOpsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ErrorLog.init(this)
    }
}
