package de.minipc.appopsshizuku.appops

import de.minipc.appopsshizuku.shizuku.ShellBackend
import de.minipc.appopsshizuku.shizuku.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDemandStopTest {

    /** Zeichnet Befehle auf; dumpsys liefert einen festen Zustand, suspend kann scheitern. */
    private class Rec(val dump: String, val suspendWithMessageFails: Boolean = false) : ShellBackend {
        val cmds = mutableListOf<List<String>>()
        override fun isAvailable() = true
        override fun hasPermission() = true
        override fun exec(vararg args: String): ShellResult {
            cmds += args.toList()
            return when {
                args.firstOrNull() == "dumpsys" -> ShellResult(0, dump, "")
                "--dialogMessage" in args && suspendWithMessageFails -> ShellResult(255, "", "Error: Unknown option: --dialogMessage")
                else -> ShellResult(0, "", "")
            }
        }
        override fun suspendPackage(packageName: String): ShellResult = exec("pm", "suspend", "--user", "0", packageName)
    }

    @Test
    fun nurBeendenIstGesperrtWennGestoppt() {
        assertTrue(LockState(suspended = false, disabled = false, stopped = true).isLocked(OnDemandLock.STOP))
        assertFalse(LockState(suspended = false, disabled = false, stopped = false).isLocked(OnDemandLock.STOP))
        assertEquals(OnDemandLock.STOP, OnDemandLock.fromJson("stop"))
    }

    @Test
    fun nurBeendenFriertNichtEinUndDeaktiviertNicht() {
        val rec = Rec("User 0: ceDataInode=1 installed=true stopped=true suspended=false enabled=0")
        val r = OnDemandExecutor(rec).lock("com.x", OnDemandLock.STOP)
        assertTrue(r.ok)
        assertTrue(rec.cmds.any { it == listOf("am", "force-stop", "com.x") })
        assertTrue(rec.cmds.none { it.getOrNull(1) == "suspend" || it.getOrNull(1) == "disable-user" })
    }

    @Test
    fun einfrierenMitEigenemDialogtext() {
        val rec = Rec("User 0: stopped=true suspended=true enabled=0")
        OnDemandExecutor(rec, "Bitte ueber die Verknuepfung oeffnen").lock("com.x", OnDemandLock.SUSPEND)
        assertTrue(rec.cmds.any {
            it == listOf("pm", "suspend", "--user", "0", "--dialogMessage", "Bitte ueber die Verknuepfung oeffnen", "com.x")
        })
    }

    @Test
    fun geraetOhneDialogOptionFaelltZurueck() {
        val rec = Rec("User 0: stopped=true suspended=true enabled=0", suspendWithMessageFails = true)
        val r = OnDemandExecutor(rec, "Text").lock("com.x", OnDemandLock.SUSPEND)
        assertTrue(r.ok)
        assertTrue(rec.cmds.any { it == listOf("pm", "suspend", "--user", "0", "com.x") })
    }

    @Test
    fun nurBeendenAppsHerausfiltern() {
        val entries = mapOf(
            "a" to LifecycleEntry(onDemand = true, onDemandLock = OnDemandLock.STOP),
            "b" to LifecycleEntry(onDemand = true, onDemandLock = OnDemandLock.SUSPEND),
            "c" to LifecycleEntry(onDemand = true)
        )
        assertEquals(setOf("a"), OnDemand.stopApps(entries, entries.keys))
    }
}
