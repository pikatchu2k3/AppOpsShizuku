package de.minipc.appopsshizuku.appops

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import de.minipc.appopsshizuku.LifecycleScreenService
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
        val lifecycleCount: Int = 0,
        val extrasCount: Int = 0
    )

    /** Projekt-Nummer im Export-Bericht fuer den Aufrufer (ohne das Backup anzuwenden). */
    data class ImportResult(
        val profiles: Map<String, Map<String, String>>,
        val behavior: Map<String, BehaviorState>,
        val lifecycle: Map<String, LifecycleEntry> = emptyMap(),
        val extras: BackupExtras = BackupExtras(),
        /** Eintraege, die [ImportGuard] verworfen hat (ungueltiges/geschuetztes Paket, unbekannter Modus ...). */
        val dropped: Int = 0
    ) {
        /** Apps, die beim Uebernehmen deaktiviert oder eingefroren wuerden. */
        val disableOrFreezeCount: Int
            get() = behavior.values.count { it.disabled == true || it.frozen == true }
    }

    /**
     * Schreibt Profile UND Verhaltens-Einstellungen als JSON in die vom Nutzer gewaehlte Datei
     * (SAF-URI aus dem Speichern-Dialog). Frueher landete die Datei in filesDir - dort kommt
     * man ohne Root nicht heran und sie verschwindet beim Deinstallieren.
     * count == 0 && behaviorCount == 0 bedeutet: nichts zu sichern gewesen (dann wird nichts geschrieben).
     */
    fun export(target: Uri, behavior: Map<String, BehaviorState> = emptyMap()): ExportResult {
        val profiles = store.allProfiles()
        val behaviorJson = behaviorStateToJson(behavior)
        val lifecycleJson = lifecycleToJson(lifecycleStore.all())
        val extras = BackupExtras.collect(appContext)
        if (profiles.isEmpty() && behaviorJson.length() == 0 && lifecycleJson.length() == 0 && extras.isEmpty) {
            return ExportResult(0, 0, "")
        }
        val doc = JSONObject()
            .put("version", 2)
            .put("profiles", JSONArray(PrivacyProfileStore.toJson(profiles)))
            .put("behavior", behaviorJson)
            .put("lifecycle", lifecycleJson)
            .put("extras", extras.toJson())
        // "wt": vorhandene Datei abschneiden, sonst bleiben bei kuerzerem Inhalt Reste stehen
        val out = appContext.contentResolver.openOutputStream(target, "wt")
            ?: throw IllegalStateException("Datei nicht beschreibbar")
        out.bufferedWriter(Charsets.UTF_8).use { it.write(doc.toString(2)) }
        return ExportResult(profiles.size, behaviorJson.length(), displayName(target), lifecycleJson.length(), extras.count)
    }

    /** Lesbarer Dateiname der gewaehlten Datei (z. B. "appops-sicherung-2026-11-19.json"). */
    private fun displayName(uri: Uri): String = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: uri.toString()

    /**
     * Liest die JSON-Datei aus der SAF-URI und prueft sie mit [ImportGuard] - OHNE etwas zu speichern oder
     * anzuwenden. Die Oberflaeche zeigt das Ergebnis an; erst [commit] uebernimmt es.
     * Wirft bei ungueltigem JSON.
     */
    fun parse(uri: Uri): ImportResult {
        val json = appContext.contentResolver.openInputStream(uri)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: throw IllegalArgumentException("Datei nicht lesbar")
        val document = parseDocument(json)
        val life = parseLifecycle(json)
        val extras = BackupExtras.parse(json)
        return sanitize(document, life, extras, defaultProtection(document, life, extras))
    }

    /** Geschuetzte Pakete (System-UI, Einstellungen, Launcher, Tastatur, Shizuku, diese App ...). */
    private fun defaultProtection(
        document: Pair<Map<String, Map<String, String>>, Map<String, BehaviorState>>,
        lifecycle: Map<String, LifecycleEntry>,
        extras: BackupExtras
    ): (String) -> Boolean {
        val all = (document.second.keys + lifecycle.keys + extras.whileInUse.keys +
            extras.rules.flatMap { it.packages }).filter { ImportGuard.validPackage(it) }
        val blocked = runCatching { LifecycleScreenService.protectedPackagesOf(appContext, all) }
            .getOrDefault(emptySet())
        return { it in blocked }
    }

    /** Ein geprueftes Ergebnis in die Speicher uebernehmen (Verhalten wird vom Aufrufer auf das Geraet angewendet). */
    fun commit(result: ImportResult) {
        store.importProfiles(result.profiles)
        // Nur Einstellungen mergen - angewendet wird ein Lifecycle-Profil erst per Knopf
        lifecycleStore.importEntries(result.lifecycle)
        BackupExtras.apply(appContext, result.extras)
    }

    /** [parse] und sofort [commit] (fuer Skripte/Tests ohne Rueckfrage). */
    fun import(uri: Uri): ImportResult = parse(uri).also { commit(it) }

    companion object {
        /** Prueft ein bereits gelesenes Dokument (auch fuer Tests mit eigener Schutzliste). */
        fun sanitize(
            document: Pair<Map<String, Map<String, String>>, Map<String, BehaviorState>>,
            lifecycle: Map<String, LifecycleEntry>,
            extras: BackupExtras,
            isProtected: (String) -> Boolean
        ): ImportResult {
            val profiles = ImportGuard.profiles(document.first)
            val behavior = ImportGuard.behavior(document.second, isProtected)
            val life = ImportGuard.lifecycle(lifecycle, isProtected)
            val rules = ImportGuard.rules(extras.rules, isProtected)
            val wiu = ImportGuard.whileInUse(extras.whileInUse, isProtected)
            return ImportResult(
                profiles = profiles.value,
                behavior = behavior.value,
                lifecycle = life.value,
                extras = extras.copy(rules = rules.value, whileInUse = wiu.value),
                dropped = profiles.dropped + behavior.dropped + life.dropped + rules.dropped + wiu.dropped
            )
        }

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
