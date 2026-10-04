package de.minipc.appopsshizuku.appops

import de.minipc.appopsshizuku.shizuku.ShellBackend
import de.minipc.appopsshizuku.shizuku.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Entscheidung 09/2026: "Alle auf Standard" darf NIE Zugriff erteilen — bei Berechtigungs-Ops
 * wird entzogen (`pm revoke`, die App fragt selbst wieder), bei reinen AppOps bleibt `default`.
 * Ausserdem: "nicht angefordert" (App deklariert die Berechtigung nicht) ist etwas anderes als
 * "nicht setzbar" (Geraet kennt die Op nicht) und darf nicht als "wirksam gesetzt" gelten.
 */
class OpResetTest {

    private class Recorder : ShellBackend {
        val commands = mutableListOf<List<String>>()
        override fun isAvailable() = true
        override fun hasPermission() = true
        override fun exec(vararg args: String): ShellResult {
            commands.add(args.toList()); return ShellResult(0, "", "")
        }
    }

    private fun op(name: String) = PrivacyOps.PRIVACY_OPS.single { it.opName == name }

    // --- Reset-Semantik ---

    @Test
    fun permissionOp_resetEntziehtStattZuErteilen() {
        val rec = Recorder()
        val camera = op("CAMERA")
        assertEquals("deny", OpReset.modeFor(camera))
        AppOpsController(rec).setMode("pkg", camera, OpReset.modeFor(camera))
        assertEquals(
            listOf("pm", "revoke", "pkg", "android.permission.CAMERA"),
            rec.commands.single()
        )
    }

    @Test
    fun standortOp_resetEntziehtEbenfalls() {
        val rec = Recorder()
        val fine = op("FINE_LOCATION")
        AppOpsController(rec).setMode("pkg", fine, OpReset.modeFor(fine))
        assertEquals(
            listOf("pm", "revoke", "pkg", "android.permission.ACCESS_FINE_LOCATION"),
            rec.commands.single()
        )
    }

    @Test
    fun reineAppOp_resetGehtAufDefault() {
        val rec = Recorder()
        val clip = op("READ_CLIPBOARD")
        assertEquals("default", OpReset.modeFor(clip))
        AppOpsController(rec).setMode("pkg", clip, OpReset.modeFor(clip))
        assertEquals(
            listOf("cmd", "appops", "set", "pkg", "READ_CLIPBOARD", "default"),
            rec.commands.single()
        )
    }

    @Test
    fun alleDateien_resetGehtAufDefaultUndNichtAufRevoke() {
        val rec = Recorder()
        val alle = op("MANAGE_EXTERNAL_STORAGE")
        assertEquals("default", OpReset.modeFor(alle))
        AppOpsController(rec).setMode("pkg", alle, OpReset.modeFor(alle))
        assertEquals(
            listOf("cmd", "appops", "set", "pkg", "MANAGE_EXTERNAL_STORAGE", "default"),
            rec.commands.single()
        )
    }

    @Test
    fun resetModus_istFuerKeinenKatalogEintragEinErteilen() {
        for (o in PrivacyOps.PRIVACY_OPS) {
            val mode = OpReset.modeFor(o)
            assertFalse("${o.opName}: Reset darf nie erteilen", mode == "allow")
            if (o.permissionName != null) {
                assertEquals("${o.opName}: Permission-Op muss entzogen werden", "deny", mode)
            } else {
                assertEquals("${o.opName}: reine AppOp muss auf default", "default", mode)
            }
        }
    }

    // --- "nicht angefordert" vs "nicht setzbar" ---

    private val dump = """
        Package [com.example.app] (1234):
          requested permissions:
            android.permission.CAMERA
            android.permission.ACCESS_FINE_LOCATION
          runtime permissions:
            android.permission.CAMERA: granted=true, flags=[ USER_SET]
            android.permission.ACCESS_FINE_LOCATION: granted=false, flags=[ USER_SET]
    """.trimIndent()

    @Test
    fun deklariertePermission_liefertDenEchtenZustand() {
        assertEquals("allow", OpEffect.permissionState(dump, "android.permission.CAMERA"))
        assertEquals("deny", OpEffect.permissionState(dump, "android.permission.ACCESS_FINE_LOCATION"))
    }

