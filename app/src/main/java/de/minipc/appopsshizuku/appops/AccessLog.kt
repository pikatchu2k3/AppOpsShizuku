package de.minipc.appopsshizuku.appops

/**
 * Zugriffsprotokoll aus `cmd appops get <pkg>` (eine Shell-Abfrage fuer alle Ops einer App).
 *
 * Format der Paket-Zeilen (AppOpsService-Shell, Android 10+):
 *   `CAMERA: allow; time=+2d3h4m5s123ms ago; duration=+1s23ms`
 *   `READ_CLIPBOARD: ignore; rejectTime=+5m ago`
 *   `RECORD_AUDIO: allow; time=+1s ago (running)`
 * Zeiten sind Abstaende zu "jetzt" (android.util.TimeUtils.formatDuration). `Uid mode:`-Zeilen
 * tragen keine Zeiten und werden uebersprungen. Alles bleibt auf dem Geraet: nur gelesen, nie gesendet.
 */
data class OpAccess(
    val opName: String,
    /** Wie lange der letzte erlaubte Zugriff her ist (ms); null = keiner aufgezeichnet. */
    val lastAccessAgoMs: Long? = null,
    /** Wie lange der letzte ABGELEHNTE Zugriff her ist (ms); null = keiner aufgezeichnet. */
    val lastRejectAgoMs: Long? = null,
    /** Dauer des letzten Zugriffs (ms), falls das System sie fuehrt. */
    val lastDurationMs: Long? = null,
    /** Zugriff laeuft gerade (z. B. Mikrofon offen). */
    val running: Boolean = false
)

object AccessLog {

    private val LINE = Regex("""^\s*([A-Z][A-Z0-9_]*):\s*[a-z]+\b(.*)$""")
    private val TIME = Regex("""(?<![a-zA-Z])time=([+-]?[0-9dhms]+)\s+ago""")
    private val REJECT = Regex("""rejectTime=([+-]?[0-9dhms]+)\s+ago""")
    private val DURATION = Regex("""duration=([+-]?[0-9dhms]+)""")
    private val PART = Regex("""(\d+)(ms|d|h|m|s)""")

    /** `+2d3h4m5s123ms` -> Millisekunden; `0` -> 0; nicht lesbar -> null. Vorzeichen wird ignoriert. */
    fun parseDuration(raw: String): Long? {
        val s = raw.trim().trimStart('+', '-')
        if (s == "0") return 0
        if (s.isEmpty()) return null
        var total = 0L
        var consumed = 0
        for (m in PART.findAll(s)) {
            val n = m.groupValues[1].toLong()
            total += when (m.groupValues[2]) {
                "d" -> n * 86_400_000L
                "h" -> n * 3_600_000L
                "m" -> n * 60_000L
                "s" -> n * 1_000L
                else -> n
            }
            consumed += m.value.length
        }
        return if (consumed == s.length) total else null
    }

    fun parse(appopsGetOutput: String): Map<String, OpAccess> {
        val out = mutableMapOf<String, OpAccess>()
        for (line in appopsGetOutput.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("Uid mode:", ignoreCase = true)) continue
            val m = LINE.matchEntire(t) ?: continue
            val op = m.groupValues[1]
            val rest = m.groupValues[2]
            val entry = OpAccess(
                opName = op,
                lastAccessAgoMs = TIME.find(rest)?.groupValues?.get(1)?.let(::parseDuration),
                lastRejectAgoMs = REJECT.find(rest)?.groupValues?.get(1)?.let(::parseDuration),
                lastDurationMs = DURATION.find(rest)?.groupValues?.get(1)?.let(::parseDuration),
                running = rest.contains("(running)")
            )
            // Mehrfache Zeilen derselben Op (z. B. pro Attribution) -> juengsten Zugriff behalten
            out[op] = out[op]?.let { merge(it, entry) } ?: entry
        }
        return out
    }

    private fun merge(a: OpAccess, b: OpAccess) = OpAccess(
        a.opName,
        minOfNullable(a.lastAccessAgoMs, b.lastAccessAgoMs),
        minOfNullable(a.lastRejectAgoMs, b.lastRejectAgoMs),
        a.lastDurationMs ?: b.lastDurationMs,
        a.running || b.running
    )

    private fun minOfNullable(x: Long?, y: Long?): Long? = when {
        x == null -> y
        y == null -> x
        else -> minOf(x, y)
    }
}

