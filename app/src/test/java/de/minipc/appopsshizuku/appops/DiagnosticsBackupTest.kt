package de.minipc.appopsshizuku.appops

import de.minipc.appopsshizuku.appops.Diagnostics.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsBackupTest {

    @Test
    fun befehlBekanntOderNicht() {
        assertEquals(Status.FAIL, Diagnostics.commandKnown("Unknown command: set-permission-flags"))
        assertEquals(Status.FAIL, Diagnostics.commandKnown("Error: Unknown option: --dialogMessage"))
        assertEquals(Status.OK, Diagnostics.commandKnown("Error: no package specified"))
        assertEquals(Status.UNKNOWN, Diagnostics.commandKnown("  "))
    }

    @Test
    fun lesbarUndParser() {
        assertEquals(Status.OK, Diagnostics.readable(0, "opportunistic"))
        assertEquals(Status.FAIL, Diagnostics.readable(255, "x"))
        assertEquals(Status.FAIL, Diagnostics.readable(0, "java.lang.SecurityException: nope"))
        assertEquals(Status.UNKNOWN, Diagnostics.parserStatus(0, "viel Text, anderes Format", 0))
        assertEquals(Status.OK, Diagnostics.parserStatus(0, "x", 3))
        assertEquals(Status.FAIL, Diagnostics.parserStatus(1, "", 0))
    }

    @Test
    fun berichtZeilen() {
        val r = Diagnostics.report("Kopf", listOf("A" to Diagnostics.Result("a", Status.OK), "B" to Diagnostics.Result("b", Status.FAIL, "3")))
        assertTrue(r.contains("[OK]  A"))
        assertTrue(r.contains("[--]  B — 3"))
    }

    @Test
    fun extrasRoundtripUndFilter() {
        val e = BackupExtras(
            rules = listOf(AutoRule("1", "Nacht", startMin = 60, endMin = 120)),
            whileInUse = mapOf("com.a" to setOf("READ_CONTACTS")),
            quarantineEnabled = true,
            lockdownOps = setOf("CAMERA", "FINE_LOCATION")
        )
        assertEquals(e, BackupExtras.fromJson(e.toJson()))
        // Fremde Datei: reine AppOps / unbekannte Namen duerfen nicht in "Nur waehrend Nutzung" landen
        val evil = org.json.JSONObject("""{"whileInUse":{"com.a":["READ_CLIPBOARD","QUATSCH","CAMERA"]},"lockdownOps":["CAMERA","READ_SMS"]}""")
        val parsed = BackupExtras.fromJson(evil)
        assertEquals(mapOf("com.a" to setOf("CAMERA")), parsed.whileInUse)
        assertEquals(setOf("CAMERA"), parsed.lockdownOps)
        // Alte Dateien ohne extras
        assertTrue(BackupExtras.parse("[]").isEmpty)
        assertTrue(BackupExtras.parse("""{"version":2}""").isEmpty)
    }
}
