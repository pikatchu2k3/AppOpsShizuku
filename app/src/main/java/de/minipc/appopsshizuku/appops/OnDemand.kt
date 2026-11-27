package de.minipc.appopsshizuku.appops

import de.minipc.appopsshizuku.shizuku.ShellBackend
import de.minipc.appopsshizuku.shizuku.ShellResult

/**
 * "Nur bei Bedarf": Die App ist dauerhaft gesperrt und laeuft nur, nachdem sie ueber unsere
 * Launcher-Verknuepfung geoeffnet wurde. Verlaesst man sie, wird sie nach einer Frist wieder
 * gesperrt, beim Bildschirm-Aus sofort. Beim Bildschirm-An wird sie NIE freigegeben - genau
 * das unterscheidet den Modus von "Beim Bildschirm-Aus anhalten".
 *
 * Gesperrt heisst immer zuerst `am force-stop` (Prozess weg, Wecker/Jobs geloescht, keine
 * Broadcasts bis zum naechsten Start), danach je nach [OnDemandLock] einfrieren oder deaktivieren.
 * Einfrieren allein haelt die App NICHT im Hintergrund an (siehe [LockState.wokeUp]); der Dienst
 * prueft deshalb regelmaessig nach und beendet eine geweckte App erneut.
 * Reine Logik (JVM-testbar); Erkennen von Bildschirm und Verlassen macht der Dienst.
 */
enum class OnDemandLock(val json: String) {
    /** `pm suspend`: Icon bleibt (grau), Start blockiert. Ohne Shizuku nicht aufhebbar. */
    SUSPEND("suspend"),

    /** `pm disable-user`: Icon verschwindet; in den System-Einstellungen wieder aktivierbar. */
    DISABLE("disable"),

    /**
     * Nur `am force-stop`: die App bleibt startbar - aus JEDEM Launcher, nicht nur ueber unsere
     * Verknuepfung. "Beendet" heisst wie bei den anderen Sperrarten: keine Wecker, Jobs oder
     * Push-Broadcasts, bis die App geoeffnet wird. Schwaecher: andere Apps koennen sie gezielt starten.
     */
    STOP("stop");

    companion object {
        fun fromJson(s: String?): OnDemandLock? = entries.firstOrNull { it.json == s }
    }
}

/** Zustand aus `dumpsys package`; null = nicht lesbar. */
data class LockState(val suspended: Boolean?, val disabled: Boolean?, val stopped: Boolean?) {
    fun isLocked(lock: OnDemandLock): Boolean = when (lock) {
        OnDemandLock.SUSPEND -> suspended == true
        OnDemandLock.DISABLE -> disabled == true
        OnDemandLock.STOP -> stopped == true
    }

    /**
     * Gesperrt, laeuft aber wieder: `pm suspend` blockiert nur das OEFFNEN (Activities). Jobs,
     * Dienste und Broadcasts laufen bei einer eingefrorenen App weiter, sobald das System oder eine
     * andere App sie startet (z. B. Bluetooth-Geraet verbunden) - dann ist "stopped" wieder false.
     * Davor schuetzt nur der Beendet-Zustand, also muss erneut beendet werden.
     * Deaktivierte Apps kann niemand starten; "Nur beenden" gilt ohne stopped ohnehin als frei.
     */
    fun wokeUp(lock: OnDemandLock): Boolean =
        lock == OnDemandLock.SUSPEND && suspended == true && stopped == false

    /** Frei = weder eingefroren noch deaktiviert (beides sicher gelesen). */
    val isUnlocked: Boolean get() = suspended == false && disabled == false
}

/** Ergebnis von Sperren/Freigeben inkl. danach gelesenem Zustand. */
data class OnDemandResult(val packageName: String, val ok: Boolean, val state: LockState, val raw: String)

object OnDemand {
    const val DEFAULT_GRACE_SECONDS = 60

    /** Auswahl in der Oberflaeche; 0 = nur beim Bildschirm-Aus. */
    val GRACE_CHOICES = listOf(10, 60, 300, 0)

