package de.minipc.appopsshizuku.appops

import de.minipc.appopsshizuku.shizuku.ShellBackend
import de.minipc.appopsshizuku.shizuku.ShellResult

/**
 * Privacy-Steuerung via Shell-Backend. Reine JVM-Logik (Unit-Tests ohne Android).
 *
 * Zwei Hebel, live auf Android 15/16 (Emulator) verifiziert:
 * - Ops MIT Runtime-Permission (Kamera, Mikrofon, Kontakte, ...): `pm revoke|grant`.
 *   Auf Android 15/16 folgt der AppOps-Uid-Mode der Permission — `cmd appops set`
 *   wird fuer diese Ops ignoriert (Uid mode bleibt foreground/ignore), pm revoke
 *   blockt zuverlaessig (Uid mode -> ignore), pm grant gibt frei (-> foreground).
 * - Reine AppOps OHNE Permission (READ_CLIPBOARD, WRITE_CLIPBOARD): `cmd appops set`.
 */
class AppOpsController(private val backend: ShellBackend) {

    /**
     * "default" = nicht blockiert (grant + appops default). Zugriff wieder normal.
     *
     * [userFixed]: Ein reines `pm revoke` laesst die App sofort wieder nach der Berechtigung fragen
     * (kein USER_FIXED-Flag). Beim Blockieren wird deshalb zusaetzlich `user-fixed` gesetzt - wie
     * "Nicht mehr fragen" im System-Dialog. Beim Erteilen und beim Zuruecksetzen ([userFixed] = false)
     * wird das Flag entfernt, damit "Standard" wieder "die App darf fragen" heisst.
     * Die Flag-Befehle sind Best-Effort (nicht jedes Android kennt `set-permission-flags`); massgeblich
     * bleibt das Ergebnis von grant/revoke, das zurueckgegeben wird.
     */
    fun setMode(packageName: String, op: AppOp, mode: String, userFixed: Boolean = true): ShellResult {
        val perm = op.permissionName
        return if (perm != null) {
            when (mode) {
                "deny", "ignore" -> backend.exec("pm", "revoke", packageName, perm).also {
                    if (it.exitCode == 0) {
                        val verb = if (userFixed) "set-permission-flags" else "clear-permission-flags"
                        backend.exec("pm", verb, packageName, perm, *USER_CHOICE_FLAGS)
                    }
                }
                else -> {
                    // allow + default: danach das Flag loesen, sonst bleibt "nicht mehr fragen" stehen
                    val res = backend.exec("pm", "grant", packageName, perm)
                    if (res.exitCode == 0) {
                        backend.exec("pm", "clear-permission-flags", packageName, perm, *USER_CHOICE_FLAGS)
                    }
                    res
                }
            }
        } else {
            backend.exec("cmd", "appops", "set", packageName, op.opName, mode)
        }
    }

    companion object {
        /** Flags fuer "Nutzer hat entschieden, nicht erneut fragen". */
        val USER_CHOICE_FLAGS = arrayOf("user-set", "user-fixed")
    }

    /**
     * Liest den aktuellen Modus. Gibt null zurück wenn die Op auf dem Gerät nicht lesbar ist.
     * Echtes Format von `cmd appops get` (Android 15/16, live verifiziert):
     *   "Uid mode: CAMERA: foreground"  -> granted, Zugriff normal  -> "allow"
     *   "Uid mode: CAMERA: ignore"      -> blockiert (revoked)       -> "ignore"
     *   "Uid mode: CAMERA: deny"        -> blockiert (hart)          -> "deny"
     *   "READ_CLIPBOARD: deny"          -> reine AppOps OHNE Permission:
     *                                      nur Paket-Ebene, KEINE "Uid mode:"-Zeile
     *   "No operations." / "No mode"    -> kein expliziter Modus     -> "default"
     */
    fun getMode(packageName: String, op: AppOp): String? {
        val result = backend.exec("cmd", "appops", "get", packageName, op.opName)
        if (result.exitCode != 0) return null
        return parseMode(result.stdout, op)
    }

    /** Wie [getMode], aber auf bereits gelesener Ausgabe von `cmd appops get <pkg> <op>`. */
    fun parseMode(output: String, op: AppOp): String {
        val stdout = output.trim()
        if (stdout.contains("No mode", ignoreCase = true)) return "default"
        if (stdout.contains("No operations", ignoreCase = true)) return "default"
        val opPrefix = op.opName.lowercase() + ":"
        for (line in stdout.split("\n")) {
            val t = line.trim()
            if (t.isEmpty()) continue
            if (t.startsWith("Uid mode:", ignoreCase = true)) {
                val after = t.substringAfter(":").trim()          // "CAMERA: foreground" oder "foreground"
                val mode = after.substringAfterLast(":").trim().lowercase()
                return normalizeMode(mode) ?: "default"
            }
            // Paket-Ebene bei reinen AppOps (READ_CLIPBOARD, WRITE_CLIPBOARD):
            // "<OP>: <mode>" — die Zeile NUR parsen, wenn der Praefix zum Op passt
            // (sonst koennte "Default mode: allow" faelschlich als "default" gelesen werden).
            if (t.lowercase().startsWith(opPrefix)) {
                val mode = t.substringAfterLast(":").trim().lowercase()
                return normalizeMode(mode) ?: "default"
            }
            val m = normalizeMode(t)
            if (m != null) return m
        }
        return "default"
    }

    fun restoreAll(packageName: String, ops: List<AppOp>): Map<String, ShellResult> =
        ops.associate { it.opName to setMode(packageName, it, "default") }

    /** Capability-Probe: existiert die Op auf dem Gerät? */
    fun probe(packageName: String, op: AppOp): Boolean = getMode(packageName, op) != null

    /** Grobe Prüfung: kann diese App überhaupt kontrolliert werden? */
    fun canControl(packageName: String): Boolean {
        val getAccountsOp = PrivacyOps.PRIVACY_OPS.find { it.opName == "GET_ACCOUNTS" }
        val cameraOp = PrivacyOps.PRIVACY_OPS.find { it.opName == "CAMERA" }
        if (getAccountsOp != null && probe(packageName, getAccountsOp)) return true
        if (cameraOp != null && probe(packageName, cameraOp)) return true
        return false
    }

    private fun normalizeMode(m: String): String? {
        val low = m.lowercase()
        return when {
            low.startsWith("foreground") -> "allow"   // Android 12+: Permission granted
            low.startsWith("allow") -> "allow"
            low.startsWith("ignore") -> "ignore"
            low.startsWith("deny") -> "deny"
            low.startsWith("default") -> "default"
            else -> null
        }
    }
}
