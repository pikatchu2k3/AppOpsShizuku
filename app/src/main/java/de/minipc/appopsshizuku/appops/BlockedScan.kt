package de.minipc.appopsshizuku.appops

/**
 * Filter "Blockiert" der App-Liste: hat eine App mindestens eine gesperrte Privacy-Op?
 *
 * Ausgabe von `cmd appops get <pkg>` (alle Ops) kennt zwei Ebenen:
 *  - `Uid mode: CAMERA: ignore`          -> Berechtigungs-Ops (UID-Ebene)
 *  - `READ_CLIPBOARD: deny; time=+1h ago` -> reine AppOps, NUR Paket-Ebene, ohne `Uid mode:`
 * Frueher zaehlte nur die erste Form - eine gesperrte Zwischenablage blieb im Filter unsichtbar.
 * Bei der Paket-Ebene zaehlen nur reine Ops aus dem eigenen Katalog: sonst wuerden System-Standards
 * fremder Ops (z. B. MOCK_LOCATION) jede App als "blockiert" markieren.
 */
object BlockedScan {

    private val UID_BLOCKED = Regex("""^\s*Uid mode:\s*[A-Z0-9_]+:\s*(ignore|deny)\b""", RegexOption.MULTILINE)
    private val PACKAGE_LINE = Regex("""^\s*([A-Z0-9_]+):\s*(ignore|deny)\b""", RegexOption.MULTILINE)

    /** Reine AppOps (ohne Runtime-Permission) aus dem Katalog. */
    val PURE_OPS: Set<String> =
        PrivacyOps.PRIVACY_OPS.filter { it.permissionName == null }.map { it.opName }.toSet()

    fun hasBlockedOp(appopsGetOutput: String, pureOps: Set<String> = PURE_OPS): Boolean {
        if (UID_BLOCKED.containsMatchIn(appopsGetOutput)) return true
        return PACKAGE_LINE.findAll(appopsGetOutput).any { it.groupValues[1] in pureOps }
    }
}
