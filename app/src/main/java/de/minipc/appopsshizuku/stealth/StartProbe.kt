package de.minipc.appopsshizuku.stealth

/**
 * Auswertung der Startprobe: Logcat (`-v threadtime`, Puffer main+system+crash) nach dem Start der
 * gepatchten App. Nur Zeilen der App-Prozesse zaehlen (PIDs aus ActivityManager "Start proc" und
 * "Process: <pkg>, PID: n"), sonst saehe jeder fremde Absturz im Log wie ein Patch-Fehler aus.
 */
object StartProbe {

    /** Absturzmerkmale; `FATAL EXCEPTION` = bestaetigter Absturz, der Rest nur ein Fehlermerkmal im Log. */
    val MARKERS = listOf(
        "FATAL EXCEPTION", "ClassNotFoundException", "NoSuchMethodError", "VerifyError", "SecurityException"
    )

    class Result(
        /** false = im Log kein Prozess der App gesehen (App nicht gestartet / Log leer). */
        val started: Boolean,
        val crashed: Boolean,
        /** Marker -> erste Logzeile (gekuerzt), nur Marker mit Treffer. */
        val hits: Map<String, String>
    )

    private val LINE_PID = Regex("""^\S+\s+\S+\s+(\d+)\s+\d+\s+[VDIWEF]\s""")

    fun analyze(log: String, pkg: String): Result {
        val q = Regex.escape(pkg)
        val startProc = Regex("""Start proc (\d+):$q/""")
        val crashProc = Regex("""Process: $q, PID: (\d+)""")
        val lines = log.lines()
        val pids = HashSet<String>()
        for (l in lines) {
            startProc.find(l)?.let { pids += it.groupValues[1] }
            crashProc.find(l)?.let { pids += it.groupValues[1] }
        }
        val hits = LinkedHashMap<String, String>()
        for (l in lines) {
            val pid = LINE_PID.find(l)?.groupValues?.get(1) ?: continue
            if (pid !in pids) continue
            for (m in MARKERS) if (m in l && m !in hits) hits[m] = l.trim().take(200)
        }
        return Result(pids.isNotEmpty(), "FATAL EXCEPTION" in hits, hits)
    }
}
