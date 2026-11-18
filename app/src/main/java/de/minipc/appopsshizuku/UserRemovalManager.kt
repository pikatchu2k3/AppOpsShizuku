package de.minipc.appopsshizuku

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import de.minipc.appopsshizuku.appops.DisableChain
import de.minipc.appopsshizuku.appops.UserRemoval
import de.minipc.appopsshizuku.appops.UserRemovalStore
import de.minipc.appopsshizuku.shizuku.ShellBackend

/**
 * Shell-Schicht fuer "Fuer diesen Nutzer entfernen" ([UserRemoval]). Erfolg zaehlt nur nach dem
 * Zuruecklesen ueber den PackageManager (FLAG_INSTALLED fuer den aktuellen Nutzer).
 */
object UserRemovalManager {

    data class Outcome(val ok: Boolean, val reason: String?)

    /** true = fuer den Nutzer installiert, false = entfernt (APK noch im System), null = unbekannt. */
    fun installedForUser(context: Context, pkg: String): Boolean? = runCatching {
        @Suppress("DEPRECATION")
        val ai = context.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
        (ai.flags and ApplicationInfo.FLAG_INSTALLED) != 0
    }.getOrNull()

    fun homePackage(context: Context): String? = runCatching {
        context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
    }.getOrNull()

    fun keyboardPackage(context: Context, backend: ShellBackend?): String? {
        val local = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrNull()
        val raw = local ?: backend?.exec("settings", "get", "secure", "default_input_method")?.stdout
        return UserRemoval.keyboardPackage(raw)
    }

    fun blockReason(context: Context, backend: ShellBackend?, pkg: String): UserRemoval.Block? =
        UserRemoval.blockReason(pkg, context.packageName, homePackage(context), keyboardPackage(context, backend))

    /** `pm uninstall -k --user 0` (Daten bleiben), danach zuruecklesen. */
    fun remove(context: Context, backend: ShellBackend, pkg: String): Outcome {
        if (blockReason(context, backend, pkg) != null) return Outcome(false, null)
        val res = backend.exec("pm", "uninstall", "-k", "--user", "0", pkg)
        val gone = installedForUser(context, pkg) == false
        if (gone) UserRemovalStore(context).add(pkg, System.currentTimeMillis())
        return Outcome(gone, if (gone) null else UserRemoval.reason(res.stdout + "\n" + res.stderr) ?: res.stdout.trim())
    }

    /** `cmd package install-existing --user 0` (Rueckfall `pm install-existing`), danach zuruecklesen. */
    fun restore(context: Context, backend: ShellBackend, pkg: String): Outcome {
        var res = backend.exec("cmd", "package", "install-existing", "--user", "0", pkg)
        if (installedForUser(context, pkg) != true) {
            res = backend.exec("pm", "install-existing", "--user", "0", pkg)
        }
        val back = installedForUser(context, pkg) == true
        if (back) UserRemovalStore(context).remove(pkg)
        return Outcome(back, if (back) null else UserRemoval.reason(res.stdout + "\n" + res.stderr) ?: res.stdout.trim())
    }

    /** Neutralisierte Apps (fuer das Zuruecknehmen beim Auftauen). */
    private fun neutralizedPrefs(context: Context) =
        context.applicationContext.getSharedPreferences("neutralized", Context.MODE_PRIVATE)

    fun isNeutralized(context: Context, pkg: String) = neutralizedPrefs(context).contains(pkg)

    private fun enabledState(context: Context, pkg: String): Int? =
        runCatching { context.packageManager.getApplicationEnabledSetting(pkg) }.getOrNull()

    private fun suspended(context: Context, pkg: String): Boolean = runCatching {
        android.os.Build.VERSION.SDK_INT >= 29 && context.packageManager.isPackageSuspended(pkg)
    }.getOrDefault(false)