    /** UsageEvents-Typen (android.app.usage.UsageEvents.Event), hier als Zahl fuer JVM-Tests. */
    const val EVENT_RESUMED = 1
    const val EVENT_PAUSED = 2
    const val EVENT_STOPPED = 23

    fun lockOf(e: LifecycleEntry): OnDemandLock = e.onDemandLock ?: OnDemandLock.SUSPEND

    fun graceOf(e: LifecycleEntry): Int = e.onDemandGraceSeconds ?: DEFAULT_GRACE_SECONDS

    /** Apps im Modus "Nur bei Bedarf" (eigene App und geschuetzte Pakete nie). */
    fun apps(entries: Map<String, LifecycleEntry>, ownPackage: String, protectedPackages: Set<String>): Set<String> =
        entries.filter { (pkg, e) ->
            e.onDemand == true && screenOffAllowed(pkg, ownPackage, pkg in protectedPackages)
        }.keys

    /**
     * Ist die App im Vordergrund? Aus den UsageEvents-Typen des Pakets (aeltestes zuerst):
     * der letzte RESUMED/PAUSED/STOPPED entscheidet. null = keine Aussage (keine Ereignisse).
     */
    fun foregroundFromEvents(eventTypes: List<Int>): Boolean? =
        eventTypes.lastOrNull { it == EVENT_RESUMED || it == EVENT_PAUSED || it == EVENT_STOPPED }
            ?.let { it == EVENT_RESUMED }

    /**
     * Ist die App im Vordergrund? JE ACTIVITY ausgewertet (aeltestes Ereignis zuerst, Paar = Klasse to Typ):
     * vorne, wenn bei irgendeiner Activity das letzte RESUMED/PAUSED/STOPPED ein RESUMED ist.
     *
     * Wichtig: Beim Wechsel zwischen zwei Bildschirmen DERSELBEN App meldet Android
     * A PAUSED -> B RESUMED -> A STOPPED. Das letzte Ereignis der App ist dann STOPPED, obwohl B vorne ist.
     * [foregroundFromEvents] hielt die App deshalb fuer verlassen, und "Nur bei Bedarf" sperrte sie nach
     * Ablauf der Frist mitten in der Benutzung. null = keine Aussage (keine Ereignisse).
     */
    fun foregroundFromActivityEvents(events: List<Pair<String?, Int>>): Boolean? {
        val last = HashMap<String, Int>()
        for ((cls, type) in events) {
            if (type == EVENT_RESUMED || type == EVENT_PAUSED || type == EVENT_STOPPED) last[cls.orEmpty()] = type
        }
        if (last.isEmpty()) return null
        return last.values.any { it == EVENT_RESUMED }
    }

    /**
     * Fallback ohne Nutzungszugriff: `dumpsys activity activities`. Ausgewertet werden die Zeilen
     * mit der obersten aktiven Activity (Bezeichnung je nach Android-Version verschieden).
     * null = keine solche Zeile gefunden (keine Aussage).
     */
    fun foregroundFromDumpsys(dump: String, packageName: String): Boolean? {
        val lines = dump.lineSequence()
            .map { it.trim() }
            .filter {
                it.startsWith("topResumedActivity") || it.startsWith("mResumedActivity") ||
                    it.startsWith("ResumedActivity")
            }
            .toList()
        if (lines.isEmpty()) return null
        return lines.any { " $packageName/" in it }
    }

    /** Bedarfs-Apps mit Sperrart "Nur beenden": ihr Start aus fremden Launchern wird erkannt. */
    fun stopApps(entries: Map<String, LifecycleEntry>, apps: Set<String>): Set<String> =
        apps.filter { entries[it]?.let { e -> lockOf(e) } == OnDemandLock.STOP }.toSet()

