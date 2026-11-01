package de.minipc.appopsshizuku

import android.content.Context
import android.util.Log
import de.minipc.appopsshizuku.appops.WorkProfile
import de.minipc.appopsshizuku.shizuku.ShellBackend

/**
 * Shell-Schicht fuer das Arbeitsprofil. Ein Profil ANLEGEN braucht einen Profilbesitzer; das versucht
 * diese App ueber einen eigenen DeviceAdminReceiver (dpm set-profile-owner). Apps in ein VORHANDENES
 * Profil zu verschieben geht allein mit Shell-Rechten (pm install-existing/uninstall --user).
 *
 * Jede Aktion liest den Ist-Zustand zurueck; was das Geraet ablehnt, wird als Klartext gemeldet, nicht
 * stillschweigend als Erfolg verbucht.
 */
class WorkProfileManager(private val backend: ShellBackend, private val ownPackage: String) {

    private val receiver get() = "$ownPackage/de.minipc.appopsshizuku.ProfileOwnerReceiver"

    private fun text(vararg args: String) = backend.exec(*args).let { it.stdout + "\n" + it.stderr }

    fun users(): List<WorkProfile.User> = WorkProfile.parseUsers(text("pm", "list", "users"))

    fun managedProfile(): WorkProfile.User? = WorkProfile.managedProfile(users())

    /** Paketname des Profilbesitzers (null = keiner / nicht lesbar). */
    fun profileOwner(userId: Int): String? =
        WorkProfile.parseProfileOwner(text("dumpsys", "device_policy"), userId)

    data class Created(val userId: Int?, val owner: Boolean, val raw: String)

    /**
     * Arbeitsprofil anlegen, diese App hineininstallieren, als Profilbesitzer setzen und starten.
     * userId != null = Profil existiert; owner = set-profile-owner hat gegriffen (fuer Verschieben
     * nicht noetig, aber fuer Richtlinien/Entfernen sauberer).
     */
    fun createProfile(name: String): Created {
        val create = text("pm", "create-user", "--profileOf", "0", "--managed", name)
        val id = WorkProfile.parseCreatedUserId(create)
            ?: managedProfile()?.id
            ?: return Created(null, false, create)
        // Eigene App ins Profil (Voraussetzung fuer set-profile-owner)
        backend.exec("pm", "install-existing", "--user", id.toString(), ownPackage)
        val owner = text("dpm", "set-profile-owner", "--user", id.toString(), receiver)
        backend.exec("am", "start-user", id.toString())
        val isOwner = profileOwner(id) == ownPackage || owner.contains("Success", ignoreCase = true)
        Log.i("AppOps.WorkProfile", "createProfile id=$id owner=$isOwner")
        return Created(id, isOwner, create + "\n" + owner)
    }

    /** Ganzes Arbeitsprofil entfernen (alle darin liegenden App-Daten gehen verloren). */
    fun removeProfile(userId: Int): Boolean {
        backend.exec("pm", "remove-user", userId.toString())
        return managedProfile()?.id != userId
    }

    fun appsInProfile(userId: Int): Set<String> =
        WorkProfile.parsePackages(text("pm", "list", "packages", "--user", userId.toString()))

    /** App ins Profil (install-existing) oder daraus entfernen (uninstall --user). Mit Zuruecklesen. */
    fun moveApp(userId: Int, pkg: String, into: Boolean): Boolean {
        if (into) backend.exec("pm", "install-existing", "--user", userId.toString(), pkg)
        else backend.exec("pm", "uninstall", "--user", userId.toString(), pkg)
        val present = pkg in appsInProfile(userId)
        return present == into
    }
}
