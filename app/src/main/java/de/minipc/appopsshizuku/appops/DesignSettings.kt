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

    /** Ablage der Design-Einstellungen; die Sternenhimmel-View hoert darauf, um sofort zu reagieren. */
    const val PREFS = "design"
    const val KEY_STAR_ANIMATION = "star_animation"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Bewegter Sternenhimmel (Funkeln, Sternschnuppe). Standard: AN; stoppt zusaetzlich automatisch bei Energiesparmodus. */
    fun isStarAnimationEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STAR_ANIMATION, true)

    fun setStarAnimationEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_STAR_ANIMATION, enabled).apply()
    }

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
