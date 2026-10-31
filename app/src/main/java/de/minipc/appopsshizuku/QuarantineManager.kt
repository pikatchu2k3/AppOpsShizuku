package de.minipc.appopsshizuku

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import de.minipc.appopsshizuku.appops.AppOpsController
import de.minipc.appopsshizuku.appops.NetControl
import de.minipc.appopsshizuku.appops.NetStore
import de.minipc.appopsshizuku.appops.PrivacyOps
import de.minipc.appopsshizuku.appops.PrivacyProfileStore
import de.minipc.appopsshizuku.appops.Quarantine
import de.minipc.appopsshizuku.appops.QuarantineStore
import de.minipc.appopsshizuku.shizuku.ShellBackend
import de.minipc.appopsshizuku.shizuku.ShizukuBackend

/**
 * Android-Teil der Quarantaene ([Quarantine]): neue Apps finden, Quarantaene anwenden und aufheben.
 * Jede Aenderung wird zurueckgelesen und in denselben Speichern vermerkt wie die Handschalter
 * (Netz-Markierung, Privacy-Profil) - "Alles zuruecksetzen" in den Reitern bleibt damit korrekt.
 */
object QuarantineManager {
    private const val TAG = "AppOps.Quarantine"

    data class CheckResult(val applied: List<String>, val failed: List<String>)

    private fun usableBackend(): ShellBackend? =
        runCatching { ShizukuBackend().takeIf { it.isAvailable() && it.hasPermission() } }.getOrNull()

    /**
     * Neue Apps seit der letzten Pruefung in Quarantaene nehmen. null = Funktion aus oder Shizuku fehlt
     * (dann bleibt der Pruefzeitpunkt stehen, damit die Apps beim naechsten Mal erfasst werden).
     */
    @Synchronized
    fun check(context: Context): CheckResult? {
        val app = context.applicationContext
        val store = QuarantineStore(app)
        if (!store.enabled) return null
        val backend = usableBackend() ?: return null
        val now = System.currentTimeMillis()
        val pm = app.packageManager
        val candidates = pm.getInstalledPackages(0).mapNotNull { pi ->
            val ai = pi.applicationInfo ?: return@mapNotNull null
            Quarantine.Candidate(pi.packageName, pi.firstInstallTime, (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
        }
        val fresh = Quarantine.newPackages(candidates, store.checkedUntil, app.packageName, store.quarantined().keys)
        val applied = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (pkg in fresh) {
            if (apply(app, backend, pkg)) applied += pkg else failed += pkg
            store.add(pkg, now)
        }
        store.checkedUntil = now
        if (fresh.isNotEmpty()) Log.i(TAG, "Quarantaene: ok=$applied fehlgeschlagen=$failed")
        return CheckResult(applied, failed)
    }

    /** Quarantaene anwenden; true nur, wenn alle Teile zurueckgelesen bestaetigt sind. */
    fun apply(context: Context, backend: ShellBackend, pkg: String): Boolean {
        val uid = runCatching { context.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull() ?: return false
        val net = NetControl(backend)
        val netOk = net.set(Quarantine.NET, pkg, uid, true)
        if (netOk) NetStore(context).mark(pkg, Quarantine.NET, true)
        val controller = AppOpsController(backend)
        val profile = PrivacyProfileStore(context)
        var opsOk = true
        for (name in Quarantine.OPS) {
            val op = PrivacyOps.PRIVACY_OPS.firstOrNull { it.opName == name } ?: continue
            controller.setMode(pkg, op, Quarantine.OP_MODE)
            if (controller.getMode(pkg, op) == Quarantine.OP_MODE) {
                runCatching { profile.setOpMode(pkg, name, Quarantine.OP_MODE) }
            } else {
                opsOk = false
            }
        }
        return netOk && opsOk
    }

    /** Quarantaene aufheben: nur zuruecknehmen, was die Quarantaene gesetzt hat. */
    fun release(context: Context, pkg: String): Boolean {
        val app = context.applicationContext
        val backend = usableBackend() ?: return false
        var ok = true
        val netStore = NetStore(app)
        if (Quarantine.NET in netStore.get(pkg)) {
            val uid = runCatching { app.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull()
            if (uid != null && NetControl(backend).set(Quarantine.NET, pkg, uid, false)) {
                netStore.mark(pkg, Quarantine.NET, false)
            } else {
                ok = false
            }
        }
        val controller = AppOpsController(backend)
        val profile = PrivacyProfileStore(app)
        for (name in Quarantine.OPS) {
            val op = PrivacyOps.PRIVACY_OPS.firstOrNull { it.opName == name } ?: continue
            controller.setMode(pkg, op, "default")
            // Systemstandard dieser Ops ist "allow" - je nach Android liest sich das als default oder allow
            if (controller.getMode(pkg, op) in setOf("default", "allow")) {
                runCatching { profile.setOpMode(pkg, name, "default") }
            } else {
                ok = false
            }
        }
        if (ok) QuarantineStore(app).remove(pkg)
        return ok
    }
}
