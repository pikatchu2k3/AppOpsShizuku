package de.minipc.appopsshizuku.appops

/**
 * Diagnose "Was kann mein Geraet?": bewertet die Ausgaben harmloser Probe-Befehle. Keine Probe
 * aendert etwas - Befehle werden entweder nur lesend oder absichtlich OHNE Ziel aufgerufen (dann
 * antwortet die Shell mit "kein Paket angegeben" statt etwas zu tun).
 * Reine Auswertung (JVM-testbar); die Aufrufe macht die Activity.
 */
object Diagnostics {

    enum class Status { OK, FAIL, UNKNOWN }

    data class Result(val id: String, val status: Status, val detail: String = "")

    /**
     * Kennt die Shell einen (ohne Ziel aufgerufenen) Befehl bzw. eine Option?
     * "Unknown command/option" = nein. Jede andere Antwort (auch "no package specified") = ja.
     */
    fun commandKnown(output: String): Status {
        val t = output.trim()
        if (t.isEmpty()) return Status.UNKNOWN
        val unknown = Regex("unknown (command|option)|No shell command implementation", RegexOption.IGNORE_CASE)
        return if (unknown.containsMatchIn(t)) Status.FAIL else Status.OK
    }

    /** Lesender Befehl: Exit 0 und eine verwertbare (nicht leere, nicht "Exception") Ausgabe. */
    fun readable(exitCode: Int, output: String): Status = when {
        exitCode != 0 -> Status.FAIL
        output.isBlank() -> Status.UNKNOWN
        Regex("""^\s*(java\.|android\.)?[\w.]*(Exception|Error)\b""", RegexOption.MULTILINE).containsMatchIn(output) -> Status.FAIL
        else -> Status.OK
    }


    /** Parser-Probe: Ausgabe da, aber nichts erkannt = Format unbekannt (nicht "kaputt"). */
    fun parserStatus(exitCode: Int, output: String, recognized: Int): Status = when {
        exitCode != 0 || output.isBlank() -> Status.FAIL
        recognized > 0 -> Status.OK
        else -> Status.UNKNOWN
    }

    /** Textbericht zum Teilen. */
    fun report(header: String, lines: List<Pair<String, Result>>): String = buildString {
        appendLine(header)
        for ((label, r) in lines) {
            val mark = when (r.status) { Status.OK -> "[OK]  "; Status.FAIL -> "[--]  "; Status.UNKNOWN -> "[??]  " }
            append(mark).append(label)
            if (r.detail.isNotBlank()) append(" — ").append(r.detail)
            appendLine()
        }
    }
}