    /**
     * Ausweich-Kette ab [from]: probiert die Schritte der Reihe nach, jeder wird zurueckgelesen,
     * und hoert beim ersten wirksamen auf. Kritische Apps / Startbildschirm / Tastatur: gar nichts.
     */
    fun runChain(
        context: Context,
        backend: ShellBackend,
        pkg: String,
        from: DisableChain.Step = DisableChain.Step.DISABLE_USER
    ): List<DisableChain.Attempt> {
        if (blockReason(context, backend, pkg) != null) return emptyList()
        val out = mutableListOf<DisableChain.Attempt>()
        for (step in DisableChain.ORDER.dropWhile { it != from }) {
            val attempt = when (step) {
                DisableChain.Step.NEUTRALIZE -> neutralize(context, backend, pkg)
                DisableChain.Step.REMOVE_FOR_USER -> remove(context, backend, pkg).let {
                    DisableChain.Attempt(step, it.ok, it.reason)
                }
                DisableChain.Step.HIDE_AS_OWNER ->
                    if (!DeviceOwnerManager.isOwner(context)) {
                        DisableChain.Attempt(step, false, context.getString(R.string.owner_not_set))
                    } else DeviceOwnerManager.setHidden(context, pkg, true).let {
                        DisableChain.Attempt(step, it.ok, it.reason)
                    }
                else -> {
                    val res = backend.exec(*DisableChain.command(step, pkg)!!.toTypedArray())
                    val ok = DisableChain.isDisabledState(enabledState(context, pkg))
                    DisableChain.Attempt(
                        step, ok,
                        if (ok) null else UserRemoval.reason(res.stdout + "\n" + res.stderr) ?: res.stdout.trim().ifEmpty { null }
                    )
                }
            }
            out += attempt
            if (attempt.ok) break
        }
        return out
    }

    /** Einfrieren + beenden + Hintergrund sperren; ok nur, wenn die App danach eingefroren ist. */
    private fun neutralize(context: Context, backend: ShellBackend, pkg: String): DisableChain.Attempt {
        val res = backend.exec("pm", "suspend", "--user", "0", pkg)
        backend.exec("am", "force-stop", pkg)
        backend.exec("cmd", "appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", "ignore")
        backend.exec("cmd", "appops", "set", pkg, "RUN_IN_BACKGROUND", "ignore")
        backend.exec("am", "set-standby-bucket", pkg, "restricted")
        val ok = suspended(context, pkg)
        if (ok) neutralizedPrefs(context).edit().putLong(pkg, System.currentTimeMillis()).apply()
        return DisableChain.Attempt(
            DisableChain.Step.NEUTRALIZE, ok,
            if (ok) null else UserRemoval.reason(res.stdout + "\n" + res.stderr) ?: res.stdout.trim().ifEmpty { null }
        )
    }

    /** Neutralisieren zuruecknehmen (beim Auftauen): Hintergrund-Sperren wieder auf Standard. */
    fun undoNeutralize(context: Context, backend: ShellBackend, pkg: String) {
        if (!isNeutralized(context, pkg)) return
        backend.exec("cmd", "appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", "default")
        backend.exec("cmd", "appops", "set", pkg, "RUN_IN_BACKGROUND", "default")
        backend.exec("am", "set-standby-bucket", pkg, "active")
        neutralizedPrefs(context).edit().remove(pkg).apply()
    }

    /**
     * Fuer den Nutzer entfernte Apps: alle von uns entfernten plus System-Apps, deren APK noch da ist
     * (auch von anderen Debloatern entfernt). Nutzer-Apps, die nur in einem anderen Profil liegen,
     * bleiben aussen vor - die hat niemand "entfernt".
     */
    fun removedApps(context: Context): List<ApplicationInfo> {
        val ours = UserRemovalStore(context).all().keys
        @Suppress("DEPRECATION")
        val all = runCatching {
            context.packageManager.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
        }.getOrDefault(emptyList())
        return all.filter { ai ->
            (ai.flags and ApplicationInfo.FLAG_INSTALLED) == 0 &&
                ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 || ai.packageName in ours)
        }
    }
}
