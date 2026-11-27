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
     * Vertrauenslisten-Eintrag: `paket` (Altbestand, ohne Signatur) oder `paket@fingerprint`.
     * Mit Fingerabdruck gilt das Vertrauen nur fuer genau diesen Signierer - eine andere App, die spaeter
     * denselben Paketnamen belegt, wird neu gefragt.
     */
    fun entryFor(pkg: String, fingerprint: String?): String =
        if (fingerprint.isNullOrBlank()) pkg else "$pkg@$fingerprint"

    private fun pkgOf(entry: String) = entry.substringBefore('@')
    private fun fpOf(entry: String): String? = entry.substringAfter('@', "").ifEmpty { null }

    /** Eintrag fuer [caller] in [trusted]; Altbestand ohne Fingerabdruck wird beim naechsten Treffer ersetzt. */
    fun pin(trusted: Set<String>, caller: String, fingerprint: String?): Set<String> =
        trusted.filterNot { pkgOf(it) == caller }.toSet() + entryFor(caller, fingerprint)

    /** Anzahl vertrauter Starter-Apps (fuer die Anzeige). */
    fun count(trusted: Set<String>): Int = trusted.map(::pkgOf).toSet().size

    /**
     * @param caller vom System gemeldeter Aufrufer; null = nicht erkennbar (z. B. ohne ForResult gestartet)
     * @param target Ziel-App
     * @param callerFingerprint Signierer des Aufrufers ([PackageSigner.fingerprint]); null = nicht lesbar
     */
    fun decide(
        caller: String?,
        ownPackage: String,
        target: String,
        trusted: Set<String>,
        callerFingerprint: String? = null
    ): Decision {
        if (caller.isNullOrBlank()) return Decision.DENY
        // Eine App darf sich nicht selbst freigeben (z. B. gesperrte App oeffnet sich per Link selbst)
        if (caller == target) return Decision.DENY
        if (caller == ownPackage) return Decision.ALLOW
        val entry = trusted.firstOrNull { pkgOf(it) == caller } ?: return Decision.ASK
        val pinned = fpOf(entry) ?: return Decision.ALLOW          // Altbestand: erlauben, dann festpinnen
        return if (callerFingerprint != null && callerFingerprint == pinned) Decision.ALLOW else Decision.ASK
    }
}
