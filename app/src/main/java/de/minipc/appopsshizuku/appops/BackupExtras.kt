package de.minipc.appopsshizuku.appops

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Zusatzteil der Sicherung ("extras" im v2-Dokument): Automatik-Regeln, "Nur waehrend Nutzung",
 * Quarantaene-Schalter und Lockdown-Auswahl. Additiv - alte Dateien ohne "extras" bleiben lesbar.
 *
 * Bewusst NICHT gesichert wird Laufzeitzustand des Geraets (wem gerade etwas erteilt/entzogen ist,
 * welche Apps in Quarantaene stehen): der gilt nur fuer das Geraet, auf dem er entstand.
 */
data class BackupExtras(
    val rules: List<AutoRule> = emptyList(),
    val whileInUse: Map<String, Set<String>> = emptyMap(),
    val quarantineEnabled: Boolean? = null,
    val lockdownOps: Set<String>? = null
) {
    val isEmpty: Boolean
        get() = rules.isEmpty() && whileInUse.isEmpty() && quarantineEnabled != true && lockdownOps == null

    /** Anzahl gesicherter Einstellungen (fuer die Rueckmeldung). */
    val count: Int get() = rules.size + whileInUse.size

    fun toJson(): JSONObject = JSONObject().apply {
        put("rules", JSONArray().also { a -> rules.forEach { a.put(AutoRuleStore.toJson(it)) } })
        put("whileInUse", JSONObject().also { o ->
            whileInUse.forEach { (pkg, ops) -> o.put(pkg, JSONArray(ops.sorted())) }
        })
        quarantineEnabled?.let { put("quarantineEnabled", it) }
        lockdownOps?.let { put("lockdownOps", JSONArray(it.sorted())) }
    }

    companion object {
        fun fromJson(o: JSONObject?): BackupExtras {
            o ?: return BackupExtras()
            fun strings(a: JSONArray?) = a?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
            val rules = o.optJSONArray("rules")?.let { a ->
                (0 until a.length()).mapNotNull { AutoRuleStore.fromJson(a.getJSONObject(it)) }
            } ?: emptyList()
            val wiu = o.optJSONObject("whileInUse")?.let { w ->
                w.keys().asSequence().associateWith { strings(w.optJSONArray(it)).toSet() }.filterValues { it.isNotEmpty() }
            } ?: emptyMap()
            // Nur Katalog-Ops mit Berechtigung zulassen (fremde Datei darf nichts Unbekanntes einschleusen)
            val valid = PrivacyOps.PRIVACY_OPS.filter { it.permissionName != null }.map { it.opName }.toSet()
            return BackupExtras(
                rules = rules,
                whileInUse = wiu.mapValues { it.value intersect valid }.filterValues { it.isNotEmpty() },
                quarantineEnabled = if (o.has("quarantineEnabled")) o.optBoolean("quarantineEnabled") else null,
                lockdownOps = o.optJSONArray("lockdownOps")?.let { strings(it).toSet() intersect Lockdown.CHOICES.toSet() }
            )
        }

        /** Extras aus einem Sicherungsdokument (alte Array-Dateien: leer). */
        fun parse(json: String): BackupExtras {
            val t = json.trim()
            if (t.startsWith("[")) return BackupExtras()
            return fromJson(JSONObject(t).optJSONObject("extras"))
        }

        fun collect(context: Context): BackupExtras {
            val lock = LockdownStore(context)
            return BackupExtras(
                rules = AutoRuleStore(context).all(),
                whileInUse = WhileInUseStore(context).all(),
                quarantineEnabled = QuarantineStore(context).enabled,
                lockdownOps = lock.ops.takeIf { it != Lockdown.DEFAULT }
            )
        }

        /**
         * In die Speicher uebernehmen. "Nur waehrend Nutzung"-Apps gelten danach als "von uns erteilt":
         * der Waechter entzieht dann, sobald die App nicht vorne ist - sonst bliebe eine importierte
         * Einstellung wirkungslos, falls die Berechtigung gerade erteilt ist.
         */
        fun apply(context: Context, e: BackupExtras) {
            val rules = AutoRuleStore(context)
            e.rules.forEach { rules.put(it) }
            val wiu = WhileInUseStore(context)
            for ((pkg, ops) in e.whileInUse) ops.forEach { wiu.set(pkg, it, true) }
            if (e.whileInUse.isNotEmpty()) wiu.grantedByUs = wiu.grantedByUs + e.whileInUse.keys
            if (e.quarantineEnabled == true) {
                val q = QuarantineStore(context)
                if (!q.enabled) { q.checkedUntil = System.currentTimeMillis(); q.enabled = true }
            }
            e.lockdownOps?.let { if (!LockdownStore(context).active) LockdownStore(context).ops = it }
        }
    }
}
