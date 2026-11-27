package de.minipc.appopsshizuku

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Process
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import de.minipc.appopsshizuku.appops.OnDemand
import de.minipc.appopsshizuku.shizuku.ShellBackend

/**
 * Android-Teil von "Nur bei Bedarf": Nutzungszugriff (um das Verlassen der App zu erkennen)
 * und die Launcher-Verknuepfung. Die Entscheidungslogik liegt in [OnDemand].
 */
object OnDemandSupport {

    fun shortcutId(packageName: String) = "ondemand_$packageName"

    /** Darf unsere App Nutzungsereignisse lesen? (AppOp GET_USAGE_STATS) */
    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Nutzungszugriff fuer die EIGENE App per Shizuku erteilen (sonst Einstellungen > Nutzungszugriff). */
    fun grantUsageAccess(context: Context, backend: ShellBackend): Boolean {
        if (hasUsageAccess(context)) return true
        backend.exec("cmd", "appops", "set", context.packageName, "GET_USAGE_STATS", "allow")
        return hasUsageAccess(context)
    }

    /**
     * Ist [packageName] gerade im Vordergrund? Bevorzugt ueber Nutzungsereignisse seit [sinceMs];
     * ohne Nutzungszugriff ueber `dumpsys activity activities`. null = keine Aussage.
     */
    fun isForeground(context: Context, backend: ShellBackend?, packageName: String, sinceMs: Long): Boolean? {
        if (hasUsageAccess(context)) {
            val usm = context.getSystemService(UsageStatsManager::class.java)
            val events = usm.queryEvents(sinceMs, System.currentTimeMillis())
            val types = ArrayList<Pair<String?, Int>>()
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.packageName == packageName) types.add(e.className to e.eventType)
            }
            OnDemand.foregroundFromActivityEvents(types)?.let { return it }
        }
        if (backend == null) return null
        val dump = backend.exec("dumpsys", "activity", "activities")
        return if (dump.exitCode == 0) OnDemand.foregroundFromDumpsys(dump.stdout, packageName) else null
    }

    /**
     * Vordergrund-Zustand mehrerer Apps mit EINER Abfrage (Nutzungsereignisse seit [sinceMs]),
     * sonst ein einziges `dumpsys activity activities`. null je App = keine Aussage.
     */
    fun foregroundMap(
        context: Context,
        backend: ShellBackend?,
        packages: Set<String>,
        sinceMs: Long
    ): Map<String, Boolean?> {
        if (packages.isEmpty()) return emptyMap()
        if (hasUsageAccess(context)) {
            val usm = context.getSystemService(UsageStatsManager::class.java)
            val events = usm.queryEvents(sinceMs, System.currentTimeMillis())
            val types = HashMap<String, ArrayList<Pair<String?, Int>>>()
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.packageName in packages) types.getOrPut(e.packageName) { ArrayList() }.add(e.className to e.eventType)
            }
            return packages.associateWith { p -> types[p]?.let { OnDemand.foregroundFromActivityEvents(it) } }
        }
        if (backend == null) return packages.associateWith { null }
        val dump = backend.exec("dumpsys", "activity", "activities")
        if (dump.exitCode != 0) return packages.associateWith { null }
        return packages.associateWith { OnDemand.foregroundFromDumpsys(dump.stdout, it) }
    }

    fun canPinShortcut(context: Context): Boolean = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /**
     * Verknuepfung mit Icon und Namen der Ziel-App anlegen. Der Launcher fragt selbst nach.
     * Ziel ist IMMER unsere nicht exportierte [OnDemandLaunchActivity] (Start als unsere App).
     */
    fun requestPinShortcut(context: Context, packageName: String): Boolean {
        if (!canPinShortcut(context)) return false
        val pm = context.packageManager
        val ai = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull() ?: return false
        val label = ai.loadLabel(pm).toString()
        val icon = IconCompat.createWithBitmap(drawableToBitmap(ai.loadIcon(pm)))
        val info = ShortcutInfoCompat.Builder(context, shortcutId(packageName))
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(icon)
            .setIntent(OnDemandLaunchActivity.intent(context, packageName))
            .build()
        // Bereits angeheftet, aber deaktiviert (Modus war aus)? Wieder einschalten.
        enableShortcut(context, packageName)
        return ShortcutManagerCompat.requestPinShortcut(context, info, null)
    }

    /** Modus aus: angeheftete Verknuepfung ausgrauen (entfernen darf nur der Nutzer). */
    fun disableShortcut(context: Context, packageName: String, message: CharSequence) {
        runCatching { ShortcutManagerCompat.disableShortcuts(context, listOf(shortcutId(packageName)), message) }
    }

    fun enableShortcut(context: Context, packageName: String) {
        runCatching { ShortcutManagerCompat.enableShortcuts(context, listOf(
            ShortcutInfoCompat.Builder(context, shortcutId(packageName))
                .setShortLabel(packageName)
                .setIntent(OnDemandLaunchActivity.intent(context, packageName))
                .build()
        )) }
    }

    /** Ist fuer die App schon eine Verknuepfung angeheftet? (nur Android 7.1+, sonst null) */
    fun isPinned(context: Context, packageName: String): Boolean? = runCatching {
        context.getSystemService(ShortcutManager::class.java).pinnedShortcuts.any {
            it.id == shortcutId(packageName) && it.isEnabled
        }
    }.getOrNull()

    private fun drawableToBitmap(d: android.graphics.drawable.Drawable): Bitmap {
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return bmp
    }

    /** Intent fuer den Start der Ziel-App nach dem Freigeben. */
    fun launchIntentFor(context: Context, packageName: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
