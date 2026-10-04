package de.minipc.appopsshizuku.appops

import de.minipc.appopsshizuku.shizuku.ShellBackend
import de.minipc.appopsshizuku.shizuku.ShellResult

/**
 * Test-Fake nach dem Muster von AppOpsControllerTest.FakeBackend (zeichnet Befehle auf),
 * aber mit Zustand - noetig, um die am Redmi gemessene Falle nachzustellen:
 * solange eine Akku-Ausnahme besteht, meldet das Geraet Standby-Stufe 5, egal was
 * `am set-standby-bucket` gesetzt hat (Befehl laeuft trotzdem fehlerfrei durch).
 */
class FakeDevice(
    var exempt: Boolean = false,
    /** Stufe, die ohne Akku-Ausnahme gilt (von set-standby-bucket gesetzt). */
    var userBucket: Int = 40,
    val modes: MutableMap<String, String> = mutableMapOf(),
    var suspended: Boolean = false,
    var stopped: Boolean = false,
    var permission: Boolean = true,
    /** Ops, deren Aenderung das Geraet stillschweigend ignoriert. */
    val ignoredOps: Set<String> = emptySet(),
    /** Geraet ignoriert pm suspend (z. B. Geraeteverwaltung). */
    var ignoreSuspend: Boolean = false,
    /** Netz: UIDs mit gesperrten Hintergrunddaten / Pakete ohne Netz / UIDs auf der netpolicy-Sperrliste. */
    val bgDenied: MutableSet<Int> = mutableSetOf(),
    val offline: MutableSet<String> = mutableSetOf(),
    val blacklist: MutableSet<Int> = mutableSetOf(),
    /** Am OnePlus gemessen: der erste Hintergrund-Set wirft sUidOwnerMap-Fehler und setzt den Wert trotzdem. */
    var bgSetThrowsOnce: Boolean = false,
    /** Geraet uebernimmt Netz-Aenderungen nicht (Befehl laeuft fehlerfrei durch). */
    var ignoreNetSets: Boolean = false,
    /** Firewall-Kette: am Redmi im Ausgangszustand AUS - Deny-Bit liest sich dann trotzdem als `deny`. */
    var chainEnabled: Boolean = true,
    /** Geraet laesst die Kette nicht einschalten. */
    var ignoreChainSet: Boolean = false
) : ShellBackend {

    val commands = mutableListOf<List<String>>()

    val bucket: Int get() = if (exempt) 5 else userBucket

    override fun isAvailable(): Boolean = permission
    override fun hasPermission(): Boolean = permission

    override fun exec(vararg args: String): ShellResult {
        val a = args.toList()
        commands.add(a)
        return when {
            a.take(3) == listOf("cmd", "deviceidle", "whitelist") -> {
                val arg = a[3]
                exempt = arg.startsWith("+")
                ok((if (exempt) "Added: " else "Removed: ") + arg.drop(1))
            }
            a == listOf("dumpsys", "deviceidle", "whitelist") ->
                ok("system-excidle,com.android.providers.calendar,10116\n" + if (exempt) "$PKG,10234\n" else "")
            a.take(2) == listOf("am", "set-standby-bucket") -> {
                val n = listOf("active" to 10, "working_set" to 20, "frequent" to 30, "rare" to 40,
                    "restricted" to 45, "never" to 50).toMap()[a[3]]
                if (n == null) ok("Error: Unknown bucket: ${a[3]}") else { userBucket = n; ok("") }
            }
            a.take(3) == listOf("cmd", "activity", "get-standby-bucket") -> ok("$bucket\n")
            a.take(3) == listOf("cmd", "appops", "set") -> {
                if (a[4] !in ignoredOps) modes[a[4]] = a[5]
                ok("")
            }
            a.take(3) == listOf("cmd", "appops", "get") -> {
                val m = modes[a[4]]
                ok(if (m == null || m == "default") "No operations." else "${a[4]}: $m; time=+1h2m3s")
            }
            a.take(3) == listOf("cmd", "connectivity", "set-background-networking-enabled-for-uid") -> {
                val uid = a[3].toInt()
                if (!ignoreNetSets) if (a[4] == "true") bgDenied.remove(uid) else bgDenied.add(uid)
                if (bgSetThrowsOnce) {
                    bgSetThrowsOnce = false
                    ShellResult(0, "", "java.lang.IllegalStateException: sUidOwnerMap does not have entry for uid: $uid (code 2)")
                } else ok("")
            }
            a.take(3) == listOf("cmd", "connectivity", "get-background-networking-enabled-for-uid") ->
                ok("${a[3]}: ${if (a[3].toInt() in bgDenied) "deny" else "allow"}")
            a.take(3) == listOf("cmd", "connectivity", "set-package-networking-enabled") -> {
                if (!ignoreNetSets) if (a[3] == "true") offline.remove(a[4]) else offline.add(a[4])
                ok("Disabled networking for ${a[4]}, appId 10641")
            }
            a.take(3) == listOf("cmd", "connectivity", "get-package-networking-enabled") ->
                ok("${a[3]}:${if (a[3] in offline) "deny" else "allow"}")
            a.take(3) == listOf("cmd", "connectivity", "get-chain3-enabled") ->
                ok("chain:${if (chainEnabled) "enabled" else "disabled"}")
            a.take(3) == listOf("cmd", "connectivity", "set-chain3-enabled") -> {
                if (!ignoreChainSet) chainEnabled = (a[3] == "true")
                ok("")
            }
            a.take(2) == listOf("cmd", "netpolicy") && a[2] == "add" -> { if (!ignoreNetSets) blacklist.add(a[4].toInt()); ok("") }
            a.take(2) == listOf("cmd", "netpolicy") && a[2] == "remove" -> { blacklist.remove(a[4].toInt()); ok("") }
            a == listOf("cmd", "netpolicy", "list", "restrict-background-blacklist") ->
                ok("Restrict background blacklisted UIDs: " + (blacklist.joinToString(" ").ifEmpty { "none" }))
            a.take(3) == listOf("cmd", "package", "compile") -> ok("Success")
            a.take(2) == listOf("am", "force-stop") -> { stopped = true; ok("") }
            a.take(2) == listOf("dumpsys", "package") ->
                ok("    User 0: ceDataInode=2622625 installed=true hidden=false suspended=$suspended stopped=$stopped enabled=0")
            else -> ShellResult(1, "", "unbekannter Befehl: $a")
        }
    }

    override fun suspendPackage(packageName: String): ShellResult {
        commands.add(listOf("pm", "suspend", packageName))
        if (!ignoreSuspend) suspended = true
        return ok("Package $packageName new suspended state: $suspended")
    }

    override fun unsuspendPackage(packageName: String): ShellResult {
        commands.add(listOf("pm", "unsuspend", packageName))
        suspended = false
        return ok("Package $packageName new suspended state: false")
    }

    /** Index des ersten Befehls, der mit [prefix] beginnt (-1 = nicht ausgefuehrt). */
    fun indexOf(vararg prefix: String): Int =
        commands.indexOfFirst { it.take(prefix.size) == prefix.toList() }

    private fun ok(out: String) = ShellResult(0, out, "")

    companion object {
        const val PKG = "com.brave.browser"
        const val OWN = "de.minipc.appopsshizuku"
    }
}
