package de.minipc.appopsshizuku.appops

import android.content.Context

/**
 * Persistenz fuer Lifecycle-Profile und Screen-Off-Exit (Feature 3).
 *
 * - "lifecycle_profiles": Schluessel = Paketname, Wert = JSON eines [LifecycleEntry]
 *   (Profil-Einstellungen, gemerkter Ist-Zustand, Screen-Off-Haken). Wird im
 *   v2-Export unter "lifecycle" mitgesichert.
 * - "lifecycle_state": Laufzeitzustand des Dienstes (welche Apps WIR eingefroren
 *   haben, letzte Meldung) - geraetebezogen, wird NICHT exportiert.
 */
class LifecycleStore(context: Context) {

    private val prefs = context.getSharedPreferences("lifecycle_profiles", Context.MODE_PRIVATE)
    private val state = context.getSharedPreferences("lifecycle_state", Context.MODE_PRIVATE)

    fun get(packageName: String): LifecycleEntry =
        lifecycleEntryFromJson(ProfileJson.parse(prefs.getString(packageName, null)))

    fun put(packageName: String, entry: LifecycleEntry) {
        prefs.edit().putString(packageName, lifecycleEntryToJson(entry).toString()).apply()
    }

    fun update(packageName: String, change: (LifecycleEntry) -> LifecycleEntry) =
        put(packageName, change(get(packageName)))

    fun all(): Map<String, LifecycleEntry> {
        val out = mutableMapOf<String, LifecycleEntry>()
        for ((pkg, value) in prefs.all) {
            if (value !is String || value.isEmpty()) continue
            out[pkg] = lifecycleEntryFromJson(ProfileJson.parse(value))
        }
        return out
    }

    /** App wurde deinstalliert: Eintrag und Laufzeitzustand verwerfen. */
    fun remove(packageName: String) {
        prefs.edit().remove(packageName).apply()
        if (packageName in suspendedByUs) suspendedByUs = suspendedByUs - packageName
        if (packageName in onDemandOpen) onDemandOpen = onDemandOpen - packageName
    }

    /** Import: feldweise mergen - in der Datei fehlende Felder bleiben unangetastet. */
    fun importEntries(entries: Map<String, LifecycleEntry>) {
        val editor = prefs.edit()
        for ((pkg, e) in entries) {
            editor.putString(pkg, lifecycleEntryToJson(get(pkg).mergedWith(e)).toString())
        }
        editor.apply()
    }

    var suspendedByUs: Set<String>
        get() = state.getStringSet(KEY_SUSPENDED_BY_US, emptySet())?.toSet().orEmpty()
        set(value) {
            state.edit().putStringSet(KEY_SUSPENDED_BY_US, value.toSet()).apply()
        }

    /** "Nur bei Bedarf": Apps, die gerade ueber unsere Verknuepfung geoeffnet (freigegeben) sind. */
    var onDemandOpen: Set<String>
        get() = state.getStringSet(KEY_ON_DEMAND_OPEN, emptySet())?.toSet().orEmpty()
        set(value) {
            state.edit().putStringSet(KEY_ON_DEMAND_OPEN, value.toSet()).apply()
        }

    /** Ueber die Verknuepfung geoeffnet: in die Offen-Liste + Zeitpunkt (Fenster fuer Nutzungsereignisse). */
    fun markOpened(packageName: String, timeMs: Long) {
        state.edit().putLong(KEY_OPENED_AT_PREFIX + packageName, timeMs).apply()
        onDemandOpen = onDemandOpen + packageName
    }

    /**
     * "Nur bei Bedarf": fremde Starter-Apps (z. B. AppDialer NXT), die gesperrte Bedarfs-Apps
     * freigeben und oeffnen duerfen. Vom Nutzer im Dialog erlaubt; geraetebezogen, nicht exportiert.
     */
    var onDemandCallers: Set<String>
        get() = state.getStringSet(KEY_ON_DEMAND_CALLERS, emptySet())?.toSet().orEmpty()
        set(value) {
            state.edit().putStringSet(KEY_ON_DEMAND_CALLERS, value.toSet()).apply()
        }

    fun openedAt(packageName: String): Long? =
        state.getLong(KEY_OPENED_AT_PREFIX + packageName, -1L).takeIf { it > 0 }

    /** Letzte Meldung des Dienstes (fuer die Oberflaeche), z. B. "Bildschirm aus: ...". */
    var lastReport: String
        get() = state.getString(KEY_LAST_REPORT, "").orEmpty()
        set(value) {
            state.edit().putString(KEY_LAST_REPORT, value).apply()
        }

    private companion object {
        const val KEY_SUSPENDED_BY_US = "suspended_by_us"
        const val KEY_LAST_REPORT = "last_report"
        const val KEY_ON_DEMAND_OPEN = "on_demand_open"
        const val KEY_ON_DEMAND_CALLERS = "on_demand_callers"
        const val KEY_OPENED_AT_PREFIX = "on_demand_opened_at_"
    }
}
