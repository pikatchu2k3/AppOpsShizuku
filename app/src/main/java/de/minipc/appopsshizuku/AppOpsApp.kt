package de.minipc.appopsshizuku

import android.app.Application
import com.google.android.material.color.DynamicColors

/**
 * Application-Einstieg: aktiviert Material-You-Systemfarben auf Android 12+.
 * Auf aelteren Geraeten passiert nichts — die Baseline-M3-Palette aus
 * themes.xml bleibt als Fallback aktiv.
 */
class AppOpsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
