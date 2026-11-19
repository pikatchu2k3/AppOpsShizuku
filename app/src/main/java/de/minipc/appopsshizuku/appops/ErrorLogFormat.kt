package de.minipc.appopsshizuku.appops

import de.minipc.appopsshizuku.shizuku.ShellResult
import de.minipc.appopsshizuku.shizuku.isFailure

/**
 * Reine Logik des Fehlerprotokolls (JVM-testbar): Eintraege formatieren, Wiederholungen
 * zusammenfassen und das Protokoll auf eine Hoechstgroesse kuerzen.
 *
 * Die App hat keine Internet-Berechtigung: das Protokoll bleibt auf dem Geraet und wird nur
 * geteilt, wenn der Nutzer es selbst tut (Diagnose -> Bericht teilen / speichern).
 */
object ErrorLogFormat {

    /** Jeder Eintrag beginnt mit dieser Kopfzeile - daran wird beim Kuerzen geschnitten. */
    const val ENTRY_PREFIX = "--- "

    /** Hoechstgroesse der Datei; beim Ueberschreiten bleiben die neuesten [KEEP_CHARS] stehen. */
    const val MAX_CHARS = 200_000
    const val KEEP_CHARS = 150_000

    /** Gleicher Fehler innerhalb dieser Zeit wird nur einmal geschrieben (z. B. "Shizuku fehlt"). */
    const val REPEAT_WINDOW_MS = 10 * 60 * 1000L

    private const val CLIP = 600

    fun entry(timestamp: String, kind: String, text: String): String =
        "$ENTRY_PREFIX$timestamp · $kind\n${text.trimEnd()}\n"

    /** Text fuer einen fehlgeschlagenen Shell-Befehl; null, wenn der Befehl geklappt hat. */
    fun shell(args: Array<out String>, r: ShellResult): String? {
        if (!r.isFailure()) return null
        val sb = StringBuilder()
        sb.append("$ ").append(args.joinToString(" ")).append('\n')
        sb.append("exit=").append(r.exitCode)
        if (r.stderr.isNotBlank()) sb.append("\nstderr: ").append(clip(r.stderr))
        if (r.stdout.isNotBlank()) sb.append("\nstdout: ").append(clip(r.stdout))
        return sb.toString()
    }

    /** Schluessel fuer "derselbe Fehler": Art + erste Zeile, ohne Zahlen (Zeiten, PIDs). */
    fun key(kind: String, text: String): String =
        kind + "|" + text.lineSequence().firstOrNull().orEmpty().replace(Regex("""\d+"""), "#")

    /** Schreiben oder als Wiederholung unterdruecken? [last] = Zeitpunkt des letzten gleichen Eintrags. */
    fun shouldWrite(now: Long, last: Long?): Boolean = last == null || now - last >= REPEAT_WINDOW_MS

    /** Auf die neuesten [keep] Zeichen kuerzen, an einer Eintragsgrenze. */
    fun trim(text: String, max: Int = MAX_CHARS, keep: Int = KEEP_CHARS): String {
        if (text.length <= max) return text
        val tail = text.substring(text.length - keep)
        val cut = tail.indexOf("\n$ENTRY_PREFIX")
        return if (cut >= 0) tail.substring(cut + 1) else tail
    }

    /** Anzahl der Eintraege. */
    fun count(text: String): Int =
        text.lineSequence().count { it.startsWith(ENTRY_PREFIX) }

    /** Zeitstempel des letzten Eintrags (Kopfzeile bis zum " · "), null wenn leer. */
    fun lastTimestamp(text: String): String? =
        text.lineSequence().lastOrNull { it.startsWith(ENTRY_PREFIX) }
            ?.removePrefix(ENTRY_PREFIX)?.substringBefore(" · ")

    /** Fuer das Teilen: hoechstens [max] Zeichen, die neuesten Eintraege zuerst behalten. */
    fun forShare(text: String, max: Int = 90_000): String = trim(text, max, max)

    fun clip(s: String, n: Int = CLIP): String {
        val t = s.trim()
        return if (t.length <= n) t else t.take(n) + " … (+${t.length - n} Zeichen)"
    }
}
