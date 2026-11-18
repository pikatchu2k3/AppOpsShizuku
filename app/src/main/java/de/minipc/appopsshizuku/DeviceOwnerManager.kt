package de.minipc.appopsshizuku

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import de.minipc.appopsshizuku.appops.DeviceOwner
import de.minipc.appopsshizuku.appops.HiddenStore
import de.minipc.appopsshizuku.appops.UserRemoval
import de.minipc.appopsshizuku.shizuku.ShellBackend

/** Android-Teil von [DeviceOwner]: einrichten, abgeben, Apps verstecken/zeigen (mit Zuruecklesen). */
object DeviceOwnerManager {

    fun admin(context: Context) = ComponentName(context.packageName, ProfileOwnerReceiver::class.java.name)

    private fun dpm(context: Context) = context.getSystemService(DevicePolicyManager::class.java)

    fun isOwner(context: Context): Boolean =
        runCatching { dpm(context).isDeviceOwnerApp(context.packageName) }.getOrDefault(false)

    data class Setup(val result: DeviceOwner.SetupResult, val raw: String)

    /** `dpm set-device-owner` ueber die Shell; Erfolg nur, wenn wir danach wirklich Inhaber sind. */
    fun setup(context: Context, backend: ShellBackend): Setup {
        val comp = admin(context).flattenToShortString()
        val res = backend.exec("dpm", "set-device-owner", "--user", "0", comp)
        val raw = (res.stdout + "\n" + res.stderr).trim()
        if (isOwner(context)) return Setup(DeviceOwner.SetupResult.OK, raw)
        val c = DeviceOwner.classify(raw).let { if (it == DeviceOwner.SetupResult.OK) DeviceOwner.SetupResult.OTHER else it }
        return Setup(c, raw)
    }

    /**
     * Geraeteinhaber abgeben. Vorher werden alle von uns versteckten Apps wieder sichtbar gemacht -
     * sonst blieben sie versteckt, ohne dass jemand sie zurueckholen kann.
     */
    fun release(context: Context): Boolean {
        if (!isOwner(context)) return true
        for (pkg in HiddenStore(context).all()) setHidden(context, pkg, false)
        runCatching {
            @Suppress("DEPRECATION")
            dpm(context).clearDeviceOwnerApp(context.packageName)
        }
        return !isOwner(context)
    }

    fun isHidden(context: Context, pkg: String): Boolean =
        runCatching { dpm(context).isApplicationHidden(admin(context), pkg) }.getOrDefault(false)

    /** Verstecken/zeigen; Ergebnis = zurueckgelesener Zustand entspricht dem Wunsch. */
    fun setHidden(context: Context, pkg: String, hidden: Boolean): UserRemovalManager.Outcome {
        if (!isOwner(context)) return UserRemovalManager.Outcome(false, null)
        val err = runCatching { dpm(context).setApplicationHidden(admin(context), pkg, hidden) }.exceptionOrNull()
        val ok = isHidden(context, pkg) == hidden
        if (ok) {
            if (hidden) HiddenStore(context).add(pkg) else HiddenStore(context).remove(pkg)
        }
        return UserRemovalManager.Outcome(ok, if (ok) null else err?.message?.let { UserRemoval.reason(it) ?: it })
    }
}
