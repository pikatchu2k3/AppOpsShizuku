package de.minipc.appopsshizuku.appops

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persistenz der Automatik-Regeln (JSON in SharedPreferences) + gemerkter Aktiv-Zustand je Regel. */
class AutoRuleStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("auto_rules", Context.MODE_PRIVATE)

    fun all(): List<AutoRule> {
        val raw = prefs.getString(KEY_RULES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun put(rule: AutoRule) {
        val list = all().filterNot { it.id == rule.id } + rule
        save(list)
    }

    fun remove(id: String) {
        save(all().filterNot { it.id == id })
        prefs.edit().remove(KEY_ACTIVE + id).apply()
    }

    private fun save(list: List<AutoRule>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(KEY_RULES, arr.toString()).apply()
    }

    /** Gemerkter Aktiv-Zustand (um Uebergaenge zu erkennen); null = noch nie ausgewertet. */
    fun lastActive(id: String): Boolean? =
        if (prefs.contains(KEY_ACTIVE + id)) prefs.getBoolean(KEY_ACTIVE + id, false) else null

    fun setLastActive(id: String, active: Boolean) =
        prefs.edit().putBoolean(KEY_ACTIVE + id, active).apply()

    companion object {
        private const val KEY_RULES = "rules"
        private const val KEY_ACTIVE = "active_"

        fun toJson(r: AutoRule): JSONObject = JSONObject().apply {
            put("id", r.id); put("name", r.name); put("enabled", r.enabled)
            put("trigger", r.trigger.name); put("days", r.days)
            put("startMin", r.startMin); put("endMin", r.endMin)
            put("ssid", r.ssid); put("whenConnected", r.whenConnected)
            put("action", r.action.name)
            put("packages", JSONArray(r.packages))
        }

        fun fromJson(o: JSONObject): AutoRule? = runCatching {
            val pkgs = o.optJSONArray("packages")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            AutoRule(
                id = o.getString("id"),
                name = o.optString("name"),
                enabled = o.optBoolean("enabled", true),
                trigger = RuleTrigger.valueOf(o.optString("trigger", "TIME")),
                days = o.optInt("days", 0b1111111),
                startMin = o.optInt("startMin", 0),
                endMin = o.optInt("endMin", 0),
                ssid = o.optString("ssid"),
                whenConnected = o.optBoolean("whenConnected", true),
                action = RuleAction.valueOf(o.optString("action", "LOCKDOWN")),
                packages = pkgs
            )
        }.getOrNull()
    }
}
