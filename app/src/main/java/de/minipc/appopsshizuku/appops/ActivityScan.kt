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
        val re = Regex("""^\s*($PKG)\s.*?,\s*(\d+)\s+wakeup""", RegexOption.MULTILINE)
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
        val re = Regex("""service=ComponentInfo\{($PKG)/""")
        val out = HashMap<String, Int>()
        for (m in re.findAll(dump)) {
            val pkg = m.groupValues[1]
            out[pkg] = (out[pkg] ?: 0) + 1
        }
        return out
    }

    /**
     * `dumpsys sensorservice` -> Pakete, die (zuletzt) Sensoren abgefragt haben. Best-Effort ueber
     * `package=...`/`pkg=...`-Marker; Bewegungssensoren brauchen keine Berechtigung und werden fuers
     * Fingerprinting genutzt.
     */
    fun parseSensorPackages(dump: String): Set<String> {
        val re = Regex("""(?:package|pkg)=($PKG)""")
        return re.findAll(dump).map { it.groupValues[1] }.toSet()
    }

    enum class Kind { ALARM, JOB, SENSOR }

    data class Item(val packageName: String, val kind: Kind, val count: Int)

    /** Eintraege einer Kategorie, haeufigste zuerst (ohne die eigene App). */
    fun items(counts: Map<String, Int>, kind: Kind, ownPackage: String): List<Item> =
        counts.filterKeys { it != ownPackage }
            .map { Item(it.key, kind, it.value) }
            .sortedWith(compareByDescending<Item> { it.count }.thenBy { it.packageName })
}
