package de.minipc.appopsshizuku.appops

/**
 * Ansicht "Nach Berechtigung": welche Apps duerfen auf eine bestimmte Berechtigung zugreifen?
 * Reine Entscheidungslogik (JVM-testbar); Lesen/Schreiben macht die Activity.
 */
object PermissionOverview {

    /** Zeile der Liste. [mode] = gelesener Modus, null = nicht lesbar. */
    data class Row(
        val packageName: String,
        val label: String,
        val isSystem: Boolean,
        val mode: String?,
        val access: OpAccess? = null
    ) {
        val allowed: Boolean get() = isAllowed(mode)
    }

    fun isAllowed(mode: String?): Boolean = mode == "allow" || mode == "default"

    /**
     * Ziel-Modus fuer den Schalter.
     * - Berechtigungs-Ops: erteilen / entziehen (pm grant / pm revoke + user-fixed).
     * - Reine AppOps: zurueck auf Systemstandard / still blockieren. `ignore` statt `deny`, weil `deny`
     *   bei der Zwischenablage nachweislich Apps beim Start abstuerzen laesst (siehe PROJEKTSTAND).
     */
    fun toggleMode(op: AppOp, allow: Boolean): String = when {
        op.permissionName != null -> if (allow) "allow" else "deny"
        else -> if (allow) "default" else "ignore"
    }

    /** Erlaubte zuerst, darin zuletzt benutzte zuerst, dann alphabetisch. */
    fun sort(rows: List<Row>): List<Row> = rows.sortedWith(
        compareByDescending<Row> { it.allowed }
            .thenBy { it.access?.lastAccessAgoMs ?: Long.MAX_VALUE }
            .thenBy { it.label.lowercase() }
    )

    /** Ziele der Sammelaktion "allen entziehen": erlaubte Nutzer-Apps (System-Apps nie). */
    fun bulkTargets(rows: List<Row>): List<Row> = rows.filter { it.allowed && !it.isSystem }
}