    /** Bedarfs-Apps mit Sperrart "Einfrieren": nur sie koennen trotz Sperre im Hintergrund geweckt werden. */
    fun suspendApps(entries: Map<String, LifecycleEntry>, apps: Set<String>): Set<String> =
        apps.filter { entries[it]?.let { e -> lockOf(e) } == OnDemandLock.SUSPEND }.toSet()

    /** Frist nach dem Verlassen abgelaufen? Bei Frist 0 sperrt nur das Bildschirm-Aus. */
    fun shouldLock(nowMs: Long, leftAtMs: Long?, graceSeconds: Int): Boolean =
        graceSeconds > 0 && leftAtMs != null && nowMs - leftAtMs >= graceSeconds * 1000L
}

/**
 * Zustand aus `dumpsys package <pkg>`, gelesen aus der Zeile "User 0:" (dort stehen
 * suspended/stopped/enabled; an anderen Stellen der Ausgabe kann "enabled=" anderes meinen).
 * enabled: 0 Standard, 1 aktiviert, 2 deaktiviert, 3 vom Nutzer deaktiviert, 4 bis zur Nutzung.
 */
fun parseLockState(dumpsys: String): LockState {
    val line = dumpsys.lineSequence().firstOrNull { it.trim().startsWith("User 0:") } ?: dumpsys
    fun bool(key: String) = Regex("\\b$key=(true|false)").find(line)?.groupValues?.get(1)?.let { it == "true" }
    val enabled = Regex("\\benabled=(\\d)").find(line)?.groupValues?.get(1)?.toInt()
    return LockState(
        suspended = bool("suspended"),
        disabled = enabled?.let { it == 2 || it == 3 || it == 4 },
        stopped = bool("stopped")
    )
}

/** Sperren und Freigeben ueber die Shell; Erfolg zaehlt nur nach frisch gelesenem Zustand. */
/**
 * @param dialogMessage Text fuer den System-Dialog beim Tippen auf eine eingefrorene App
 *   (`pm suspend --dialogMessage`), statt "wird ueber die App Shell verwaltet".
 */
class OnDemandExecutor(private val backend: ShellBackend, private val dialogMessage: String? = null) {

    fun state(packageName: String): LockState =
        parseLockState(backend.exec("dumpsys", "package", packageName).stdout)

    fun lock(packageName: String, lock: OnDemandLock): OnDemandResult {
        val raw = StringBuilder()
        raw.append(rawOf(backend.exec("am", "force-stop", packageName)))
        val res = when (lock) {
            OnDemandLock.SUSPEND -> suspend(packageName)
            OnDemandLock.DISABLE -> backend.exec("pm", "disable-user", "--user", "0", packageName)
            OnDemandLock.STOP -> ShellResult(0, "", "") // force-stop oben genuegt
        }
        raw.append(' ').append(rawOf(res))
        val st = state(packageName)
        return OnDemandResult(packageName, st.isLocked(lock), st, raw.toString().trim())
    }

    /** Mit eigenem Dialogtext einfrieren; kennt das Geraet die Option nicht, ohne Text. */
    private fun suspend(packageName: String): ShellResult {
        val msg = dialogMessage
        if (!msg.isNullOrBlank()) {
            val r = backend.exec("pm", "suspend", "--user", "0", "--dialogMessage", msg, packageName)
            if (r.exitCode == 0 && !r.stderr.contains("Unknown option", ignoreCase = true)) return r
        }
        return backend.suspendPackage(packageName)
    }

    /** Hebt beide Sperrarten auf (auch eine, die vor einem Wechsel der Sperrart gesetzt wurde). */
    fun unlock(packageName: String): OnDemandResult {
        val before = state(packageName)
        val raw = StringBuilder()
        if (before.suspended != false) raw.append(rawOf(backend.unsuspendPackage(packageName))).append(' ')
        if (before.disabled != false) raw.append(rawOf(backend.exec("pm", "enable", "--user", "0", packageName)))
        val st = state(packageName)
        return OnDemandResult(packageName, st.isUnlocked, st, raw.toString().trim())
    }
}
