package de.minipc.appopsshizuku.appops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhileInUseQuarantineTest {

    private val grace = WhileInUse.GRACE_MS

    @Test
    fun vorneErteilen_verlassenErstNachFristEntziehen() {
        val left = mutableMapOf<String, Long>()
        val apps = setOf("a")
        assertEquals(mapOf("a" to WhileInUse.Action.GRANT),
            WhileInUse.decide(0, apps, mapOf("a" to true), emptySet(), left))
        // Verlassen: innerhalb der Frist nichts
        assertEquals(emptyMap<String, WhileInUse.Action>(),
            WhileInUse.decide(1_000, apps, mapOf("a" to false), setOf("a"), left))
        assertEquals(emptyMap<String, WhileInUse.Action>(),
            WhileInUse.decide(1_000 + grace - 1, apps, mapOf("a" to false), setOf("a"), left))
        assertEquals(mapOf("a" to WhileInUse.Action.REVOKE),
            WhileInUse.decide(1_000 + grace, apps, mapOf("a" to false), setOf("a"), left))
    }

    @Test
    fun zurueckInnerhalbDerFristSetztZurueck() {
        val left = mutableMapOf<String, Long>()
        WhileInUse.decide(0, setOf("a"), mapOf("a" to false), setOf("a"), left)
        WhileInUse.decide(5_000, setOf("a"), mapOf("a" to true), setOf("a"), left)
        assertTrue(left.isEmpty())
        assertEquals(emptyMap<String, WhileInUse.Action>(),
            WhileInUse.decide(grace + 1, setOf("a"), mapOf("a" to false), setOf("a"), left))
    }

    @Test
    fun keineAussageAendertNichts_bildschirmAusEntziehtSofort() {
        val left = mutableMapOf<String, Long>()
        assertEquals(emptyMap<String, WhileInUse.Action>(),
            WhileInUse.decide(0, setOf("a"), mapOf("a" to null), setOf("a"), left))
        assertEquals(mapOf("a" to WhileInUse.Action.REVOKE),
            WhileInUse.decide(0, setOf("a", "b"), mapOf("a" to true), setOf("a"), left, screenOn = false))
    }

    @Test
    fun modusAusGeschaltet_wirdAusDerListeGenommen() {
        assertEquals(mapOf("x" to WhileInUse.Action.REVOKE),
            WhileInUse.decide(0, emptySet(), emptyMap(), setOf("x"), mutableMapOf()))
    }

    @Test
    fun quarantaeneNurNeueNutzerApps() {
        val c = listOf(
            Quarantine.Candidate("new.app", 2_000, false),
            Quarantine.Candidate("old.app", 500, false),
            Quarantine.Candidate("new.system", 2_000, true),
            Quarantine.Candidate("own", 2_000, false),
            Quarantine.Candidate("already", 2_000, false)
        )
        assertEquals(listOf("new.app"), Quarantine.newPackages(c, 1_000, "own", setOf("already")))
    }

    @Test
    fun quarantaeneBlockiertStillUndOhneBerechtigungsOps() {
        assertEquals("ignore", Quarantine.OP_MODE)
        for (name in Quarantine.OPS) {
            val op = PrivacyOps.PRIVACY_OPS.first { it.opName == name }
            assertEquals("$name muss eine reine AppOp sein", null, op.permissionName)
        }
    }
}
