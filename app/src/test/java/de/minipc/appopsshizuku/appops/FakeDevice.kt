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
    var ignoreSuspend: Boolean = false
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