/**
 * Empfehlungen "diese Berechtigung braucht die App offenbar nicht" - rein lokal, aus zwei Quellen:
 *  1. Nutzung: erteilt, aber seit [UNUSED_DAYS] Tagen nicht benutzt (bzw. nie, und die App ist
 *     mindestens so lange installiert - eine frisch installierte App hatte noch keine Gelegenheit).
 *  2. App-Kategorie (ApplicationInfo.category): Spiele/Medien/Nachrichten brauchen typischerweise
 *     weder Kontakte noch Anrufliste, SMS oder genauen Standort.
 * Es wird nur VORGESCHLAGEN; entzogen wird erst nach Bestaetigung.
 */
object Recommendations {
    const val UNUSED_DAYS = 30
    private const val DAY_MS = 86_400_000L

    /** Werte von android.content.pm.ApplicationInfo.CATEGORY_* (hier als Zahl fuer JVM-Tests). */
    const val CATEGORY_GAME = 0
    const val CATEGORY_AUDIO = 1
    const val CATEGORY_VIDEO = 2
    const val CATEGORY_IMAGE = 3
    const val CATEGORY_NEWS = 5

    private val CATEGORY_SUSPICIOUS: Map<Int, Set<String>> = run {
        val personal = setOf(
            "READ_CONTACTS", "WRITE_CONTACTS", "READ_CALL_LOG", "WRITE_CALL_LOG",
            "READ_SMS", "RECEIVE_SMS", "SEND_SMS", "CALL_PHONE", "READ_PHONE_NUMBERS",
            "GET_ACCOUNTS", "READ_PHONE_STATE", "FINE_LOCATION",
            "READ_CALENDAR", "WRITE_CALENDAR"
        )
        mapOf(
            CATEGORY_GAME to personal + setOf("RECORD_AUDIO", "CAMERA"),
            CATEGORY_AUDIO to personal + "CAMERA",
            CATEGORY_VIDEO to personal,
            CATEGORY_IMAGE to personal - setOf("FINE_LOCATION"),
            CATEGORY_NEWS to personal + setOf("RECORD_AUDIO", "CAMERA")
        )
    }

    enum class Reason { UNUSED, CATEGORY }

    data class Suggestion(val opName: String, val reason: Reason)

    /** Erteilte Permission-Op, die seit [UNUSED_DAYS] Tagen nicht benutzt wurde? */
    fun isUnused(access: OpAccess?, installedAgoMs: Long, days: Int = UNUSED_DAYS): Boolean {
        val threshold = days * DAY_MS
        val last = access?.lastAccessAgoMs
        if (access?.running == true) return false
        return if (last == null) installedAgoMs >= threshold else last >= threshold
    }

    /**
     * @param states opName -> aktueller Modus (nur "allow" wird betrachtet: erteilt)
     * @param permissionOps Ops mit Runtime-Permission (nur diese lassen sich sinnvoll entziehen)
     */
    fun suggest(
        states: Map<String, String?>,
        access: Map<String, OpAccess>,
        permissionOps: Set<String>,
        category: Int,
        installedAgoMs: Long
    ): List<Suggestion> {
        val suspicious = CATEGORY_SUSPICIOUS[category].orEmpty()
        val out = mutableListOf<Suggestion>()
        for ((op, mode) in states) {
            if (mode != "allow" || op !in permissionOps) continue
            when {
                isUnused(access[op], installedAgoMs) -> out += Suggestion(op, Reason.UNUSED)
                op in suspicious -> out += Suggestion(op, Reason.CATEGORY)
            }
        }
        return out.sortedBy { it.opName }
    }
}
