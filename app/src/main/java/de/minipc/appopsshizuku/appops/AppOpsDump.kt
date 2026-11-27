package de.minipc.appopsshizuku.appops

import de.minipc.appopsshizuku.shizuku.ShellResult
import java.util.concurrent.ConcurrentHashMap

/**
 * Liest die Modi ALLER Ops einer App aus EINER Ausgabe von `cmd appops get <pkg>`.
 *
 * Frueher lief je Op ein eigener Aufruf (`cmd appops get <pkg> <OP>`, rund 30 Prozesse beim Oeffnen einer App),
 * obwohl dieselbe Gesamtausgabe fuer das Zugriffsprotokoll ohnehin gelesen wurde. Die Ausgabe enthaelt je Op
 * dieselben Zeilen wie die Einzelabfrage (`Uid mode: <OP>: <modus>` und `<OP>: <modus>; time=...`), nur gemischt.
 */
object AppOpsDump {

    /** Nur die Zeilen, die zu [opName] gehoeren - gleiche Form wie die Ausgabe der Einzelabfrage. */
    fun slice(dump: String, opName: String): String {
        val uid = "Uid mode: $opName:"
        val pkg = "$opName:"
        return dump.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith(uid) || it.startsWith(pkg) }
            .joinToString("\n")
    }

    /** Modus von [op] aus der Gesamtausgabe; keine Zeile heisst wie bei "No operations." -> "default". */
    fun mode(controller: AppOpsController, dump: String, op: AppOp): String =
        controller.parseMode(slice(dump, op.opName), op)

    /**
     * Welche Ops kennt DIESES Geraet? Ein Op-Name, den es nicht kennt, scheitert bei `cmd appops get <pkg> <OP>`
     * mit "Unknown operation string". In der Gesamtausgabe faellt das nicht auf (eine Op ohne Eintrag ist dort
     * einfach abwesend) - deshalb wird je Op EINMAL geprueft und das Ergebnis fuer den Prozess gemerkt.
     * Gemerkt wird nur Eindeutiges: ein anderer Fehler (z. B. Shizuku weg) ist weder "bekannt" noch "unbekannt".
     */
    class Support {
        private val known = ConcurrentHashMap<String, Boolean>()

        /** true = Op bekannt, false = Geraet kennt sie nicht, null = nicht entscheidbar (nicht gemerkt). */
        fun isKnown(opName: String, probe: () -> ShellResult): Boolean? {
            known[opName]?.let { return it }
            val r = probe()
            val verdict = classify(r) ?: return null
            known[opName] = verdict
            return verdict
        }

        fun clear() = known.clear()

        companion object {
            fun classify(r: ShellResult): Boolean? = when {
                r.exitCode == 0 -> true
                (r.stderr + "\n" + r.stdout).contains("Unknown operation", ignoreCase = true) -> false
                else -> null
            }
        }
    }
}
