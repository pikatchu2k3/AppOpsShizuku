package de.minipc.appopsshizuku.appops

import android.content.Context
import de.minipc.appopsshizuku.R
import de.minipc.appopsshizuku.ui.OpLabels
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gemeinsames Aenderungsprotokoll ueber alle Funktionen: jede von dieser App vorgenommene Aenderung
 * landet hier mit Zeitpunkt und fertiger Beschreibung. Zwei Typen sind einzeln rueckgaengig zu machen
 * (Op-Modus, Netz-Schalter) - dafuer wird der vorherige Wert mitgespeichert. Sammel-/Systemaktionen
 * (Lockdown, Quarantaene, "alles blockieren") werden als Info-Eintrag gefuehrt; sie haben ihre eigene
 * Gegenaktion und sind hier nicht einzeln umkehrbar.
 *
 * Alles liegt lokal in SharedPreferences (JSON), begrenzt auf [MAX] Eintraege.
 */
object ChangeLog {

    const val MAX = 200
    private const val PREFS = "change_log"
    private const val KEY = "entries"

    enum class Type { OP, NET, INFO }

    data class Entry(
        val id: Long,
        val time: Long,
        val type: Type,
        val label: String,
        val packageName: String? = null,
        val opName: String? = null,
        val prevMode: String? = null,
        val netSwitch: String? = null,
        val prevOn: Boolean? = null
    ) {
        val undoable: Boolean get() = type == Type.OP || type == Type.NET
    }

    // ---- reine (JVM-testbare) Kodierung ----

    fun toJson(e: Entry): JSONObject = JSONObject().apply {
        put("id", e.id); put("t", e.time); put("type", e.type.name); put("label", e.label)
        e.packageName?.let { put("pkg", it) }
        e.opName?.let { put("op", it) }
        e.prevMode?.let { put("prev", it) }
        e.netSwitch?.let { put("sw", it) }
        e.prevOn?.let { put("prevOn", it) }
    }

    fun fromJson(o: JSONObject): Entry? = runCatching {
        Entry(
            id = o.getLong("id"),
            time = o.getLong("t"),
            type = Type.valueOf(o.getString("type")),
            label = o.getString("label"),
            packageName = o.optString("pkg").ifEmpty { null },
            opName = o.optString("op").ifEmpty { null },
            prevMode = o.optString("prev").ifEmpty { null },
            netSwitch = o.optString("sw").ifEmpty { null },
            prevOn = if (o.has("prevOn")) o.getBoolean("prevOn") else null
        )
    }.getOrNull()

    /** Neuesten Eintrag vorne, auf [MAX] begrenzen. */
    fun prepend(list: List<Entry>, e: Entry, max: Int = MAX): List<Entry> =
        (listOf(e) + list).take(max)

    // ---- Android-Teil ----

    fun all(context: Context): List<Entry> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    @Synchronized
    private fun add(context: Context, e: Entry) = save(context, prepend(all(context), e))

    /** Eintrag per id entfernen (nach erfolgreichem Rueckgaengig). */
    @Synchronized
    fun remove(context: Context, id: Long) = save(context, all(context).filterNot { it.id == id })

    @Synchronized
    fun clear(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()

    private fun nextId() = System.nanoTime()

    private fun appLabel(context: Context, pkg: String): String = runCatching {
        context.packageManager.getApplicationInfo(pkg, 0).loadLabel(context.packageManager).toString()
    }.getOrDefault(pkg)

    /** Op-Modus-Aenderung (einzeln rueckgaengig: zurueck auf [prevMode]). */
    fun recordOp(context: Context, pkg: String, op: AppOp, prevMode: String?, newMode: String) {
        if (prevMode == newMode) return
        val opLabel = OpLabels.of(context, op)
        // "Nur waehrend Nutzung" ist kein Systemzustand: ein Rueckgaengig dorthin ginge ueber setMode
        // als Erteilen. Darum nur als Info fuehren.
        if (prevMode == WhileInUse.MODE) {
            add(
                context,
                Entry(
                    nextId(), System.currentTimeMillis(), Type.INFO,
                    label = context.getString(
                        R.string.log_op, appLabel(context, pkg), opLabel, prevMode, newMode
                    )
                )
            )
            return
        }
        add(
            context,
            Entry(
                nextId(), System.currentTimeMillis(), Type.OP,
                label = context.getString(
                    R.string.log_op, appLabel(context, pkg), opLabel, prevMode ?: "default", newMode
                ),
                packageName = pkg, opName = op.opName, prevMode = prevMode ?: "default"
            )
        )
    }

    /** Netz-Schalter (einzeln rueckgaengig: zurueck auf [prevOn]). */
    fun recordNet(context: Context, pkg: String, sw: NetSwitch, prevOn: Boolean, newOn: Boolean) {
        if (prevOn == newOn) return
        val swLabel = context.getString(
            when (sw) {
                NetSwitch.BG_DATA -> R.string.badge_bg_net
                NetSwitch.OFFLINE -> R.string.badge_offline
                NetSwitch.BG_RESTRICT -> R.string.net_bg_restrict
            }
        )
        add(
            context,
            Entry(
                nextId(), System.currentTimeMillis(), Type.NET,
                label = context.getString(
                    if (newOn) R.string.log_net_on else R.string.log_net_off, appLabel(context, pkg), swLabel
                ),
                packageName = pkg, netSwitch = sw.name, prevOn = prevOn
            )
        )
    }

    /** Sammel-/Systemaktion (nicht einzeln rueckgaengig). */
    fun recordInfo(context: Context, label: String) {
        add(context, Entry(nextId(), System.currentTimeMillis(), Type.INFO, label = label))
    }
}
