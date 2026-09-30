package de.minipc.appopsshizuku.shizuku

/**
 * Android-freie Fehlerheuristik fuer Shell-Ausgaben.
 *
 * pm/cmd liefern bei einer SecurityException oft exitCode 0, aber die Meldung
 * steht in stdout/stderr — nur der Exit-Code reicht nicht (Android 15/16: Shell
 * darf Komponenten-Zustand nur mit CHANGE_COMPONENT_ENABLED_STATE aendern).
 *
 * Herausgezogen aus ComponentsActivity, damit die Logik unit-testbar ist.
 */
fun ShellResult.isFailure(): Boolean {
    if (exitCode != 0) return true
    val out = stderr + "\n" + stdout
    return out.contains("Exception", ignoreCase = true) ||
        out.contains("cannot change", ignoreCase = true) ||
        out.contains("Error", ignoreCase = true)
}
