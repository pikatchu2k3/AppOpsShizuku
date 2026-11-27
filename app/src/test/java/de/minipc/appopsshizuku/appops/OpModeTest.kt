package de.minipc.appopsshizuku.appops

import de.minipc.appopsshizuku.shizuku.ShellBackend
import de.minipc.appopsshizuku.shizuku.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ein unbekannter Modus darf nie erteilen; "reset" entzieht und gibt nie etwas frei. */
class OpModeTest {

    private class Rec : ShellBackend {
        val commands = mutableListOf<List<String>>()
        override fun isAvailable() = true
        override fun hasPermission() = true
        override fun exec(vararg args: String): ShellResult { commands.add(args.toList()); return ShellResult(0, "", "") }
    }

    private val cam = AppOp("CAMERA", "Kamera", "android.permission.CAMERA")
    private val clip = AppOp("READ_CLIPBOARD", "Zwischenablage", null)
    private val perm = "android.permission.CAMERA"

    @Test fun parse() {
        assertEquals(OpMode.ALLOW, OpMode.parse("allow"))
        assertEquals(OpMode.RESET, OpMode.parse("reset"))
        assertEquals(OpMode.DEFAULT, OpMode.parse("default"))
        assertNull(OpMode.parse("foo"))
        assertNull(OpMode.parse(""))
        assertNull(OpMode.parse(null))
        assertNull(OpMode.parse("ALLOW")) // Tokens sind exakt
    }

    @Test fun unbekannterModusFuehrtNichtsAusUndErteiltNie() {
        val b = Rec()
        val r = AppOpsController(b).setMode("com.x.y", cam, "irgendwas")
        assertTrue(r.exitCode != 0)
        assertTrue(b.commands.isEmpty())
        val r2 = AppOpsController(b).setMode("com.x.y", clip, "")
        assertTrue(r2.exitCode != 0)
        assertTrue(b.commands.isEmpty())
    }

    @Test fun resetEntziehtLoestFlagsUndSetztAppOpZurueck() {
        val b = Rec()
        AppOpsController(b).setMode("com.x.y", cam, "reset")
        assertEquals(listOf("pm", "revoke", "com.x.y", perm), b.commands[0])
        assertEquals(listOf("pm", "clear-permission-flags", "com.x.y", perm, "user-set", "user-fixed"), b.commands[1])
        assertEquals(listOf("cmd", "appops", "set", "com.x.y", "CAMERA", "default"), b.commands[2])
        assertFalse(b.commands.any { it.getOrNull(1) == "grant" })
    }

    @Test fun resetBeiReinenAppOpIstSystemstandard() {
        val b = Rec()
        AppOpsController(b).setMode("com.x.y", clip, "reset")
        assertEquals(listOf(listOf("cmd", "appops", "set", "com.x.y", "READ_CLIPBOARD", "default")), b.commands)
    }

    @Test fun restoreAllErteiltNie() {
        val b = Rec()
        AppOpsController(b).restoreAll("com.x.y", listOf(cam, clip))
        assertFalse(b.commands.any { it.getOrNull(1) == "grant" })
    }

    @Test fun altbestandDefaultBleibtNichtBlockierend() {
        // gelesener Ist-Zustand "default" (aeltere Android-Versionen) + Rueckgaengig: bleibt erteilen
        val b = Rec()
        AppOpsController(b).setMode("com.x.y", cam, "default")
        assertEquals(listOf("pm", "grant", "com.x.y", perm), b.commands[0])
    }

    @Test fun userIdWirdNurAufWunschGesetzt() {
        val b = Rec()
        AppOpsController(b).setMode("com.x.y", cam, "deny", userId = 10)
        assertEquals(listOf("pm", "revoke", "--user", "10", "com.x.y", perm), b.commands[0])
        val c = Rec()
        AppOpsController(c).setMode("com.x.y", clip, "deny", userId = 10)
        assertEquals(listOf("cmd", "appops", "set", "--user", "10", "com.x.y", "READ_CLIPBOARD", "deny"), c.commands[0])
        val d = Rec()
        AppOpsController(d).setMode("com.x.y", cam, "deny")
        assertEquals(listOf("pm", "revoke", "com.x.y", perm), d.commands[0])
    }

    @Test fun resetGiltAlsWirksamWennNichtErteilt() {
        assertTrue(OpEffect.isSetEffective(perm, "reset", "deny"))
        assertTrue(OpEffect.isSetEffective(perm, "reset", "ignore"))
        assertFalse(OpEffect.isSetEffective(perm, "reset", "allow"))
        assertTrue(OpEffect.isSetEffective(null, "reset".let { "default" }, "default"))
    }

    // ---- Flags aus `dumpsys package` ----

    private val dump = """
        Packages:
          Package [com.x.y] (abc):
            runtime permissions:
              android.permission.CAMERA: granted=false, flags=[ USER_SET|USER_FIXED]
              android.permission.RECORD_AUDIO: granted=true, flags=[ ]
              android.permission.READ_CONTACTS: granted=false, flags=[ USER_SET]
              android.permission.INTERNET: granted=true
    """.trimIndent()

    @Test fun flagsWerdenGelesen() {
        assertEquals(setOf("USER_SET", "USER_FIXED"), OpEffect.permissionFlags(dump, "android.permission.CAMERA"))
        assertEquals(emptySet<String>(), OpEffect.permissionFlags(dump, "android.permission.RECORD_AUDIO"))
        assertEquals(setOf("USER_SET"), OpEffect.permissionFlags(dump, "android.permission.READ_CONTACTS"))
        assertNull(OpEffect.permissionFlags(dump, "android.permission.INTERNET")) // keine Flag-Liste
        assertNull(OpEffect.permissionFlags(dump, "android.permission.NOPE"))
    }

    @Test fun userFixedNurWennGesetzt() {
        assertEquals(true, OpEffect.isUserFixed(dump, "android.permission.CAMERA"))
        assertEquals(false, OpEffect.isUserFixed(dump, "android.permission.READ_CONTACTS"))
        assertNull(OpEffect.isUserFixed(dump, "android.permission.INTERNET"))
    }
}
