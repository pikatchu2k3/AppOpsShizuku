package de.minipc.appopsshizuku

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import de.minipc.appopsshizuku.shizuku.ShellBackend
import de.minipc.appopsshizuku.shizuku.ShellResult

/**
 * Dauerrechte: Shizuku laeuft nach einem Neustart nicht. Erteilt sich die App aber EINMAL ueber Shizuku
 * `WRITE_SECURE_SETTINGS` (pm grant, bleibt ueber Neustarts erhalten), kann sie Systemeinstellungen
 * danach selbst lesen und schreiben - privates DNS und die Liste der Bedienungshilfen funktionieren
 * dann auch ohne laufendes Shizuku.
 */
object StandingAccess {
    const val PERMISSION = Manifest.permission.WRITE_SECURE_SETTINGS

    fun has(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Ueber Shizuku erteilen; true nur, wenn das Recht danach wirklich da ist. */
    fun grant(context: Context, backend: ShellBackend): Boolean {
        backend.exec("pm", "grant", context.packageName, PERMISSION)
        return has(context)
    }

    fun revoke(context: Context, backend: ShellBackend): Boolean {
        backend.exec("pm", "revoke", context.packageName, PERMISSION)
        return !has(context)
    }
}

/**
 * Shell-Huelle: `settings get|put|delete <global|secure|system> <key> [wert]` wird mit Dauerrecht direkt
 * ueber den ContentResolver erledigt, alles andere (und alles ohne Dauerrecht) geht an [delegate].
 * So bleibt der aufrufende Code unveraendert und das Zuruecklesen funktioniert in beiden Faellen.
 */
class SettingsBackend(private val context: Context, private val delegate: ShellBackend) : ShellBackend {

    override fun isAvailable() = delegate.isAvailable()
    override fun hasPermission() = delegate.hasPermission()

    override fun exec(vararg args: String): ShellResult {
        if (args.size >= 4 && args[0] == "settings" && StandingAccess.has(context)) {
            direct(args[1], args[2], args[3], args.getOrNull(4))?.let { return it }
        }
        return delegate.exec(*args)
    }

    /** null = nicht direkt moeglich (unbekannter Bereich/Fehler) -> an die Shell weiterreichen. */
    private fun direct(verb: String, ns: String, key: String, value: String?): ShellResult? = runCatching {
        val cr = context.contentResolver
        when (verb) {
            "get" -> {
                val v = when (ns) {
                    "global" -> Settings.Global.getString(cr, key)
                    "secure" -> Settings.Secure.getString(cr, key)
                    "system" -> Settings.System.getString(cr, key)
                    else -> return null
                }
                ShellResult(0, (v ?: "null") + "\n", "")
            }
            "put", "delete" -> {
                val v = if (verb == "put") value ?: return null else null
                val ok = when (ns) {
                    "global" -> Settings.Global.putString(cr, key, v)
                    "secure" -> Settings.Secure.putString(cr, key, v)
                    else -> return null
                }
                if (ok) ShellResult(0, "", "") else null
            }
            else -> null
        }
    }.getOrNull()
}
