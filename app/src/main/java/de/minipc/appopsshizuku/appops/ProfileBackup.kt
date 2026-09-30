package de.minipc.appopsshizuku.appops

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gemeinsame Export-/Import-Logik fuer Privacy-Profile UND Verhaltens-Einstellungen.
 *
 * Dateiformat v2: {"version":2,"profiles":[...],"behavior":{...},"lifecycle":{...}}
 * Aeltere Dateien (nur ein JSON-Array, oder v2 ohne "lifecycle") werden weiter eingelesen.
 * "lifecycle" (Feature 3) ist additiv: fehlende Felder werden beim Import nicht angefasst.
 *
 * Frueher lag diese Logik nur in AppOpsActivity (Detailseite). Jetzt kapselt
 * diese Klasse den reinen Datenteil (JSON-Aufbau/-Einlesen + Merge in die
 * Prefs), damit sowohl die globalen Menuepunkte in MainActivity als auch die
 * Detailseite denselben Code nutzen koennen. Keine UI, kein Threading.
 */
class ProfileBackup(context: Context) {

    private val appContext = context.applicationContext
    private val store = PrivacyProfileStore(appContext)
    private val lifecycleStore = LifecycleStore(appContext)

    data class ExportResult(
        val count: Int,
        val behaviorCount: Int,
        val path: String,
        val lifecycleCount: Int = 0
    )

    /** Projekt-Nummer im Export-Bericht fuer den Aufrufer (ohne das Backup anzuwenden). */
    data class ImportResult(
        val profiles: Map<String, Map<String, String>>,
        val behavior: Map<String, BehaviorState>,
        val lifecycle: Map<String, LifecycleEntry> = emptyMap()
    )

    /**
     * Schreibt Profile UND Verhaltens-Einstellungen als JSON in den App-Ordner.
     * count == 0 && behaviorCount == 0 bedeutet: nichts zu sichern gewesen.
     */
    fun export(behavior: Map<String, BehaviorState> = emptyMap()): ExportResult {
        val profiles = store.allProfiles()
        val behaviorJson = behaviorStateToJson(behavior)
        val lifecycleJson = lifecycleToJson(lifecycleStore.all())
        if (profiles.isEmpty() && behaviorJson.length() == 0 && lifecycleJson.length() == 0) {
            return ExportResult(0, 0, "")
        }
        val file = store.exportFile()
        val doc = JSONObject()
            .put("version", 2)
            .put("profiles", JSONArray(PrivacyProfileStore.toJson(profiles)))
            .put("behavior", behaviorJson)
            .put("lifecycle", lifecycleJson)
        file.writeText(doc.toString(2))
        return ExportResult(profiles.size, behaviorJson.length(), file.absolutePath, lifecycleJson.length())
    }

    /**
     * Liest die JSON-Datei aus der SAF-URI und merged die Profile in die Prefs.
     * Die Verhaltens-Einstellungen werden zurueckgegeben - sie werden vom Aufrufer
     * auf das Geraet angewendet (dafuer braucht er ein Backend).
     */
    fun import(uri: Uri): ImportResult {
        val json = appContext.contentResolver.openInputStream(uri)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: throw IllegalArgumentException("Datei nicht lesbar")
        val (profiles, behavior) = parseDocument(json)
        val lifecycle = parseLifecycle(json)
        store.importProfiles(profiles)
        // Nur Einstellungen mergen - angewendet wird ein Lifecycle-Profil erst per Knopf
        lifecycleStore.importEntries(lifecycle)
        return ImportResult(profiles, behavior, lifecycle)
    }

    companion object {
        /**
         * Akzeptiert das v2-Objekt und alte Array-Dateien (nur Profile).
         * Wirft bei ungueltigem JSON.
         */
        fun parseDocument(json: String): Pair<Map<String, Map<String, String>>, Map<String, BehaviorState>> {
            val trimmed = json.trim()
            if (trimmed.startsWith("[")) {
                return PrivacyProfileStore.fromJson(trimmed) to emptyMap()
            }
            val obj = JSONObject(trimmed)
            val profiles = obj.optJSONArray("profiles")
                ?.let { PrivacyProfileStore.fromJson(it.toString()) }
                ?: emptyMap()
            return profiles to behaviorStateFromJson(obj.optJSONObject("behavior"))
        }

        /**
         * Lifecycle-Teil (Feature 3). Alte Array-Dateien und v2-Dateien ohne
         * "lifecycle" liefern eine leere Map. Wirft bei ungueltigem JSON.
         */
        fun parseLifecycle(json: String): Map<String, LifecycleEntry> {
            val trimmed = json.trim()
            if (trimmed.startsWith("[")) return emptyMap()
            return lifecycleFromJson(JSONObject(trimmed).optJSONObject("lifecycle"))
        }
    }
}
