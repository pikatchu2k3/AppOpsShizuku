package de.minipc.appopsshizuku.appops

import de.minipc.appopsshizuku.shizuku.ShellBackend

/**
 * Reiter "Netz & Tracker": die drei Netz-Hebel, die auf Android 16 per Shell-UID wirken
 * (am OnePlus verifiziert). Komponenten-Schalter (`pm disable-user <pkg>/<klasse>`) gehen dort
 * NICHT (SecurityException) und gibt es hier bewusst nicht.
 *
 * Wahrheit ist immer das Zuruecklesen: `cmd connectivity set-...` kann beim ersten Aufruf
 * `IllegalStateException: sUidOwnerMap does not have entry ...` werfen und den Wert trotzdem setzen.
 * Deshalb wird die Ausgabe des Setzens ignoriert und der Zustand neu gelesen.
 */
enum class NetSwitch {
    /** `set-background-networking-enabled-for-uid` (aktiv = Hintergrunddaten gesperrt). */
    BG_DATA,

    /** `set-package-networking-enabled` (aktiv = Paket komplett offline). */
    OFFLINE,

    /** `netpolicy restrict-background-blacklist` (aktiv = UID in der Liste). */
    BG_RESTRICT
}

/** `10641: deny` / `10641: allow` -> true = Hintergrunddaten ERLAUBT; null = nicht lesbar. */
fun parseBackgroundNetworking(out: String, uid: Int): Boolean? =
    Regex("""^\s*$uid\s*:\s*(allow|deny)\b""", RegexOption.MULTILINE).find(out)
        ?.groupValues?.get(1)?.let { it == "allow" }

/** `net.xelnaga.exchanger:deny` / `:allow` -> true = Netz ERLAUBT; null = nicht lesbar. */
fun parsePackageNetworking(out: String, pkg: String): Boolean? =
    Regex("""^\s*${Regex.escape(pkg)}\s*:\s*(allow|deny)\b""", RegexOption.MULTILINE).find(out)
        ?.groupValues?.get(1)?.let { it == "allow" }

/**
 * `cmd netpolicy list restrict-background-blacklist` -> steht [uid] in der Liste?
 * Format: "Restrict background blacklisted UIDs: 10641 10642" bzw. "...: none".
 * Es zaehlt nur der Text nach dem ersten ':' und nur GANZE Zahlen (1064 != 10641).
 */
fun parseBlacklistContains(out: String, uid: Int): Boolean =
    out.substringAfter(':', out).split(Regex("""\D+""")).any { it == uid.toString() }

/**
 * `cmd connectivity get-chain3-enabled` -> `chain:enabled` / `chain:disabled`; null = nicht lesbar.
 * Am Redmi gemessen: ist die Kette aus, laesst sich das Deny-Bit trotzdem setzen und liest sich
 * als `deny` zurueck - es wirkt dann nur nicht. Deshalb muss die Kette mitgeprueft werden.
 */
fun parseChainEnabled(out: String): Boolean? =
    Regex("""^\s*chain:(enabled|disabled)\b""", RegexOption.MULTILINE).find(out)
        ?.groupValues?.get(1)?.let { it == "enabled" }

class NetControl(private val backend: ShellBackend) {

    /**
     * true, wenn der LETZTE [set]-Aufruf die Firewall-Kette selbst einschalten musste.
     * Der Aufrufer merkt sich das, um sie beim Zuruecksetzen wieder abzuschalten.
     */
    var lastSetEnabledChain: Boolean = false
        private set

    /** true = Schalter aktiv (Netz eingeschraenkt), null = nicht lesbar. */
    fun read(sw: NetSwitch, pkg: String, uid: Int): Boolean? = when (sw) {
        NetSwitch.BG_DATA -> parseBackgroundNetworking(
            text("cmd", "connectivity", "get-background-networking-enabled-for-uid", uid.toString()), uid
        )?.not()
        NetSwitch.OFFLINE -> parsePackageNetworking(
            text("cmd", "connectivity", "get-package-networking-enabled", pkg), pkg
        )?.not()
        NetSwitch.BG_RESTRICT -> backend.exec("cmd", "netpolicy", "list", "restrict-background-blacklist")
            .let { if (it.exitCode == 0) parseBlacklistContains(it.stdout, uid) else null }
    }

    /** Ist-Zustand der Firewall-Kette (nur die Shell kann sie lesen). */
    fun chainEnabled(): Boolean? = parseChainEnabled(text("cmd", "connectivity", "get-chain3-enabled"))

    /** Schaltet die Firewall-Kette und liest zurueck. */
    fun setChain(on: Boolean): Boolean {
        backend.exec("cmd", "connectivity", "set-chain3-enabled", on.toString())
        return chainEnabled() == on
    }

    fun readAll(pkg: String, uid: Int): Map<NetSwitch, Boolean?> =
        NetSwitch.values().associateWith { read(it, pkg, uid) }

    /** Setzt [sw] und liest zurueck. true nur, wenn der gelesene Ist-Zustand == [on]. */
    fun set(sw: NetSwitch, pkg: String, uid: Int, on: Boolean): Boolean {
        lastSetEnabledChain = false
        when (sw) {
            NetSwitch.BG_DATA -> backend.exec(
                "cmd", "connectivity", "set-background-networking-enabled-for-uid", uid.toString(), (!on).toString()
            )
            NetSwitch.OFFLINE -> {
                // Ohne aktive Firewall-Kette setzt das Geraet das Deny-Bit, sperrt aber nichts.
                // Erst die Kette, dann das Bit - und nur melden, was wirklich wirkt.
                if (on && chainEnabled() == false) lastSetEnabledChain = setChain(true)
                backend.exec(
                    "cmd", "connectivity", "set-package-networking-enabled", (!on).toString(), pkg
                )
            }
            NetSwitch.BG_RESTRICT -> backend.exec(
                "cmd", "netpolicy", if (on) "add" else "remove", "restrict-background-blacklist", uid.toString()
            )
        }
        if (read(sw, pkg, uid) != on) return false
        return if (sw == NetSwitch.OFFLINE && on) chainEnabled() == true else true
    }

    /** stdout+stderr, damit auch Ausgaben auf stderr gelesen werden. */
    private fun text(vararg args: String): String =
        backend.exec(*args).let { it.stdout + "\n" + it.stderr }
}

/**
 * Merkt, welche Schalter WIR aktiviert haben ("Alles zuruecksetzen" fasst nur diese an).
 * Pure Kodierung (Komma-Liste) - die SharedPreferences-Huelle ist [NetStore].
 */
object NetMarks {
    fun encode(marks: Set<NetSwitch>): String = marks.joinToString(",") { it.name }

    fun decode(raw: String?): Set<NetSwitch> =
        raw.orEmpty().split(',').mapNotNull { n -> NetSwitch.values().firstOrNull { it.name == n } }.toSet()
}
