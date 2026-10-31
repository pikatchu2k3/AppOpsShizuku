package de.minipc.appopsshizuku.appops

import android.content.Context

/**
 * Quarantaene fuer neu installierte Apps: bis der Nutzer sie freigibt, bekommt eine neue App
 * keine Hintergrunddaten, keinen Zugriff auf die Zwischenablage und darf nicht im Hintergrund laufen.
 *
 * Erkannt wird ueber `firstInstallTime` > Zeitpunkt der letzten Pruefung (robust ueber Neustarts,
 * anders als die Sequenznummer von getChangedPackages). Ein Update aendert firstInstallTime nicht,
 * eine Neuinstallation schon. System-Apps und die eigene App nie.
 */
object Quarantine {

    data class Candidate(val packageName: String, val firstInstallTime: Long, val isSystem: Boolean)

    /** Ops, die in der Quarantaene still blockiert werden (ignore: kein Absturz der App). */
    val OPS: List<String> = listOf("READ_CLIPBOARD", "RUN_ANY_IN_BACKGROUND")
    const val OP_MODE = "ignore"

    /** Netz-Schalter der Quarantaene. */
    val NET: NetSwitch = NetSwitch.BG_DATA

    fun newPackages(candidates: List<Candidate>, sinceMs: Long, ownPackage: String, already: Set<String>): List<String> =
        candidates.filter {
            !it.isSystem && it.packageName != ownPackage && it.firstInstallTime > sinceMs &&
                it.packageName !in already
        }.map { it.packageName }.sorted()
}

class QuarantineStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("quarantine", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    /** Bis zu diesem Zeitpunkt installierte Apps sind schon geprueft. */
    var checkedUntil: Long
        get() = prefs.getLong(KEY_SINCE, 0L)
        set(v) = prefs.edit().putLong(KEY_SINCE, v).apply()

    fun quarantined(): Map<String, Long> = prefs.all
        .filterKeys { !it.startsWith("__") }
        .mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()

    fun isQuarantined(pkg: String): Boolean = prefs.contains(pkg) && !pkg.startsWith("__")

    fun since(pkg: String): Long? = if (isQuarantined(pkg)) prefs.getLong(pkg, 0L) else null

    fun add(pkg: String, at: Long) = prefs.edit().putLong(pkg, at).apply()

    fun remove(pkg: String) = prefs.edit().remove(pkg).apply()

    private companion object {
        const val KEY_ENABLED = "__enabled"
        const val KEY_SINCE = "__since"
    }
}
