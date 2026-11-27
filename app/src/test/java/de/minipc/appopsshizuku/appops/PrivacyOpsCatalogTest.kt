package de.minipc.appopsshizuku.appops

import de.minipc.appopsshizuku.shizuku.ShellBackend
import de.minipc.appopsshizuku.shizuku.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Katalog-Vollstaendigkeit, Hebel-Zuordnung (pm vs. appops) und Ehrlichkeit bei
 * "nicht setzbar" fuer die Standort-/Telefon-/Datei-Ops (Geraetewerte: Redmi Note 4, Android 14).
 */
class PrivacyOpsCatalogTest {

    private val ops = PrivacyOps.PRIVACY_OPS
    private fun op(name: String) = ops.single { it.opName == name }

    private class Recorder(val result: ShellResult = ShellResult(0, "", "")) : ShellBackend {
        val commands = mutableListOf<List<String>>()
        override fun isAvailable() = true
        override fun hasPermission() = true
        override fun exec(vararg args: String): ShellResult {
            commands.add(args.toList()); return result
        }
    }

    // --- Vollstaendigkeit ---
    @Test fun sensibleOps_sindGenauEinmalImKatalog() {
        val erwartet = listOf(
            "FINE_LOCATION", "COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION", "ACCESS_MEDIA_LOCATION",
            "READ_PHONE_STATE", "READ_SMS", "RECEIVE_SMS", "BLUETOOTH_SCAN", "NEARBY_WIFI_DEVICES",
            "MANAGE_EXTERNAL_STORAGE", "POST_NOTIFICATION", "ACCESS_LOCAL_NETWORK"
        )
        for (name in erwartet) assertEquals("$name genau einmal", 1, ops.count { it.opName == name })
    }

    @Test fun opNamen_undLabels_sindEindeutig() {
        assertEquals(ops.size, ops.map { it.opName }.toSet().size)
        assertEquals(ops.size, ops.map { it.label }.toSet().size)
    }

    @Test fun kategorien_sindKonsistent() {
        val erlaubt = setOf("Privacy", "Standort", "Medien", "Hintergrund", "Identitaet", "System-UI", "Spezial")
        assertTrue(ops.all { it.category in erlaubt })
        assertEquals("Standort", op("FINE_LOCATION").category)
        assertEquals("Standort", op("COARSE_LOCATION").category)
        assertEquals("Standort", op("ACCESS_BACKGROUND_LOCATION").category)
        assertEquals("Standort", op("ACCESS_MEDIA_LOCATION").category)
        assertEquals("Medien", op("MANAGE_EXTERNAL_STORAGE").category)
        assertEquals("System-UI", op("POST_NOTIFICATION").category)
    }

    @Test fun standortPaar_istAnDenLabelsErkennbar() {
        assertEquals("Genauer Standort", op("FINE_LOCATION").label)
        assertEquals("Ungefährer Standort", op("COARSE_LOCATION").label)
    }

    // --- Namensfalle: AppOp-Name ohne ACCESS_-Praefix, Permission mit ---
    @Test fun standort_appOpNameOhnePraefix_permissionMitPraefix() {
        assertEquals("android.permission.ACCESS_FINE_LOCATION", op("FINE_LOCATION").permissionName)
        assertEquals("android.permission.ACCESS_COARSE_LOCATION", op("COARSE_LOCATION").permissionName)
        assertTrue(ops.none { it.opName == "ACCESS_FINE_LOCATION" || it.opName == "ACCESS_COARSE_LOCATION" })
    }

    @Test fun permissionNamen_sindAndroidPermissions() {
        assertTrue(ops.mapNotNull { it.permissionName }.all { it.startsWith("android.permission.") })
    }

    // --- Hebel-Zuordnung ---
    @Test fun manageExternalStorage_istReineAppOp() {
        assertNull(op("MANAGE_EXTERNAL_STORAGE").permissionName)
        val rec = Recorder()
        AppOpsController(rec).setMode("pkg", op("MANAGE_EXTERNAL_STORAGE"), "deny")
        assertEquals(listOf("cmd", "appops", "set", "pkg", "MANAGE_EXTERNAL_STORAGE", "deny"), rec.commands.single())
    }

    @Test fun manageExternalStorage_defaultGehtZurueckAufDefaultNichtAllow() {
        val rec = Recorder()
        AppOpsController(rec).setMode("pkg", op("MANAGE_EXTERNAL_STORAGE"), "default")
        assertEquals("default", rec.commands.single().last())
    }

    @Test fun permissionOps_nutzenPm_reineAppOpsNutzenAppops() {
        for (o in ops) {
            val rec = Recorder()
            AppOpsController(rec).setMode("pkg", o, "deny")
            // Flag-Befehle (user-fixed) sind Zusatz, der Hebel bleibt genau ein Befehl
            val cmd = rec.commands.filterNot { it.getOrNull(1)?.endsWith("permission-flags") == true }.single()
            if (o.permissionName != null) {
                assertEquals(listOf("pm", "revoke", "pkg", o.permissionName), cmd)
            } else {
                assertEquals(listOf("cmd", "appops", "set", "pkg", o.opName, "deny"), cmd)
            }
        }
    }

