package de.minipc.appopsshizuku.appops

/**
 * Modus fuer "Alle auf Standard" (Reset) — bewusst NICHT fuer alle Ops derselbe.
 *
 * Entscheidung (09/2026, am Testgeraet belegt):
 * - **Permission-Ops:** Reset = `pm revoke` (der "deny"-Weg des Controllers). "Standard" heisst fuer
 *   eine Runtime-Berechtigung "nicht erteilt, die App darf neu fragen". Vorher lief der Reset ueber
 *   "default" und damit auf `pm grant` — das hat der App Berechtigungen ERTEILT, die sie nie hatte,
 *   sobald der Katalog Standort/SMS/Telefonstatus umfasst. Ein Reset darf nie Zugriff ausweiten.
 * - **Reine AppOps:** `cmd appops set <op> default` (kein Zugriff wird dabei erteilt).
 *
 * Rein rechnend, ohne Android-Abhaengigkeit, damit es als Unit-Test abgesichert werden kann.
 */
object OpReset {

    /** Modus fuer Permission-Ops: entziehen (Controller uebersetzt das in `pm revoke`). */
    const val PERMISSION_RESET_MODE = "deny"

    /** Modus fuer reine AppOps: Systemstandard. */
    const val APPOP_RESET_MODE = "default"

    fun modeFor(op: AppOp): String =
        if (op.permissionName != null) PERMISSION_RESET_MODE else APPOP_RESET_MODE

    /**
     * Zusaetzliche Befehle, die ein vollstaendiges "Standard" fuer diese Op braucht (leer = keine).
     *
     * Grund (am Geraet gemessen): der Controller uebersetzt fuer Berechtigungs-Ops nur in
     * `pm revoke`/`pm grant`. Eine frueher gesetzte AppOp-Uebersteuerung derselben Op
     * (z. B. `POST_NOTIFICATION: ignore`) bleibt dabei stehen — "Standard" waere dann nicht Standard,
     * die App bliebe trotz entzogener Berechtigung zusaetzlich auf AppOp-Ebene gesperrt.
     */
    fun extraResetCommands(pkg: String, op: AppOp): List<List<String>> =
        if (op.permissionName == null) emptyList()
        else listOf(listOf("cmd", "appops", "set", pkg, op.opName, APPOP_RESET_MODE))
}
