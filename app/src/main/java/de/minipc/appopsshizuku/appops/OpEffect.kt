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
}