    @Test
    fun nichtDeklariertePermission_giltAlsNichtAngefordert() {
        assertEquals(
            OpEffect.NOT_REQUESTED,
            OpEffect.permissionState(dump, "android.permission.ACCESS_BACKGROUND_LOCATION")
        )
    }

    @Test
    fun leererDump_bedeutetUnbekanntNichtAngefordert() {
        assertNull(OpEffect.permissionState("", "android.permission.CAMERA"))
        assertNull(OpEffect.permissionState("   ", "android.permission.CAMERA"))
    }

    @Test
    fun nichtAngefordert_giltNieAlsWirksamGesetzt() {
        assertFalse(
            OpEffect.isSetEffective(
                "android.permission.ACCESS_BACKGROUND_LOCATION", "allow", OpEffect.NOT_REQUESTED
            )
        )
        assertFalse(
            OpEffect.isSetEffective(
                "android.permission.ACCESS_BACKGROUND_LOCATION", "deny", OpEffect.NOT_REQUESTED
            )
        )
    }

    // --- "nicht angefordert" (App) vs "nicht setzbar" (Geraet kennt das Recht nicht) ---

    private val registry = """
        All Permissions:
        permission:android.permission.ACCESS_FINE_LOCATION
        package:android
        protectionLevel:dangerous
        permission:android.permission.POST_NOTIFICATIONS
        package:android
        protectionLevel:dangerous
    """.trimIndent()

    @Test
    fun geraetKenntRecht_aberAppFordertNicht_giltAlsNichtAngefordert() {
        assertEquals(
            OpEffect.NOT_REQUESTED,
            OpEffect.notRequestedOrUnknown(registry, "android.permission.POST_NOTIFICATIONS")
        )
    }

    @Test
    fun geraetKenntRechtNicht_giltAlsNichtSetzbar() {
        // ACCESS_LOCAL_NETWORK gibt es erst ab Android 17 -> auf Android 14 "nicht setzbar"
        assertNull(
            OpEffect.notRequestedOrUnknown(registry, "android.permission.ACCESS_LOCAL_NETWORK")
        )
    }

    @Test
    fun registryNichtLesbar_bleibtBeiNichtAngefordert() {
        assertEquals(
            OpEffect.NOT_REQUESTED,
            OpEffect.notRequestedOrUnknown(null, "android.permission.ACCESS_LOCAL_NETWORK")
        )
        assertEquals(
            OpEffect.NOT_REQUESTED,
            OpEffect.notRequestedOrUnknown("", "android.permission.ACCESS_LOCAL_NETWORK")
        )
    }

    @Test
    fun versionierteNamenWerdenNichtVerwechselt() {
        // "ACCESS_FINE_LOCATION" darf nicht als Treffer fuer "..._ALT" durchgehen
        val reg = "permission:android.permission.ACCESS_FINE_LOCATION\n"
        assertNull(
            OpEffect.notRequestedOrUnknown(reg, "android.permission.ACCESS_FINE_LOCATION_ALT")
        )
    }

    // --- AppOp-Ebene bei Berechtigungs-Ops mit zuruecksetzen ---

    @Test
    fun berechtigungsOp_brauchtZusaetzlichAppOpDefault() {
        val extras = OpReset.extraResetCommands("pkg", op("CAMERA"))
        assertEquals(listOf(listOf("cmd", "appops", "set", "pkg", "CAMERA", "default")), extras)
    }

    @Test
    fun reineAppOp_brauchtKeinenZusatzbefehl() {
        assertEquals(emptyList<List<String>>(), OpReset.extraResetCommands("pkg", op("READ_CLIPBOARD")))
    }

    @Test
    fun zusatzbefehleSindFuerKeinenEintragEinErteilen() {
        for (o in PrivacyOps.PRIVACY_OPS) {
            for (cmd in OpReset.extraResetCommands("pkg", o)) {
                assertFalse("${o.opName}: Zusatzbefehl darf nicht erteilen", cmd.contains("allow"))
                assertFalse("${o.opName}: Zusatzbefehl darf nicht erteilen", cmd.contains("grant"))
            }
        }
    }
}
