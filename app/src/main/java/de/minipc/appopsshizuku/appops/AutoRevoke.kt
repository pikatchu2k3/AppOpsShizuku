package de.minipc.appopsshizuku.appops

/**
 * Anzeige (nur lesen) der Android-Automatik "Berechtigungen bei Nichtnutzung entziehen" (Op
 * AUTO_REVOKE_PERMISSIONS_IF_UNUSED). Achtung: `ignore` heisst hier "von der Automatik ausgenommen",
 * NICHT "blockiert".
 */
object AutoRevoke {
    const val OP = "AUTO_REVOKE_PERMISSIONS_IF_UNUSED"

    enum class State { ACTIVE, EXEMPT, UNSUPPORTED }

    /** Ausgabe von `cmd appops get <pkg> AUTO_REVOKE_PERMISSIONS_IF_UNUSED`; leer/unbekannt -> UNSUPPORTED. */
    fun parse(exitCode: Int, stdout: String, stderr: String = ""): State {
        val text = stdout + "\n" + stderr
        if (exitCode != 0 || stdout.isBlank() || text.contains("Unknown operation", ignoreCase = true) ||
            text.contains("Error", ignoreCase = true)
        ) return State.UNSUPPORTED
        val mode = Regex("$OP: (\\w+)").find(stdout)?.groupValues?.get(1)
        return if (mode == "ignore") State.EXEMPT else State.ACTIVE
    }
}
