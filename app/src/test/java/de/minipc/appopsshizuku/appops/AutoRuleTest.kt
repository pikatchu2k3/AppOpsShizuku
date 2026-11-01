package de.minipc.appopsshizuku.appops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRuleTest {

    private fun timeRule(days: Int, s: Int, e: Int) =
        AutoRule("1", "r", trigger = RuleTrigger.TIME, days = days, startMin = s, endMin = e)

    @Test
    fun fensterTagsueber() {
        val r = timeRule(0b1111111, 9 * 60, 17 * 60)
        assertTrue(RuleEval.active(r, 0, 9 * 60, null))
        assertTrue(RuleEval.active(r, 0, 16 * 60 + 59, null))
        assertFalse(RuleEval.active(r, 0, 17 * 60, null)) // Ende exklusiv
        assertFalse(RuleEval.active(r, 0, 8 * 60, null))
    }

    @Test
    fun fensterUeberNacht() {
        // 23:00-07:00 nur Mo (bit0) als Starttag
        val r = timeRule(0b0000001, 23 * 60, 7 * 60)
        assertTrue(RuleEval.active(r, 0, 23 * 60 + 30, null)) // Mo 23:30
        assertTrue(RuleEval.active(r, 1, 3 * 60, null))       // Di 03:00 (gehoert zum Mo-Fenster)
        assertFalse(RuleEval.active(r, 1, 8 * 60, null))      // Di 08:00
        assertFalse(RuleEval.active(r, 2, 3 * 60, null))      // Mi 03:00 (Di war nicht aktiv)
    }

    @Test
    fun wochentagGrenze() {
        val r = timeRule(0b0100000, 10 * 60, 12 * 60) // nur Samstag (bit5)
        assertTrue(RuleEval.active(r, 5, 11 * 60, null))
        assertFalse(RuleEval.active(r, 4, 11 * 60, null))
    }

    @Test
    fun wlanVerbundenUndNicht() {
        val con = AutoRule("1", "r", trigger = RuleTrigger.WIFI, ssid = "Home", whenConnected = true)
        assertTrue(RuleEval.active(con, 0, 0, "Home"))
        assertFalse(RuleEval.active(con, 0, 0, "Other"))
        assertFalse(RuleEval.active(con, 0, 0, null))
        val dis = con.copy(whenConnected = false)
        assertTrue(RuleEval.active(dis, 0, 0, "Other"))
        assertTrue(RuleEval.active(dis, 0, 0, null))
        assertFalse(RuleEval.active(dis, 0, 0, "Home"))
    }

    @Test
    fun deaktivierteRegelNieAktiv() {
        assertFalse(RuleEval.active(timeRule(0b1111111, 0, 1440).copy(enabled = false), 0, 60, null))
    }

    @Test
    fun naechsteGrenze() {
        val rules = listOf(timeRule(0b1111111, 9 * 60, 17 * 60))
        // 08:00 -> naechste Grenze 09:00 = 60 min
        assertEquals(60, RuleEval.nextEdgeMinutes(rules, 0, 8 * 60))
        // 10:00 -> naechste Grenze 17:00 = 420 min
        assertEquals(420, RuleEval.nextEdgeMinutes(rules, 0, 10 * 60))
        // 18:00 -> morgen 09:00 = 900 min
        assertEquals(900, RuleEval.nextEdgeMinutes(rules, 0, 18 * 60))
        assertNull(RuleEval.nextEdgeMinutes(emptyList(), 0, 600))
    }

    @Test
    fun storeRoundtrip() {
        val r = AutoRule("abc", "Nacht", true, RuleTrigger.WIFI, 0b0101010, 100, 200, "SSID", false, RuleAction.APPS_OFFLINE, listOf("a", "b"))
        assertEquals(r, AutoRuleStore.fromJson(AutoRuleStore.toJson(r)))
    }
}
