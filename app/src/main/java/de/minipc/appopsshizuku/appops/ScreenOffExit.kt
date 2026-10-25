package de.minipc.appopsshizuku.appops

import de.minipc.appopsshizuku.shizuku.ShellBackend
import de.minipc.appopsshizuku.shizuku.isFailure

/**
 * Feature 3 "Screen-Off-Exit": gewaehlte Apps beim Bildschirm-Aus anhalten und
 * (optional) beim Bildschirm-An wieder freigeben. Standard: AUS.
 *
 * Reine Entscheidungs- und Pruef-Logik (JVM-testbar). Das Erkennen von
 * Bildschirm an/aus macht [de.minipc.appopsshizuku.LifecycleScreenService]:
 * ACTION_SCREEN_ON/OFF sind ab Android 8 NICHT per Manifest-Empfaenger
 * empfangbar, nur per zur Laufzeit registriertem Empfaenger in einem laufenden
 * Prozess - deshalb der Vordergrunddienst.
 */
enum class ScreenOffEffect(val json: String) {
    /** `pm suspend` / `pm unsuspend` - zuruecknehmbar, Standardwirkung. */
    SUSPEND("suspend"),

    /** `am force-stop` - hart, nichts zurueckzunehmen. */
    FORCE_STOP("force_stop");

    companion object {
        fun fromJson(s: String?): ScreenOffEffect? = entries.firstOrNull { it.json == s }
    }
}

/** Eine einzelne Aktion fuer ein Paket. */
data class ScreenAction(val packageName: String, val kind: Kind) {
    enum class Kind { SUSPEND, UNSUSPEND, FORCE_STOP }
}

/** Ergebnis einer ausgefuehrten Aktion inkl. frisch gelesenem Zustand. */
data class ScreenActionResult(
    val action: ScreenAction,
    val ok: Boolean,
    val suspended: Boolean?,
    val stopped: Boolean?,
    val raw: String
) {
    fun describe(): String {
        val what = when (action.kind) {
            ScreenAction.Kind.SUSPEND -> "eingefroren"
            ScreenAction.Kind.UNSUSPEND -> "freigegeben"
            ScreenAction.Kind.FORCE_STOP -> "beendet"
        }
        val state = "suspended=${suspended ?: "?"} stopped=${stopped ?: "?"}"
        return if (ok) "${action.packageName}: $what ($state)"
        else "${action.packageName}: NICHT $what ($state)" + (if (raw.isNotBlank()) " [${raw.take(120)}]" else "")
    }
}

/** Darf fuer diese App Screen-Off-Exit ueberhaupt eingeschaltet werden? */
fun screenOffAllowed(packageName: String, ownPackage: String, isSystemApp: Boolean): Boolean =
    packageName != ownPackage && !isSystemApp

/** Solange mindestens eine erlaubte App den Haken hat, muss der Dienst laufen. */
/**
 * Nur Eintraege installierter Apps. Ohne diesen Filter hielte der Eintrag einer
 * deinstallierten App den Dienst samt Benachrichtigung am Leben (realer Fall 10/2026).
 */
fun installedOnly(
    entries: Map<String, LifecycleEntry>,
    isInstalled: (String) -> Boolean
): Map<String, LifecycleEntry> = entries.filterKeys(isInstalled)

fun screenOffServiceNeeded(
    entries: Map<String, LifecycleEntry>,
    ownPackage: String,
    systemPackages: Set<String>
): Boolean = entries.any { (pkg, e) ->
    e.screenOffExit == true && screenOffAllowed(pkg, ownPackage, pkg in systemPackages)
}

/**
 * Was bei einem Bildschirm-Wechsel je App zu tun ist.
 *
 * - Bildschirm aus: SUSPEND (Standard) oder FORCE_STOP. Bereits eingefrorene Apps
 *   ([alreadySuspended]) werden nicht angefasst - die hat der Nutzer selbst
 *   eingefroren, sie beim Einschalten "freizugeben" waere falsch.
 * - Bildschirm an: nur Apps freigeben, die WIR eingefroren haben ([suspendedByUs])
 *   und bei denen "wieder freigeben" nicht abgeschaltet ist.
 * - Eigene App und System-Apps: nie.
 */
