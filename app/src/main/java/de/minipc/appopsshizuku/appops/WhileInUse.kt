package de.minipc.appopsshizuku.appops

import android.content.Context

/**
 * "Nur waehrend Nutzung": Fuer Kontakte, Kalender, Anrufliste, Medien, Konten ... kennt Android keinen
 * Modus "nur im Vordergrund". Hier wird die Berechtigung erteilt, sobald die App vorne ist, und
 * [GRACE_MS] nach dem Verlassen (beim Bildschirm-Aus sofort) wieder entzogen.
 *
 * Reine Entscheidungslogik ([decide]); Erkennen von Vordergrund/Bildschirm macht
 * LifecycleScreenService, Erteilen/Entziehen der AppOpsController.
 */
object WhileInUse {
    const val MODE = "in-use"
    const val GRACE_MS = 10_000L

    enum class Action { GRANT, REVOKE }

    /**
     * @param foreground Paket -> vorne? (null = keine Aussage, dann bleibt alles wie es ist)
     * @param grantedByUs Pakete, deren Berechtigungen WIR gerade erteilt haben
     * @param leftAt Paket -> seit wann nicht mehr vorne (wird hier gepflegt)
     * @param hasForegroundService nur fuer Apps mit eingeschalteter Ausnahme true, wenn ein Vordergrunddienst
     *   laeuft; wird nur gefragt, wenn ohnehin entzogen wuerde. Dann bleibt die Berechtigung (pm revoke
     *   wuerde den Prozess samt Dienst beenden), spaetestens nach Ende des Dienstes wird entzogen.
     */
    fun decide(
        now: Long,
        apps: Set<String>,
        foreground: Map<String, Boolean?>,
        grantedByUs: Set<String>,
        leftAt: MutableMap<String, Long>,
        screenOn: Boolean = true,
        hasForegroundService: (String) -> Boolean = { false }
    ): Map<String, Action> {
        val out = mutableMapOf<String, Action>()
        for (pkg in apps) {
            if (!screenOn) {
                leftAt.remove(pkg)
                if (pkg in grantedByUs && !hasForegroundService(pkg)) out[pkg] = Action.REVOKE
                continue
            }
            when (foreground[pkg]) {
                true -> {
                    leftAt.remove(pkg)
                    if (pkg !in grantedByUs) out[pkg] = Action.GRANT
                }
                false -> if (pkg in grantedByUs) {
                    val since = leftAt.getOrPut(pkg) { now }
                    if (now - since >= GRACE_MS && !hasForegroundService(pkg)) {
                        out[pkg] = Action.REVOKE
                        leftAt.remove(pkg)
                    }
                }
                null -> Unit
            }
        }
        // Modus fuer eine App ausgeschaltet, Berechtigung aber noch von uns erteilt -> zuruecknehmen
        for (pkg in grantedByUs - apps) out[pkg] = Action.REVOKE
        return out
    }
}

/** Erkennung aus `dumpsys activity services <pkg>`: laeuft ein Dienst des Pakets als Vordergrunddienst? */
object ForegroundServices {
    fun isRunning(dump: String, pkg: String): Boolean =
        dump.split("* ServiceRecord{").drop(1).any { block ->
            block.substringBefore('\n').contains(" $pkg/") && "isForeground=true" in block
        }
}

/** Speicher: Paket -> Ops im Modus "nur waehrend Nutzung"; dazu, wem WIR gerade erteilt haben. */
class WhileInUseStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("while_in_use", Context.MODE_PRIVATE)

    fun ops(pkg: String): Set<String> =
        prefs.getString(pkg, null).orEmpty().split(',').filter { it.isNotBlank() }.toSet()

    fun has(pkg: String, op: String): Boolean = op in ops(pkg)

    fun set(pkg: String, op: String, on: Boolean) {
        val next = if (on) ops(pkg) + op else ops(pkg) - op
        prefs.edit().apply {
            if (next.isEmpty()) remove(pkg) else putString(pkg, next.sorted().joinToString(","))
        }.apply()
    }

    fun all(): Map<String, Set<String>> = prefs.all
        .filterKeys { !it.startsWith("__") }
        .mapValues { (_, v) -> (v as? String).orEmpty().split(',').filter { it.isNotBlank() }.toSet() }
        .filterValues { it.isNotEmpty() }

    fun remove(pkg: String) = prefs.edit().remove(pkg).apply()

    /** Apps, bei denen auch ein laufender Vordergrunddienst den Entzug aufschiebt (Standard: aus). */
    var keepWithForegroundService: Set<String>
        get() = prefs.getStringSet(KEY_FGS, emptySet()).orEmpty().toSet()
        set(v) = prefs.edit().putStringSet(KEY_FGS, v).apply()

    var grantedByUs: Set<String>
        get() = prefs.getStringSet(KEY_GRANTED, emptySet()).orEmpty().toSet()
        set(v) = prefs.edit().putStringSet(KEY_GRANTED, v).apply()

    private companion object {
        const val KEY_GRANTED = "__granted"
        const val KEY_FGS = "__fgs"
    }
}