    @Test fun standort_revokeFine_grantFine_ueberPm() {
        val rec = Recorder()
        val c = AppOpsController(rec)
        c.setMode("pkg", op("FINE_LOCATION"), "deny")
        c.setMode("pkg", op("FINE_LOCATION"), "default")
        val levers = rec.commands.filterNot { it.getOrNull(1)?.endsWith("permission-flags") == true }
        assertEquals(listOf("pm", "revoke", "pkg", "android.permission.ACCESS_FINE_LOCATION"), levers[0])
        assertEquals(listOf("pm", "grant", "pkg", "android.permission.ACCESS_FINE_LOCATION"), levers[1])
    }

    @Test fun leseUeberPermission_nurFuerOpsDieDemAppOpRueckleseNichtTrauen() {
        val viaPerm = ops.filter { it.readViaPermission }.map { it.opName }.toSet()
        assertEquals(
            setOf("FINE_LOCATION", "COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION", "ACCESS_LOCAL_NETWORK"),
            viaPerm
        )
        assertTrue(ops.filter { it.readViaPermission }.all { it.permissionName != null })
    }

    // --- Rücklesen (Android 14: "Uid mode: <OP>: ignore" nach pm revoke, "No operations." sonst) ---
    @Test fun getMode_nachRevoke_uidModeIgnore() {
        val rec = Recorder(ShellResult(0, "Uid mode: READ_PHONE_STATE: ignore\n", ""))
        assertEquals("ignore", AppOpsController(rec).getMode("pkg", op("READ_PHONE_STATE")))
    }

    @Test fun getMode_manageExternalStorage_denyUndDefault() {
        val deny = Recorder(ShellResult(0, "MANAGE_EXTERNAL_STORAGE: deny; rejectTime=+5s ago\n", ""))
        assertEquals("deny", AppOpsController(deny).getMode("pkg", op("MANAGE_EXTERNAL_STORAGE")))
        val def = Recorder(ShellResult(0, "MANAGE_EXTERNAL_STORAGE: default; rejectTime=+50m ago\n", ""))
        assertEquals("default", AppOpsController(def).getMode("pkg", op("MANAGE_EXTERNAL_STORAGE")))
    }

    // --- "nicht setzbar": unbekannte Op liefert Exit 255 -> getMode null -> nie Erfolg ---
    @Test fun unbekannteOp_getModeNull_undNichtEffektiv() {
        val unknown = ShellResult(255, "", "Error: Unknown operation string: ACCESS_LOCAL_NETWORK")
        val o = op("ACCESS_LOCAL_NETWORK")
        assertNull(AppOpsController(Recorder(unknown)).getMode("pkg", o))
        assertFalse(OpEffect.isSetEffective(o.permissionName, "deny", null))
    }

    // --- permissionMode (dumpsys package) ---
    private val dump = """
        Packages:
          Package [org.omnirom.omnijaws] (abc):
            requested permissions:
              android.permission.ACCESS_BACKGROUND_LOCATION
            install permissions:
              android.permission.ACCESS_NETWORK_STATE: granted=true
            User 0: ceDataInode=1 installed=true
              runtime permissions:
                android.permission.ACCESS_FINE_LOCATION: granted=false, flags=[ USER_SET]
                android.permission.ACCESS_COARSE_LOCATION: granted=true, flags=[ GRANTED_BY_DEFAULT]
                android.permission.ACCESS_BACKGROUND_LOCATION: granted=true, flags=[ GRANTED_BY_DEFAULT]
    """.trimIndent()

    @Test fun permissionMode_liestGrantFlag() {
        assertEquals("deny", OpEffect.permissionMode(dump, "android.permission.ACCESS_FINE_LOCATION"))
        assertEquals("allow", OpEffect.permissionMode(dump, "android.permission.ACCESS_COARSE_LOCATION"))
        assertEquals("allow", OpEffect.permissionMode(dump, "android.permission.ACCESS_BACKGROUND_LOCATION"))
        assertEquals("allow", OpEffect.permissionMode(dump, "android.permission.ACCESS_NETWORK_STATE"))
    }

    @Test fun permissionMode_unbekanntOderNurAngefordert_istNull() {
        assertNull(OpEffect.permissionMode(dump, "android.permission.ACCESS_LOCAL_NETWORK"))
        assertNull(OpEffect.permissionMode("", "android.permission.ACCESS_FINE_LOCATION"))
        // nur "requested permissions"-Zeile ohne granted= -> nicht gelesen
        val nurAngefordert = "requested permissions:\n  android.permission.ACCESS_LOCAL_NETWORK\n"
        assertNull(OpEffect.permissionMode(nurAngefordert, "android.permission.ACCESS_LOCAL_NETWORK"))
    }

    @Test fun permissionMode_praefixIstExakt() {
        val d = "  android.permission.ACCESS_FINE_LOCATION_X: granted=true\n"
        assertNull(OpEffect.permissionMode(d, "android.permission.ACCESS_FINE_LOCATION"))
    }

    @Test fun nurUngefaehr_fineDenyCoarseAllow_istJeEffektiv() {
        assertTrue(OpEffect.isSetEffective(op("FINE_LOCATION").permissionName, "deny", "ignore"))
        assertTrue(OpEffect.isSetEffective(op("COARSE_LOCATION").permissionName, "allow", "allow"))
        assertNotNull(op("COARSE_LOCATION").permissionName)
    }

    // Reine AppOp MANAGE_EXTERNAL_STORAGE: exakter Modus zaehlt (deny != default)
    @Test fun manageExternalStorage_effekt_exaktesModus() {
        assertTrue(OpEffect.isSetEffective(null, "deny", "deny"))
        assertFalse(OpEffect.isSetEffective(null, "deny", "default"))
        assertTrue(OpEffect.isSetEffective(null, "default", "default"))
    }
}
