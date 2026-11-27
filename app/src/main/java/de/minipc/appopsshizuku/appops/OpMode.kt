package de.minipc.appopsshizuku.appops

/**
 * Ein gewuenschter Op-Modus, eindeutig statt als freier String. Frueher fiel in
 * [AppOpsController.setMode] jeder unbekannte String bei Berechtigungen in den `pm grant`-Zweig - ein Tippfehler
 * oder ein Eintrag aus einer fremden Sicherungsdatei haette also ERTEILT.
 *
 * Tokens (Profile, UI, Aenderungsprotokoll):
 * - `allow`    erlauben (Berechtigung erteilen / AppOp allow)
 * - `ignore`   blockieren (Berechtigung entziehen / AppOp ignore)
 * - `deny`     blockieren, hart (Berechtigung entziehen / AppOp deny)
 * - `default`  "nicht blockiert": bei Berechtigungen wie `allow` (Altbestand und gelesener Ist-Zustand ohne
 *              eigenen Modus), bei reinen AppOps der Systemstandard
 * - `reset`    Zuruecksetzen: Berechtigung entziehen UND "nicht mehr fragen" loesen, die App darf neu fragen
 *              (wie "Alle auf Standard", siehe [OpReset]); bei reinen AppOps der Systemstandard
 */
enum class OpMode(val token: String) {
    ALLOW("allow"),
    IGNORE("ignore"),
    DENY("deny"),
    DEFAULT("default"),
    RESET("reset");

    /** Wert fuer `cmd appops set` bei reinen AppOps. */
    val appOpToken: String
        get() = when (this) {
            RESET -> "default"
            else -> token
        }

    companion object {
        /** Unbekannte Tokens liefern null - der Aufrufer fuehrt dann NICHTS aus. */
        fun parse(token: String?): OpMode? = entries.firstOrNull { it.token == token }
    }
}
