package de.minipc.appopsshizuku.appops

import de.minipc.appopsshizuku.shizuku.ShellBackend
import de.minipc.appopsshizuku.shizuku.isFailure
import de.minipc.appopsshizuku.whitelistContains
import org.json.JSONObject

/**
 * Pro App gesicherte Verhaltens-Einstellungen (QW-9).
 * Alle Felder sind nullable: null = "war beim Export nicht ermittelbar" und wird
 * beim Import NICHT angefasst (kein Zuruecksetzen auf einen geratenen Wert).
 */
data class BehaviorState(
    val standbyBucket: Int? = null,
    val batteryExempt: Boolean? = null,
    val frozen: Boolean? = null,
    val disabled: Boolean? = null
)

/**
 * Liest, schreibt und sichert die Verhaltens-Einstellungen einer App
 * (Standby-Stufe, Akku-Ausnahme, eingefroren, deaktiviert) - damit sie zusammen
 * mit den Berechtigungsprofilen in EINER Datei gesichert werden koennen.
 *
 * Ehrlichkeitsregel wie im Reiter "Verhalten": nach jeder Aenderung wird der
 * Zustand neu gelesen; passt er nicht, landet ein Klartext-Fehler im Ergebnis.
 */
class BehaviorBackup(private val backend: ShellBackend) {

    /** Zustand aller uebergebenen Pakete einsammeln (fuer den Export). */
    fun collect(packages: Collection<String>): Map<String, BehaviorState> {
        val disabled = disabledPackages()
        val whitelist = backend.exec("dumpsys", "deviceidle", "whitelist").stdout
        val out = mutableMapOf<String, BehaviorState>()
        for (pkg in packages) {
            out[pkg] = BehaviorState(
                standbyBucket = readStandbyBucket(pkg),
                batteryExempt = whitelistContains(whitelist, pkg),
                frozen = readSuspended(pkg),
                disabled = disabled.contains(pkg)
            )
        }
        return out
    }

    fun toJson(states: Map<String, BehaviorState>): JSONObject = behaviorStateToJson(states)

    fun fromJson(obj: JSONObject?): Map<String, BehaviorState> = behaviorStateFromJson(obj)

    /**
     * Zustand auf das Geraet anwenden. Gibt die Fehler zurueck (leer = alles ok).
     * Die eigene App wird nie deaktiviert oder eingefroren.
     */
    fun restoreToDevice(packageName: String, state: BehaviorState, ownPackage: String): List<String> {
        val errors = mutableListOf<String>()
        val isSelf = packageName == ownPackage

        state.disabled?.let { want ->
            if (isSelf) {
                if (want) errors.add("$packageName: eigene App wird nicht deaktiviert")
            } else if (readDisabled(packageName) != want) {
                backend.exec(
                    "pm", if (want) "disable-user" else "enable", "--user", "0", packageName
                )
                if (readDisabled(packageName) != want) {
                    errors.add("$packageName: deaktivieren ließ sich nicht ändern")
                }
            }
        }

        state.frozen?.let { want ->
            if (!(isSelf && want)) {
                if (readSuspended(packageName) != want) {
                    val res = if (want) backend.suspendPackage(packageName)
                    else backend.unsuspendPackage(packageName)
                    if (res.isFailure() || readSuspended(packageName) != want) {
                        errors.add("$packageName: einfrieren ließ sich nicht ändern")
                    }
                }
            }
        }

        // WICHTIG: Akku-Ausnahme VOR der Standby-Stufe anwenden. Am Geraet gemessen: erteilt man die
        // Ausnahme, setzt Android die Standby-Stufe selbst auf 5 (EXEMPTED). Wird die Stufe danach
        // gesetzt, meldet der Rueck-Lesewert zwar Erfolg, der Endzustand ist aber wieder 5.
        state.batteryExempt?.let { want ->
            if (readBatteryExempt(packageName) != want) {
                backend.exec("cmd", "deviceidle", "whitelist", (if (want) "+" else "-") + packageName)
                if (readBatteryExempt(packageName) != want) {
                    errors.add("$packageName: Akku-Ausnahme ließ sich nicht ändern")
                }
            }
        }
        state.standbyBucket?.let { want ->
            if (readStandbyBucket(packageName) != want) {
                val name = standbyName(want)
                if (name == null) {
                    // 5 = vom System ausgenommen (EXEMPTED) - laut Test am Geraet nicht per
                    // Shell setzbar ("Error: Unknown bucket: exempted"). Ehrlich melden.
                    errors.add(
                        if (want == 5) "$packageName: Stufe 5 (vom System ausgenommen) ist nicht setzbar"
                        else "$packageName: unbekannte Standby-Stufe $want"
                    )
                } else {
                    backend.exec("am", "set-standby-bucket", packageName, name)
                    if (readStandbyBucket(packageName) != want) {
                        // Am Geraet gemessen: hat die App eine Akku-Ausnahme, setzt Android die
                        // Stufe selbst auf 5 (EXEMPTED) - setzen ist dann wirkungslos.
                        errors.add(
                            if (readBatteryExempt(packageName))
                                "$packageName: Standby-Stufe nicht setzbar - die App hat eine " +
                                    "Akku-Ausnahme, Android hält sie auf Stufe \"ausgenommen\""
                            else "$packageName: Standby-Stufe blieb beim alten Wert"
                        )
                    }
                }
            }
        }

        return errors
    }

