package de.minipc.appopsshizuku.appops

/**
 * Wer haelt das Geraet beschaeftigt? Heuristische Parser fuer `dumpsys alarm`, `dumpsys jobscheduler`
 * und `dumpsys sensorservice`. Die Formate unterscheiden sich je Android-Version/ROM - wie sonst in
 * dieser App gilt: ein leeres Ergebnis heisst "nicht erkannt", nicht "nichts vorhanden".
 *
 * Reine Textauswertung (JVM-testbar); die Shell-Aufrufe macht der Reader.
 */
object ActivityScan {

    private val PKG = """[a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)+"""

    /**
     * `dumpsys alarm` -> Weckvorgaenge je Paket aus dem "Alarm Stats"-Block.
     * Zeilenform (AOSP): `com.example +1m2s743ms running, 3 wakeups:`
     */
    fun parseAlarmWakeups(dump: String): Map<String, Int> {
        // Neuere Android-Versionen setzen "u0a123:" bzw. "1000:" vor den Paketnamen
        val re = Regex("""^\s*(?:\w+:)?($PKG)\s.*?,\s*(\d+)\s+wakeup""", RegexOption.MULTILINE)
        val out = HashMap<String, Int>()
        for (m in re.findAll(dump)) {
            val pkg = m.groupValues[1]
            val n = m.groupValues[2].toIntOrNull() ?: continue
            out[pkg] = maxOf(out[pkg] ?: 0, n)
        }
        return out
    }

    /**
     * `dumpsys jobscheduler` -> Anzahl registrierter Jobs je Paket.
     * Gezaehlt werden `service=ComponentInfo{pkg/cls}`-Vorkommen (ein Eintrag je Job).
     */
    fun parseJobs(dump: String): Map<String, Int> {
        // Variante 1: "JOB #u0a105/12: 7ad2f8 com.pkg/.Service ..." (eine Zeile je Job)
        // Variante 2: "service=ComponentInfo{com.pkg/...}" (aeltere Ausgaben)
        // Es zaehlt die erste Variante mit Treffern - nie beide, sonst doppelt gezaehlt.
        val patterns = listOf(
            Regex("""JOB #\S+/\d+:\s+\S+\s+($PKG)/"""),
            Regex("""service=ComponentInfo\{($PKG)/""")
        )
        for (re in patterns) {
            val out = HashMap<String, Int>()
            for (m in re.findAll(dump)) {
                val pkg = m.groupValues[1]
                out[pkg] = (out[pkg] ?: 0) + 1
            }
            if (out.isNotEmpty()) return out
        }
        return emptyMap()
    }

    /**
     * `dumpsys sensorservice` -> Pakete, die Sensoren abfragen oder zuletzt abgefragt haben.
     * Formate laut AOSP (SensorEventConnection.cpp, SensorRegistrationInfo.h):
     *  - aktive Verbindung:      `\t com.pkg | WakeLockRefCount 0 | uid 10123 | ...`
     *  - fruehere Registrierung: `... result=OK (sensor, package): (Accelerometer   , com.pkg)`
     *  - aeltere Ausgaben:       `package=com.pkg` / `pkg=com.pkg`
     * Bewegungssensoren brauchen keine Berechtigung und werden fuers Fingerprinting genutzt.
     */
    fun parseSensorPackages(dump: String): Set<String> {
        val patterns = listOf(
            Regex("""^\s*($PKG)\S*\s+\|\s+WakeLockRefCount""", RegexOption.MULTILINE),
            Regex("""\(sensor, package\):\s*\(.*?,\s*($PKG)[^)]*\)"""),
            Regex("""(?:package|pkg)=($PKG)""")
        )
        return patterns.flatMap { re -> re.findAll(dump).map { it.groupValues[1] } }.toSet()
    }

    enum class Kind { ALARM, JOB, SENSOR }

    data class Item(val packageName: String, val kind: Kind, val count: Int)

    /** Eintraege einer Kategorie, haeufigste zuerst (ohne die eigene App). */
    fun items(counts: Map<String, Int>, kind: Kind, ownPackage: String): List<Item> =
        counts.filterKeys { it != ownPackage }
            .map { Item(it.key, kind, it.value) }
            .sortedWith(compareByDescending<Item> { it.count }.thenBy { it.packageName })
}
