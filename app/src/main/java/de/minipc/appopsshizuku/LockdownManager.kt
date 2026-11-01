package de.minipc.appopsshizuku

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import de.minipc.appopsshizuku.appops.AppOpsController
import de.minipc.appopsshizuku.appops.Lockdown
import de.minipc.appopsshizuku.appops.LockdownStore
import de.minipc.appopsshizuku.appops.PrivacyOps
import de.minipc.appopsshizuku.shizuku.ShizukuBackend

/**
 * Lockdown-Szene: ausgewaehlte Berechtigungen (Standard: Kamera + Mikrofon) allen Nutzer-Apps entziehen.
 * Beim Beenden bekommen GENAU die Apps sie zurueck, denen sie hier entzogen wurden. System-Apps und die
 * eigene App nie. Jede Aenderung wird ueber die Grant-Flags des PackageManagers zurueckgelesen.
 * Aufrufbar aus der Seite "Systemweit" und aus der Schnellstart-Kachel.
 */
object LockdownManager {
    private const val TAG = "AppOps.Lockdown"

    data class Result(val changed: Int, val failed: Int, val noShizuku: Boolean = false)

    /** Shizuku kann nach einem frischen Prozessstart (Kachel) kurz brauchen, bis der Binder da ist. */
    private fun backend(waitMs: Long = 3_000): ShizukuBackend? {
        val b = ShizukuBackend()
        val until = System.currentTimeMillis() + waitMs
        while (true) {
            if (runCatching { b.isAvailable() && b.hasPermission() }.getOrDefault(false)) return b
            if (System.currentTimeMillis() >= until) return null
            Thread.sleep(200)
        }
    }

    private fun granted(pi: PackageInfo, permission: String): Boolean? {
        val idx = pi.requestedPermissions?.indexOf(permission) ?: -1
        if (idx < 0) return null
        val flags = pi.requestedPermissionsFlags?.getOrNull(idx) ?: return null
        return (flags and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
    }

    private fun isGranted(context: Context, pkg: String, permission: String): Boolean? =
        runCatching { context.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS) }
            .getOrNull()?.let { granted(it, permission) }

    @Synchronized
    fun activate(context: Context): Result {
        val app = context.applicationContext
        val store = LockdownStore(app)
        val backend = backend() ?: return Result(0, 0, noShizuku = true)
        val ops = store.ops
        val catalog = PrivacyOps.PRIVACY_OPS.filter { it.opName in ops && it.permissionName != null }
        val grantedNow = app.packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            .filter { pi ->
                val ai = pi.applicationInfo
                ai != null && (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && pi.packageName != app.packageName
            }
            .associate { pi ->
                pi.packageName to catalog.filter { granted(pi, it.permissionName!!) == true }.map { it.opName }.toSet()
            }
            .filterValues { it.isNotEmpty() }
        val controller = AppOpsController(backend)
        val revoked = store.revoked.toMutableSet()
        var failed = 0
        for ((pkg, opName) in Lockdown.plan(grantedNow, ops)) {
            val op = catalog.first { it.opName == opName }
            controller.setMode(pkg, op, "deny")
            if (isGranted(app, pkg, op.permissionName!!) == false) revoked += Lockdown.key(pkg, opName) else failed++
        }
        store.revoked = revoked
        if (!store.active) store.since = System.currentTimeMillis()
        store.active = true
        Log.i(TAG, "Lockdown an: ${revoked.size} entzogen, $failed fehlgeschlagen")
        runCatching {
            de.minipc.appopsshizuku.appops.ChangeLog.recordInfo(app, app.getString(R.string.log_info_lockdown_on, revoked.size))
        }
        return Result(revoked.size, failed)
    }

    @Synchronized
    fun deactivate(context: Context): Result {
        val app = context.applicationContext
        val store = LockdownStore(app)
        val backend = backend() ?: return Result(0, 0, noShizuku = true)
        val controller = AppOpsController(backend)
        val left = mutableSetOf<String>()
        var restored = 0
        for (key in store.revoked) {
            val (pkg, opName) = Lockdown.parseKey(key) ?: continue
            val op = PrivacyOps.PRIVACY_OPS.firstOrNull { it.opName == opName && it.permissionName != null } ?: continue
            // Op steht auf "Nur waehrend Nutzung": die erteilt der Waechter, nicht das Lockdown-Ende
            if (de.minipc.appopsshizuku.appops.WhileInUseStore(app).has(pkg, opName)) continue
            when (isGranted(app, pkg, op.permissionName!!)) {
                null -> Unit // deinstalliert oder fordert sie nicht mehr an: vergessen
                true -> restored++ // schon wieder erteilt (z. B. von Hand)
                false -> {
                    controller.setMode(pkg, op, "allow")
                    if (isGranted(app, pkg, op.permissionName) == true) restored++ else left += key
                }
            }
        }
        store.revoked = left
        store.active = left.isNotEmpty()
        Log.i(TAG, "Lockdown aus: $restored zurueckgegeben, ${left.size} offen")
        runCatching {
            de.minipc.appopsshizuku.appops.ChangeLog.recordInfo(app, app.getString(R.string.log_info_lockdown_off, restored))
        }
        return Result(restored, left.size)
    }

    /** Waehrend des Lockdowns darf "Nur waehrend Nutzung" diese Ops nicht erteilen. */
    fun blocksGrant(context: Context, opName: String): Boolean {
        val store = LockdownStore(context.applicationContext)
        return store.active && opName in store.ops
    }
}
