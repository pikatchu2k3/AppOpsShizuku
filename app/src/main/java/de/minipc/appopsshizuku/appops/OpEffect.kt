package de.minipc.appopsshizuku.appops

/**
 * Android-freie Ehrlichkeitspruefung fuer Op-Modi: hatte das Setzen den
 * gewuenschten Effekt? Herausgezogen aus AppOpsActivity, damit die reine
 * Entscheidungslogik unit-testbar bleibt (Verhalten identisch).
 */
object OpEffect {

    /**
     * @param permissionName Runtime-Permission der Op (pm grant/revoke-Hebel) oder null (reine AppOps).
     * @param requested gewuenschter Modus (allow/default/ignore/deny).
     * @param actual nach dem Setzen gelesener Systemzustand (null = nicht lesbar/gesetzt).
     *
     * - Ops MIT Permission: deny/ignore (revoke) und allow/default (grant) sind
     *   semantisch gleich (System kennt nur blockiert/nicht-blockiert). "foreground"
     *   wird bereits vom Controller zu "allow" normalisiert.
     * - Reine AppOps: der Modus muss exakt stimmen (still ignorierte Ops wie
     *   TURN_SCREEN_ON gelten damit als NICHT effektiv gesetzt).
     */
    fun isSetEffective(permissionName: String?, requested: String, actual: String?): Boolean {
        if (actual == null) return false
        if (permissionName != null) {
            val requestedBlocked = requested == "deny" || requested == "ignore"
            val actualBlocked = actual == "deny" || actual == "ignore"
            val requestedAllowed = requested == "allow" || requested == "default"
            val actualAllowed = actual == "allow" || actual == "default"
            return (requestedBlocked && actualBlocked) || (requestedAllowed && actualAllowed)
        }
        return requested == actual
    }

    /**
     * Grant-Zustand einer Permission aus der Ausgabe von `dumpsys package <pkg>`:
     * "allow" (granted=true), "deny" (granted=false) oder null (nicht aufgefuehrt,
     * z. B. Permission auf dem Geraet unbekannt oder von der App nicht angefordert).
     */
    fun permissionMode(dumpsysPackage: String, permission: String): String? {
        val prefix = "$permission: granted="
        for (line in dumpsysPackage.lineSequence()) {
            val t = line.trim()
            if (!t.startsWith(prefix)) continue
            return when (t.removePrefix(prefix).substringBefore(',').trim()) {
                "true" -> "allow"
                "false" -> "deny"
                else -> null
            }
        }
        return null
    }

    /**
     * Kennung fuer "die App fordert diese Berechtigung gar nicht an".
     * Wichtig: das ist NICHT dasselbe wie "nicht setzbar" (Geraet kennt die Op nicht) —
     * die Op ist setzbar, sie ist nur gegenstandslos. Zwei getrennte Kennzeichnungen,
     * sonst behauptet die UI etwas Falsches.
     */
    const val NOT_REQUESTED = "not-requested"

    /**
     * Zustand einer Runtime-Permission aus `dumpsys package <pkg>`:
     * - "allow" / "deny"     = von der App deklariert, erteilt bzw. entzogen
     * - [NOT_REQUESTED]      = von der App nicht deklariert (Zeile fehlt in der Ausgabe)
     * - null                 = Ausgabe nicht verwertbar (kein Dump / Permission unbekannt)
     */
    fun permissionState(dumpsysPackage: String, permission: String): String? {
        if (dumpsysPackage.isBlank()) return null
        return permissionMode(dumpsysPackage, permission) ?: NOT_REQUESTED
    }

    /**
     * Entscheidet bei fehlender Permission-Zeile, WELCHE der beiden Ursachen vorliegt:
     * - Geraet kennt die Berechtigung -> [NOT_REQUESTED] ("App fordert sie nicht an")
     * - Geraet kennt sie gar nicht (z. B. Android-17-Recht auf Android 14) -> null ("nicht setzbar")
     *
     * `permissionRegistry` ist die Ausgabe von `pm list permissions`; nicht lesbar (null/leer) heisst
     * "nicht entscheidbar" -> dann die haeufigere, harmlosere Aussage [NOT_REQUESTED].
     */
    fun notRequestedOrUnknown(permissionRegistry: String?, permission: String): String? = when {
        permissionRegistry.isNullOrBlank() -> NOT_REQUESTED
        permissionRegistry.contains(permission) -> NOT_REQUESTED
        else -> null
    }
}
