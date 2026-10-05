package de.minipc.appopsshizuku

import android.app.Application

/**
 * Application-Einstieg. Die Material-You-Systemfarben werden NICHT mehr hier per
 * DynamicColors.applyToActivitiesIfAvailable() erzwungen: dessen Lifecycle-Callback
 * wuerde die Wahl des Nutzers ueberschreiben. Stattdessen ruft jede Activity
 * DesignSettings.applyDynamicColorsIfEnabled() auf (Einstellung "Systemfarben").
 */
class AppOpsApp : Application()
