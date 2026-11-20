package de.minipc.appopsshizuku.appops

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests fuer das einheitliche Konfig-Backup (QW-9).
 * Geprueft werden die reinen Umwandlungen gegen ECHTE Ausgabeformate des Redmi
 * (Android 14) sowie der JSON-Rundlauf mit fehlenden Feldern.
 */
class BehaviorBackupTest {

    @Test
    fun standbyNamenWerdenKorrektAbgebildet() {
        assertEquals("active", standbyName(10))
        assertEquals("working_set", standbyName(20))
        assertEquals("frequent", standbyName(30))
        assertEquals("rare", standbyName(40))
        assertEquals("restricted", standbyName(45))
        assertEquals("never", standbyName(50))
    }

    @Test
    fun unbekannteStandbyStufeWirdNichtGeraten() {
        assertNull(standbyName(0))
        assertNull(standbyName(99))
        // 5 = EXEMPTED: am Geraet geprueft, per Shell nicht setzbar ("Unknown bucket: exempted")
        assertNull(standbyName(5))
    }

    @Test
    fun standbyWertWirdAusEchterAusgabeGelesen() {
        assertEquals(20, standbyBucketOf("20\n"))
        assertEquals(40, standbyBucketOf("40"))
        // Fehlermeldung statt Zahl -> null (kein geratener Wert)
        assertNull(standbyBucketOf("Exception occurred while executing 'get-standby-bucket'"))
        assertNull(standbyBucketOf(""))
    }

    @Test
    fun suspendZustandWirdAusDumpsysGelesen() {
        val enabled = "    User 0: ceDataInode=2622625 installed=true hidden=false suspended=false stopped=false enabled=0"
        val frozen = "    User 0: ceDataInode=2622625 installed=true hidden=false suspended=true stopped=true enabled=0"
        assertFalse(parseSuspended(enabled)!!)
        assertTrue(parseSuspended(frozen)!!)
        assertNull(parseSuspended("kein passendes Feld"))
    }

    @Test
    fun jsonRundlaufErhaeltAlleWerte() {
        val backup = BehaviorBackup(de.minipc.appopsshizuku.shizuku.NoopBackend())
        val states = mapOf(
            "com.brave.browser" to BehaviorState(40, true, false, false),
            "com.example.app" to BehaviorState(null, false, null, null)
        )
        val restored = backup.fromJson(backup.toJson(states))

        assertEquals(2, restored.size)
        val brave = restored["com.brave.browser"]!!
        assertEquals(40, brave.standbyBucket)
        assertTrue(brave.batteryExempt!!)
        assertFalse(brave.frozen!!)
        assertFalse(brave.disabled!!)

        // Fehlende Felder bleiben null -> beim Import wird nichts zurueckgesetzt
        val ex = restored["com.example.app"]!!
        assertNull(ex.standbyBucket)
        assertNull(ex.frozen)
        assertNull(ex.disabled)
        assertEquals(false, ex.batteryExempt)
    }

    @Test
    fun leeresBackupIstKeinFehler() {
        val backup = BehaviorBackup(de.minipc.appopsshizuku.shizuku.NoopBackend())
        assertEquals(0, backup.toJson(emptyMap()).length())
        assertTrue(backup.fromJson(null).isEmpty())
        assertTrue(backup.fromJson(JSONObject()).isEmpty())
    }

    @Test
    fun unbekannteFelderStoerenDenImportNicht() {
        val backup = BehaviorBackup(de.minipc.appopsshizuku.shizuku.NoopBackend())
        val json = JSONObject().put("com.x", JSONObject().put("unbekanntFeld", 5))
        // Keine bekannten Felder -> Eintrag ohne Werte, nichts wird angefasst
        val parsed = backup.fromJson(json)
        assertEquals(BehaviorState(), parsed["com.x"])
    }
}

/** Dateiformat des einheitlichen Backups (v2-Objekt + alte Array-Dateien). */
class ProfileBackupDocumentTest {

    @Test
    fun v2DokumentWirdGelesen() {
        val doc = """
            {"version":2,
             "profiles":[{"packageName":"com.brave.browser","opModes":{"CAMERA":"ignore"}}],
             "behavior":{"com.brave.browser":{"standbyBucket":20,"batteryExempt":false}}}
        """.trimIndent()
        val (profiles, behavior) = de.minipc.appopsshizuku.appops.ProfileBackup.parseDocument(doc)
        assertEquals(1, profiles.size)
        assertEquals("ignore", profiles["com.brave.browser"]?.get("CAMERA"))
        assertEquals(20, behavior["com.brave.browser"]?.standbyBucket)
        assertEquals(false, behavior["com.brave.browser"]?.batteryExempt)
    }

