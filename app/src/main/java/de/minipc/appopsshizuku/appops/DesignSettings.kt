package de.minipc.appopsshizuku.appops

import android.app.Activity
import android.content.Context
import com.google.android.material.color.DynamicColors

/**
 * Design-Einstellung: Systemfarben (Material You) oder feste Petrol-Palette.
 * Standard: Systemfarben AN. Wirkt nur auf Android 12+ (sonst gilt immer Petrol).
 */
object DesignSettings {

    private const val KEY = "dynamic_color"

    private fun prefs(context: Context) =
        context.getSharedPreferences("design", Context.MODE_PRIVATE)

    fun isDynamicColorEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY, true)

    fun setDynamicColorEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY, enabled).apply()
    }

    /** In onCreate NACH super.onCreate und VOR setContentView aufrufen. */
    fun applyDynamicColorsIfEnabled(activity: Activity) {
        if (isDynamicColorEnabled(activity)) DynamicColors.applyToActivityIfAvailable(activity)
    }
}