    // ------------------------------------------------------------------ Lesen

    private fun readStandbyBucket(packageName: String): Int? =
        standbyBucketOf(backend.exec("cmd", "activity", "get-standby-bucket", packageName).stdout)

    private fun readBatteryExempt(packageName: String): Boolean =
        whitelistContains(backend.exec("dumpsys", "deviceidle", "whitelist").stdout, packageName)

    private fun readSuspended(packageName: String): Boolean =
        parseSuspended(backend.exec("dumpsys", "package", packageName).stdout) ?: false

    private fun readDisabled(packageName: String): Boolean =
        disabledPackages().contains(packageName)

    private fun disabledPackages(): Set<String> =
        backend.exec("pm", "list", "packages", "-d").stdout
            .lineSequence()
            .mapNotNull { it.trim().removePrefix("package:").takeIf { l -> l.isNotEmpty() } }
            .toSet()
}

// ---------------------------------------------------------------- Reine Helfer (unit-testbar)

/** Verhaltens-Zustaende als JSON (nur gesetzte Felder; fehlende Felder fehlen auch im JSON). */
fun behaviorStateToJson(states: Map<String, BehaviorState>): JSONObject {
    val obj = JSONObject()
    for ((pkg, s) in states) {
        val entry = JSONObject()
        s.standbyBucket?.let { entry.put("standbyBucket", it) }
        s.batteryExempt?.let { entry.put("batteryExempt", it) }
        s.frozen?.let { entry.put("frozen", it) }
        s.disabled?.let { entry.put("disabled", it) }
        if (entry.length() > 0) obj.put(pkg, entry)
    }
    return obj
}

/** JSON -> Zustaende. Unbekannte Felder werden ignoriert, fehlende bleiben null. */
fun behaviorStateFromJson(obj: JSONObject?): Map<String, BehaviorState> {
    if (obj == null) return emptyMap()
    val out = mutableMapOf<String, BehaviorState>()
    for (pkg in obj.keys()) {
        val e = obj.optJSONObject(pkg) ?: continue
        out[pkg] = BehaviorState(
            standbyBucket = if (e.has("standbyBucket")) e.optInt("standbyBucket") else null,
            batteryExempt = if (e.has("batteryExempt")) e.optBoolean("batteryExempt") else null,
            frozen = if (e.has("frozen")) e.optBoolean("frozen") else null,
            disabled = if (e.has("disabled")) e.optBoolean("disabled") else null
        )
    }
    return out
}

/** Standby-Nummer -> Name fuer `am set-standby-bucket`. Unbekannte Nummern: null. */
fun standbyName(bucket: Int): String? = when (bucket) {
    10 -> "active"
    20 -> "working_set"
    30 -> "frequent"
    40 -> "rare"
    45 -> "restricted"
    50 -> "never"
    else -> null
}

/** Erste Zahl aus der Ausgabe von `cmd activity get-standby-bucket <pkg>`. */
fun standbyBucketOf(stdout: String): Int? =
    stdout.trim().lineSequence().firstOrNull()?.trim()?.toIntOrNull()

/** `suspended=true|false` aus `dumpsys package <pkg>`; null, wenn nicht enthalten. */
fun parseSuspended(dumpsys: String): Boolean? {
    val m = Regex("suspended=(true|false)").find(dumpsys) ?: return null
    return m.groupValues[1] == "true"
}