    @Test
    fun alteArrayDateiBleibtLesbar() {
        val old = """[{"packageName":"com.x","opModes":{"CAMERA":"deny"}}]"""
        val (profiles, behavior) = de.minipc.appopsshizuku.appops.ProfileBackup.parseDocument(old)
        assertEquals(1, profiles.size)
        assertEquals("deny", profiles["com.x"]?.get("CAMERA"))
        assertTrue(behavior.isEmpty())
    }

    @Test
    fun dokumentOhneVerhaltensteilIstKeinFehler() {
        val doc = """{"version":2,"profiles":[]}"""
        val (profiles, behavior) = de.minipc.appopsshizuku.appops.ProfileBackup.parseDocument(doc)
        assertTrue(profiles.isEmpty())
        assertTrue(behavior.isEmpty())
    }
}

/** Export-Sammlung: Sammel-Abfragen statt zwei Shell-Aufrufen je App. */
class BehaviorBackupCollectTest {

    private class Shell(private val bucketList: String) : de.minipc.appopsshizuku.shizuku.ShellBackend {
        val calls = java.util.Collections.synchronizedList(mutableListOf<List<String>>())
        override fun isAvailable() = true
        override fun hasPermission() = true
        override fun exec(vararg args: String): de.minipc.appopsshizuku.shizuku.ShellResult {
            val a = args.toList()
            calls.add(a)
            val out = when {
                a == listOf("pm", "list", "packages", "-d") -> "package:com.off\n"
                a == listOf("dumpsys", "deviceidle", "whitelist") -> "user,com.exempt,10123\n"
                a == listOf("cmd", "activity", "get-standby-bucket") -> bucketList
                a.take(3) == listOf("cmd", "activity", "get-standby-bucket") -> "40\n"
                a.take(2) == listOf("dumpsys", "package") -> "User 0: installed=true suspended=true stopped=true"
                else -> ""
            }
            return de.minipc.appopsshizuku.shizuku.ShellResult(0, out, "")
        }
    }

    private val pkgs = listOf("com.a", "com.off", "com.exempt")

    @Test
    fun sammelAbfrage_dreiAufrufeEgalWievieleApps() {
        val shell = Shell("com.a: 10\ncom.off: 45\ncom.exempt: 5\n")
        val r = BehaviorBackup(shell) { it == "com.a" }.collect(pkgs)
        assertEquals(3, shell.calls.size)
        assertEquals(BehaviorState(10, false, true, false), r["com.a"])
        assertEquals(BehaviorState(45, false, false, true), r["com.off"])
        assertEquals(BehaviorState(5, true, false, false), r["com.exempt"])
    }

    @Test
    fun ohneSammelformat_nurFehlendeEinzelnNachlesen() {
        // Geraet kennt die Sammel-Ausgabe nicht: Einzelabfragen, eingefroren weiter ohne Shell
        val shell = Shell("")
        val r = BehaviorBackup(shell) { false }.collect(pkgs)
        assertEquals(40, r["com.a"]!!.standbyBucket)
        assertEquals(false, r["com.a"]!!.frozen)
        assertEquals(0, shell.calls.count { it.take(2) == listOf("dumpsys", "package") })
        assertEquals(3, shell.calls.count { it.size == 4 && it[2] == "get-standby-bucket" })
    }

    @Test
    fun ohneSuspendLookup_wieBisherPerDumpsys() {
        val shell = Shell("com.a: 10\ncom.off: 45\ncom.exempt: 5\n")
        val r = BehaviorBackup(shell).collect(pkgs)
        assertEquals(true, r["com.a"]!!.frozen)
        assertEquals(3, shell.calls.count { it.take(2) == listOf("dumpsys", "package") })
    }

    @Test
    fun sammelParser_ueberspringtFremdeZeilen() {
        assertEquals(
            mapOf("com.a" to 10, "org.x.y" to 40),
            parseStandbyBuckets("com.a: 10\n\nWarnung: irgendwas\norg.x.y:40\nkein paket: 5\n")
        )
        assertTrue(parseStandbyBuckets("Exception occurred").isEmpty())
    }
}
