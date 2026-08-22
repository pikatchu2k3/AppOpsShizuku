package de.minipc.appopsshizuku.appops

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Persistenz fuer Privacy-Profile (13 Ops pro App) in SharedPreferences
 * ("privacy_profiles", Schluessel = Paketname, Wert = {opName: mode}).
 * Export/Import als JSON-Datei (filesDir/privacy_profiles.json), Format:
 * [{"packageName": "...", "opModes": {"CAMERA": "deny", ...}}, ...]
 * — fuer Backup und Klon-Backup (der Klon hat eigene Daten).
 */
class PrivacyProfileStore(context: Context) {

    private val prefs =
        context.getSharedPreferences("privacy_profiles", Context.MODE_PRIVATE)
    private val exportDir = context.filesDir

    /** Export-Ziel im App-Speicher. */
    fun exportFile(): File = File(exportDir, FILE_NAME)

    /** Letzten gesetzten Modus einer Op merken (auch "default"). */
    fun setOpMode(packageName: String, opName: String, mode: String) {
        val profile = JSONObject(getProfileJson(packageName))
        profile.put(opName, mode)
        prefs.edit().putString(packageName, profile.toString()).apply()
    }

    /** Gespeichertes Profil einer App (leer wenn keins existiert). */
    fun getProfile(packageName: String): Map<String, String> {
        val json = getProfileJson(packageName)
        if (json.isEmpty()) return emptyMap()
        return jsonObjectToMap(JSONObject(json))
    }

    /** Profil einer App loeschen (z.B. nach "Alle auf Standard"). */
    fun removeProfile(packageName: String) {
        prefs.edit().remove(packageName).apply()
    }

    /**
     * Alle gespeicherten Profile ueber alle Apps — die gesamte prefs-Datei,
     * wie sie beim Export als JSON geschrieben wird.
     */
    fun allProfiles(): Map<String, Map<String, String>> {
        val out = mutableMapOf<String, Map<String, String>>()
        for ((pkg, value) in prefs.all) {
            if (value !is String || value.isEmpty()) continue
            runCatching { out[pkg] = jsonObjectToMap(JSONObject(value)) }
        }
        return out
    }

    /** Importierte Profile in die Prefs mergen (bekannte Ops bleiben ueberschrieben). */
    fun importProfiles(profiles: Map<String, Map<String, String>>) {
        val editor = prefs.edit()
        for ((pkg, opModes) in profiles) {
            val merged = getProfile(pkg) + opModes
            editor.putString(pkg, JSONObject(merged).toString())
        }
        editor.apply()
    }

    private fun getProfileJson(packageName: String): String =
        prefs.getString(packageName, null).orEmpty()

    companion object {
        const val FILE_NAME = "privacy_profiles.json"

        /** Profile -> JSON-Text: Array aus {packageName, opModes}. */
        fun toJson(profiles: Map<String, Map<String, String>>): String {
            val array = org.json.JSONArray()
            for ((pkg, opModes) in profiles) {
                array.put(
                    JSONObject()
                        .put("packageName", pkg)
                        .put("opModes", JSONObject(opModes))
                )
            }
            return array.toString(2)
        }

        /** JSON-Text -> Profile (wirft bei ungueltigem JSON eine Exception). */
        fun fromJson(json: String): Map<String, Map<String, String>> {
            val array = org.json.JSONArray(json.trim())
            val out = mutableMapOf<String, Map<String, String>>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val pkg = obj.optString("packageName")
                if (pkg.isEmpty()) continue
                out[pkg] = jsonObjectToMap(obj.optJSONObject("opModes") ?: JSONObject())
            }
            return out
        }

        private fun jsonObjectToMap(obj: JSONObject): Map<String, String> {
            val out = mutableMapOf<String, String>()
            for (key in obj.keys()) {
                val value = obj.optString(key)
                if (value.isNotEmpty()) out[key] = value
            }
            return out
        }
    }
}
