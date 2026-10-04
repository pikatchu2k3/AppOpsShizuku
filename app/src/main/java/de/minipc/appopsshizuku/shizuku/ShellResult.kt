package de.minipc.appopsshizuku.shizuku

/** Echte Fehlerzeile: beginnt mit "Exception"/"Error" oder mit einer java.*-/android.*-Exception bzw. -Error. */
private val FAILURE_LINE = Regex("""^(Exception\b|Error\b|(java|android)\.[\w.$]*(Exception|Error)\b)""")

/**
 * Android-freie Fehlerheuristik fuer Shell-Ausgaben.
 *
 * pm/cmd liefern bei einer SecurityException oft exitCode 0, aber die Meldung
 * steht in stdout/stderr — nur der Exit-Code reicht nicht (Android 15/16: Shell
 * darf Komponenten-Zustand nur mit CHANGE_COMPONENT_ENABLED_STATE aendern).
 *
 * Geprueft wird nur der ANFANG jeder Zeile: ein Treffer irgendwo im Text wuerde
 * Komponenten wie `...ErrorReportingService` als Fehler melden. Wo es auf den Zustand
 * ankommt (Netz-Schalter), ist Zuruecklesen die Wahrheit, nicht diese Heuristik.
 *
 * Herausgezogen aus ComponentsActivity, damit die Logik unit-testbar ist.
 */
fun ShellResult.isFailure(): Boolean {
    if (exitCode != 0) return true
    return (stderr + "\n" + stdout).lineSequence().map { it.trim() }.any {
        FAILURE_LINE.containsMatchIn(it) || it.contains("cannot change", ignoreCase = true)
    }
}
