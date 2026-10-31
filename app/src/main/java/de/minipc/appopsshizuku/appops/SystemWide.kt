package de.minipc.appopsshizuku.appops

import android.content.Context

/**
 * Systemweite Hebel (Seite "Systemweit"): Lockdown-Szene, Private DNS, maechtige Rechte.
 * Hier nur die Android-freie Logik (Parser, Planung); Ausfuehren machen LockdownManager/SystemActivity.
 */

/** Lockdown: ausgewaehlte Berechtigungen allen Nutzer-Apps entziehen und GENAU diese spaeter zurueckgeben. */
object Lockdown {
    /** Waehlbare Ops (alle mit Runtime-Permission). */
    val CHOICES = listOf("CAMERA", "RECORD_AUDIO", "FINE_LOCATION", "COARSE_LOCATION")
    val DEFAULT = setOf("CAMERA", "RECORD_AUDIO")

    fun key(pkg: String, op: String) = "$pkg|$op"

    fun parseKey(k: String): Pair<String, String>? =
        k.split('|').takeIf { it.size == 2 && it.all { p -> p.isNotBlank() } }?.let { it[0] to it[1] }

    /**
     * @param granted Paket -> Ops (aus [ops]), die gerade ERTEILT sind; nur diese werden entzogen
     *        (was die App nie hatte, bekommt sie beim Beenden auch nicht).
     */
    fun plan(granted: Map<String, Set<String>>, ops: Set<String>): List<Pair<String, String>> =
        granted.flatMap { (pkg, set) -> set.filter { it in ops }.map { pkg to it } }
            .sortedWith(compareBy({ it.first }, { it.second }))
}

class LockdownStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("lockdown", Context.MODE_PRIVATE)

    var active: Boolean
        get() = prefs.getBoolean("active", false)
        set(v) = prefs.edit().putBoolean("active", v).apply()

    var since: Long
        get() = prefs.getLong("since", 0L)
        set(v) = prefs.edit().putLong("since", v).apply()

    var ops: Set<String>
        get() = prefs.getStringSet("ops", null)?.toSet() ?: Lockdown.DEFAULT
        set(v) = prefs.edit().putStringSet("ops", v).apply()

    /** Von uns entzogen ("pkg|OP") - nur diese werden beim Beenden zurueckgegeben. */
    var revoked: Set<String>
        get() = prefs.getStringSet("revoked", emptySet()).orEmpty().toSet()
        set(v) = prefs.edit().putStringSet("revoked", v).apply()
}

/** Private DNS (DNS-over-TLS) ueber `settings global private_dns_mode` / `private_dns_specifier`. */
object PrivateDns {
    const val MODE_OFF = "off"
    const val MODE_AUTO = "opportunistic"
    const val MODE_HOSTNAME = "hostname"

    data class Preset(val host: String, val name: String, val blocksTrackers: Boolean)

    val PRESETS = listOf(
        Preset("dns.adguard-dns.com", "AdGuard DNS", true),
        Preset("adblock.dns.mullvad.net", "Mullvad DNS (adblock)", true),
        Preset("dns.quad9.net", "Quad9", false),
        Preset("one.one.one.one", "Cloudflare", false)
    )

    data class State(val mode: String?, val host: String?)

    /** `settings get` liefert den Wert oder das Wort "null". */
    fun clean(out: String?): String? = out?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    /** Hostname plausibel? (DNS-Namen: Labels aus Buchstaben/Ziffern/Bindestrich, mind. ein Punkt) */
    fun isValidHost(h: String): Boolean =
        Regex("""^(?=.{4,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,63}$""").matches(h.trim())
}

/** Maechtige Rechte: Bedienungshilfen, Benachrichtigungszugriff, Tastaturen, Rollen. */
object PowerfulAccess {

    data class Component(val packageName: String, val className: String) {
        val flat: String get() = "$packageName/$className"
    }

    /**
     * `pkg/.Cls:pkg2/pkg2.Cls` -> Komponenten. Relative Klassennamen (`.Cls`) werden aufgeloest.
     * "null"/leer -> leere Liste.
     */
    fun parseComponents(raw: String?): List<Component> {
        val s = PrivateDns.clean(raw) ?: return emptyList()
        return s.split(':').mapNotNull { part ->
            val p = part.trim()
            val slash = p.indexOf('/')
            if (slash <= 0 || slash == p.length - 1) return@mapNotNull null
            val pkg = p.substring(0, slash)
            val cls = p.substring(slash + 1).let { if (it.startsWith(".")) pkg + it else it }
            Component(pkg, cls)
        }.distinct()
    }

    /** Liste ohne [remove] (Vergleich mit aufgeloesten Klassennamen), wieder als `a:b`. */
    fun without(raw: String?, remove: Component): String =
        parseComponents(raw).filter { it != remove }.joinToString(":") { it.flat }

    /** `cmd role get-role-holders <role>` -> Paketnamen (eine Zeile je Paket). */
    fun parseRoleHolders(out: String?): List<String> =
        out.orEmpty().lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && Regex("""^[a-zA-Z][\w.]*$""").matches(it) }.toList()

    /** Rollen, deren Inhaber besonders viel sehen. */
    val ROLES = listOf(
        "android.app.role.SMS",
        "android.app.role.DIALER",
        "android.app.role.ASSISTANT",
        "android.app.role.BROWSER"
    )
}
