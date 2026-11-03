package de.minipc.appopsshizuku.appops

/**
 * "Nur bei Bedarf" fuer fremde Starter (z. B. AppDialer NXT): Darf eine andere App eine gesperrte
 * Bedarfs-App ueber unseren exportierten Zugang freigeben und starten?
 *
 * Der Aufrufer muss sich zu erkennen geben (startActivityForResult -> callingPackage, das setzt das
 * System und laesst sich nicht faelschen). Unbekannte Aufrufer werden nie still durchgelassen: Der
 * Nutzer entscheidet einmal im Dialog, die Freigabe ist in der App wieder entziehbar.
 */
object OnDemandCallers {

    /** Aktion des exportierten Zugangs. Extra [EXTRA_PACKAGE] = Paketname der Ziel-App. */
    const val ACTION_OPEN = "de.minipc.appopsshizuku.action.OPEN_ON_DEMAND"
    const val EXTRA_PACKAGE = "pkg"

    enum class Decision { ALLOW, ASK, DENY }

    /**
     * @param caller vom System gemeldeter Aufrufer; null = nicht erkennbar (z. B. ohne ForResult gestartet)
     * @param target Ziel-App
     */
    fun decide(caller: String?, ownPackage: String, target: String, trusted: Set<String>): Decision = when {
        caller.isNullOrBlank() -> Decision.DENY
        // Eine App darf sich nicht selbst freigeben (z. B. gesperrte App oeffnet sich per Link selbst)
        caller == target -> Decision.DENY
        caller == ownPackage -> Decision.ALLOW
        caller in trusted -> Decision.ALLOW
        else -> Decision.ASK
    }
}