fun screenActions(
    entries: Map<String, LifecycleEntry>,
    screenOn: Boolean,
    ownPackage: String,
    systemPackages: Set<String>,
    suspendedByUs: Set<String>,
    alreadySuspended: Set<String> = emptySet()
): List<ScreenAction> {
    val out = mutableListOf<ScreenAction>()
    if (screenOn) {
        // Nur was WIR eingefroren haben. Auch wenn der Haken inzwischen aus ist (oder der
        // Eintrag fehlt), wird die App freigegeben - sonst bliebe sie ewig eingefroren.
        for (pkg in suspendedByUs.sorted()) {
            if (!screenOffAllowed(pkg, ownPackage, pkg in systemPackages)) continue
            val e = entries[pkg] ?: LifecycleEntry()
            val release = e.releaseOnScreenOn ?: true
            if (release || e.screenOffExit != true) out.add(ScreenAction(pkg, ScreenAction.Kind.UNSUSPEND))
        }
        return out
    }
    for ((pkg, e) in entries.toSortedMap()) {
        if (e.screenOffExit != true) continue
        if (!screenOffAllowed(pkg, ownPackage, pkg in systemPackages)) continue
        when (e.screenOffEffect ?: ScreenOffEffect.SUSPEND) {
            ScreenOffEffect.SUSPEND ->
                if (pkg !in alreadySuspended) out.add(ScreenAction(pkg, ScreenAction.Kind.SUSPEND))
            ScreenOffEffect.FORCE_STOP -> out.add(ScreenAction(pkg, ScreenAction.Kind.FORCE_STOP))
        }
    }
    return out
}

/** `stopped=true|false` aus `dumpsys package <pkg>`; null, wenn nicht enthalten. */
fun parseStopped(dumpsys: String): Boolean? {
    val m = Regex("\\bstopped=(true|false)").find(dumpsys) ?: return null
    return m.groupValues[1] == "true"
}

/**
 * Hat das Geraet die Aktion wirklich uebernommen? Entscheidend ist der danach
 * gelesene Zustand, nicht der Rueckgabewert des Befehls.
 */
fun screenActionVerified(kind: ScreenAction.Kind, suspended: Boolean?, stopped: Boolean?): Boolean = when (kind) {
    ScreenAction.Kind.SUSPEND -> suspended == true
    ScreenAction.Kind.UNSUSPEND -> suspended == false
    ScreenAction.Kind.FORCE_STOP -> stopped == true
}

/** Fuehrt Aktionen aus und liest danach `dumpsys package` - ehrliches Ergebnis je App. */
class ScreenOffExecutor(private val backend: ShellBackend) {

    fun run(actions: List<ScreenAction>): List<ScreenActionResult> = actions.map { a ->
        val res = when (a.kind) {
            ScreenAction.Kind.SUSPEND -> backend.suspendPackage(a.packageName)
            ScreenAction.Kind.UNSUSPEND -> backend.unsuspendPackage(a.packageName)
            ScreenAction.Kind.FORCE_STOP -> backend.exec("am", "force-stop", a.packageName)
        }
        val dump = backend.exec("dumpsys", "package", a.packageName).stdout
        val suspended = parseSuspended(dump)
        val stopped = parseStopped(dump)
        val ok = screenActionVerified(a.kind, suspended, stopped)
        ScreenActionResult(a, ok, suspended, stopped, if (ok && !res.isFailure()) "" else rawOf(res))
    }

    /** Ist die App gerade eingefroren? (fuer [screenActions] alreadySuspended) */
    fun isSuspended(packageName: String): Boolean =
        parseSuspended(backend.exec("dumpsys", "package", packageName).stdout) == true
}

/** Neue Menge "von uns eingefroren" nach ausgefuehrten Aktionen. */
fun updateSuspendedByUs(current: Set<String>, results: List<ScreenActionResult>): Set<String> {
    val out = current.toMutableSet()
    for (r in results) {
        when (r.action.kind) {
            ScreenAction.Kind.SUSPEND -> if (r.suspended == true) out.add(r.action.packageName)
            ScreenAction.Kind.UNSUSPEND -> if (r.suspended == false) out.remove(r.action.packageName)
            ScreenAction.Kind.FORCE_STOP -> Unit
        }
    }
    return out
}
