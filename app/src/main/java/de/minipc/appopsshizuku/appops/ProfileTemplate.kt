package de.minipc.appopsshizuku.appops

/**
 * Fertige Profil-Vorlagen fuer die Detailseite: ein Tipp setzt mehrere Ops auf einmal. Eine Vorlage
 * ist eine Zuordnung opName -> Modus; Ops, die nicht vorkommen, bleiben unangetastet. Die Auswahl der
 * Ops folgt der App-Art, deckt sich mit den Kategorie-Regeln aus [Recommendations] und bleibt bewusst
 * konservativ (nur klar entbehrliche Rechte werden blockiert).
 *
 * Reine Zuordnung (JVM-testbar); angewendet wird ueber den AppOpsController wie ein Handschalter.
 * Clipboard und Hintergrund-Ausfuehrung werden still blockiert (ignore), nie hart (deny), weil deny
 * dort Apps abstuerzen laesst.
 */
object ProfileTemplate {

    enum class Id { GAME, MESSENGER, BANKING }

    val TEMPLATES: Map<Id, Map<String, String>> = mapOf(
        Id.GAME to mapOf(
            "READ_CONTACTS" to "deny", "WRITE_CONTACTS" to "deny",
            "READ_CALL_LOG" to "deny", "READ_SMS" to "deny", "RECEIVE_SMS" to "deny",
            "GET_ACCOUNTS" to "deny", "READ_PHONE_STATE" to "deny",
            "FINE_LOCATION" to "deny", "COARSE_LOCATION" to "deny", "ACCESS_BACKGROUND_LOCATION" to "deny",
            "READ_CLIPBOARD" to "ignore", "WRITE_CLIPBOARD" to "ignore", "RUN_ANY_IN_BACKGROUND" to "ignore"
        ),
        Id.MESSENGER to mapOf(
            "FINE_LOCATION" to "deny", "ACCESS_BACKGROUND_LOCATION" to "deny",
            "READ_CALL_LOG" to "deny", "READ_PHONE_STATE" to "deny",
            "READ_CLIPBOARD" to "ignore"
        ),
        Id.BANKING to mapOf(
            "READ_CLIPBOARD" to "ignore", "WRITE_CLIPBOARD" to "ignore",
            "SYSTEM_ALERT_WINDOW" to "deny",
            "FINE_LOCATION" to "deny", "ACCESS_BACKGROUND_LOCATION" to "deny",
            "READ_PHONE_STATE" to "deny", "RUN_ANY_IN_BACKGROUND" to "ignore"
        )
    )

    /** Nur Vorlagen-Ops, die der Katalog kennt (Fremd-Ops stillschweigend weglassen). */
    fun opsFor(id: Id): Map<String, String> {
        val known = PrivacyOps.PRIVACY_OPS.map { it.opName }.toSet()
        return TEMPLATES.getValue(id).filterKeys { it in known }
    }

    /** Vorlage auf ein vorhandenes Profil legen (Vorlagenwerte gewinnen) - fuer Export/Vorschau. */
    fun merge(current: Map<String, String>, id: Id): Map<String, String> = current + opsFor(id)
}
