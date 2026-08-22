package de.minipc.appopsshizuku.shizuku

/** Fallback wenn Shizuku nicht verfuegbar ist — liefert nur Fehler. */
class NoopBackend : ShellBackend {
    override fun isAvailable(): Boolean = false
    override fun hasPermission(): Boolean = false
    override fun exec(vararg args: String): ShellResult =
        ShellResult(-1, "", "Shizuku nicht verfuegbar")
}
